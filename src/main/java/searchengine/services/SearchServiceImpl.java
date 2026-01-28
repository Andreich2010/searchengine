package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.dto.search.SearchResponse;
import searchengine.dto.search.SearchResultItem;
import searchengine.model.IndexEntity;
import searchengine.model.LemmaEntity;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;
import searchengine.model.SiteStatus;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.SiteRepository;
import searchengine.util.LemmaFinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private static final int SNIPPET_LENGTH = 200;

    private final SiteRepository siteRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final LemmaFinder lemmaFinder;

    @Override
    @Transactional(readOnly = true)
    public SearchResponse search(String query, String siteUrl, int offset, int limit) {
        if (query == null || query.isBlank()) {
            return SearchResponse.fail("Задан пустой поисковый запрос");
        }
        List<String> queryLemmas = lemmaFinder.getLemmaList(query);
        if (queryLemmas.isEmpty()) {
            return SearchResponse.fail("Задан пустой поисковый запрос");
        }

        List<SiteEntity> sites = resolveSites(siteUrl);
        if (sites.isEmpty()) {
            return SearchResponse.fail("Индекс не готов");
        }

        List<SearchResultItem> results = new ArrayList<>();
        for (SiteEntity site : sites) {
            List<LemmaEntity> lemmas = lemmaRepository.findByLemmaInAndSite(queryLemmas, site);
            if (lemmas.size() < queryLemmas.size()) {
                continue;
            }
            lemmas.sort(Comparator.comparingInt(LemmaEntity::getFrequency));
            Set<PageEntity> pages = collectPages(lemmas);
            if (pages.isEmpty()) {
                continue;
            }
            Map<PageEntity, Float> relevanceMap = calculateRelevance(lemmas, pages);
            float maxRelevance = relevanceMap.values().stream().max(Float::compare).orElse(1f);

            for (Map.Entry<PageEntity, Float> entry : relevanceMap.entrySet()) {
                PageEntity page = entry.getKey();
                float rel = entry.getValue() / maxRelevance;
                SearchResultItem item = buildResultItem(page, site, query);
                item.setRelevance(rel);
                results.add(item);
            }
        }

        results.sort(Comparator.comparing(SearchResultItem::getRelevance).reversed());
        int totalCount = results.size();
        int from = Math.min(Math.max(offset, 0), totalCount);
        int to = Math.min(from + Math.max(limit, 0), totalCount);
        List<SearchResultItem> pageResults = results.subList(from, to);

        SearchResponse response = new SearchResponse();
        response.setResult(true);
        response.setCount(totalCount);
        response.setData(pageResults);
        return response;
    }

    private List<SiteEntity> resolveSites(String siteUrl) {
        if (siteUrl == null || siteUrl.isBlank()) {
            return siteRepository.findByStatus(SiteStatus.INDEXED);
        }
        return siteRepository.findByUrl(siteUrl)
                .filter(site -> site.getStatus() == SiteStatus.INDEXED)
                .map(List::of)
                .orElseGet(List::of);
    }

    private Set<PageEntity> collectPages(List<LemmaEntity> lemmas) {
        Set<PageEntity> pages = new HashSet<>();
        boolean first = true;
        for (LemmaEntity lemma : lemmas) {
            Set<PageEntity> lemmaPages = indexRepository.findByLemma(lemma).stream()
                    .map(IndexEntity::getPage)
                    .collect(Collectors.toSet());
            if (first) {
                pages.addAll(lemmaPages);
                first = false;
            } else {
                pages.retainAll(lemmaPages);
            }
            if (pages.isEmpty()) {
                return pages;
            }
        }
        return pages;
    }

    private Map<PageEntity, Float> calculateRelevance(List<LemmaEntity> lemmas, Set<PageEntity> pages) {
        Map<PageEntity, Float> relevance = new HashMap<>();
        for (PageEntity page : pages) {
            float sum = 0f;
            for (IndexEntity index : indexRepository.findByPageAndLemmaIn(page, lemmas)) {
                sum += index.getRank();
            }
            relevance.put(page, sum);
        }
        return relevance;
    }

    private SearchResultItem buildResultItem(PageEntity page, SiteEntity site, String query) {
        SearchResultItem item = new SearchResultItem();
        item.setSite(site.getUrl());
        item.setSiteName(site.getName());
        item.setUri(page.getPath());
        item.setTitle(Jsoup.parse(page.getContent()).title());
        item.setSnippet(createSnippet(page.getContent(), query));
        return item;
    }

    private String createSnippet(String html, String query) {
        String text = Jsoup.parse(html).text();
        List<String> words = extractQueryWords(query);
        if (text.isBlank() || words.isEmpty()) {
            return "";
        }

        String lower = text.toLowerCase(Locale.ROOT);
        int startIndex = 0;
        for (String word : words) {
            int idx = lower.indexOf(word.toLowerCase(Locale.ROOT));
            if (idx >= 0) {
                startIndex = Math.max(0, idx - SNIPPET_LENGTH / 2);
                break;
            }
        }
        int endIndex = Math.min(text.length(), startIndex + SNIPPET_LENGTH);
        String snippet = text.substring(startIndex, endIndex);
        return highlight(snippet, words);
    }

    private List<String> extractQueryWords(String query) {
        Pattern pattern = Pattern.compile("[A-Za-zА-Яа-яЁё]+");
        Matcher matcher = pattern.matcher(query.toLowerCase(Locale.ROOT));
        List<String> words = new ArrayList<>();
        while (matcher.find()) {
            String word = matcher.group();
            if (!words.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    private String highlight(String text, List<String> words) {
        if (words.isEmpty()) {
            return text;
        }
        String pattern = words.stream()
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        return text.replaceAll("(?i)\\b(" + pattern + ")\\b", "<b>$1</b>");
    }
}
