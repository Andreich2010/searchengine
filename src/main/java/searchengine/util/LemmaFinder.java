package searchengine.util;

import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LemmaFinder {

    private static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-zА-Яа-яЁё]+");
    private static final Set<String> RU_PARTS = Set.of("МЕЖД", "ПРЕДЛ", "СОЮЗ", "ЧАСТ");
    private static final Set<String> EN_PARTS = Set.of("CONJ", "PREP", "ARTICLE", "INT");

    private final LuceneMorphology russianMorphology;
    private final LuceneMorphology englishMorphology;

    public LemmaFinder() {
        try {
            this.russianMorphology = new RussianLuceneMorphology();
            this.englishMorphology = new EnglishLuceneMorphology();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to initialize Lucene morphology", ex);
        }
    }

    public Map<String, Integer> collectLemmas(String text) {
        Map<String, Integer> lemmas = new HashMap<>();
        for (String word : extractWords(text)) {
            List<String> normalForms = getNormalForms(word);
            if (normalForms.isEmpty()) {
                continue;
            }
            for (String lemma : normalForms) {
                lemmas.merge(lemma, 1, Integer::sum);
            }
        }
        return lemmas;
    }

    public List<String> getLemmaList(String text) {
        return new ArrayList<>(collectLemmas(text).keySet());
    }

    private List<String> extractWords(String text) {
        List<String> words = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return words;
        }
        Matcher matcher = WORD_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }

    private List<String> getNormalForms(String word) {
        if (word.isBlank()) {
            return List.of();
        }
        if (isRussian(word)) {
            return filterByParts(word, russianMorphology, RU_PARTS);
        }
        if (isEnglish(word)) {
            return filterByParts(word, englishMorphology, EN_PARTS);
        }
        return List.of();
    }

    private List<String> filterByParts(String word, LuceneMorphology morphology, Set<String> partTags) {
        List<String> morphInfo = morphology.getMorphInfo(word);
        for (String info : morphInfo) {
            for (String tag : partTags) {
                if (info.contains(tag)) {
                    return List.of();
                }
            }
        }
        return morphology.getNormalForms(word);
    }

    private boolean isRussian(String word) {
        for (char ch : word.toCharArray()) {
            if ((ch >= 'а' && ch <= 'я') || (ch >= 'А' && ch <= 'Я') || ch == 'ё' || ch == 'Ё') {
                return true;
            }
        }
        return false;
    }

    private boolean isEnglish(String word) {
        for (char ch : word.toCharArray()) {
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
                return true;
            }
        }
        return false;
    }
}
