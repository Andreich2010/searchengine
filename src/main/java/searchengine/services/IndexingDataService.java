package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.IndexEntity;
import searchengine.model.LemmaEntity;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class IndexingDataService {

    private static final int DELETE_RETRIES = 3;

    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final Object dbLock = new Object();
    private final ConcurrentHashMap<String, Object> lemmaLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> indexLocks = new ConcurrentHashMap<>();

    @Transactional
    public void savePageAndIndex(PageEntity page, Map<String, Integer> lemmas) {
        synchronized (dbLock) {
            pageRepository.save(page);
            for (Map.Entry<String, Integer> entry : lemmas.entrySet()) {
                String lemmaValue = entry.getKey();
                int rank = entry.getValue();
                synchronized (lockFor(page.getSite(), lemmaValue)) {
                    int updated = lemmaRepository.incrementFrequency(page.getSite(), lemmaValue);
                    LemmaEntity lemma;
                    if (updated == 0) {
                        lemma = new LemmaEntity();
                        lemma.setSite(page.getSite());
                        lemma.setLemma(lemmaValue);
                        lemma.setFrequency(1);
                        lemmaRepository.save(lemma);
                    } else {
                        lemma = lemmaRepository.findByLemmaAndSite(lemmaValue, page.getSite()).orElseThrow();
                    }
                    synchronized (indexLockFor(page, lemmaValue)) {
                        if (!indexRepository.existsByPageAndLemma(page, lemma)) {
                            IndexEntity index = new IndexEntity();
                            index.setPage(page);
                            index.setLemma(lemma);
                            index.setRank(rank);
                            indexRepository.save(index);
                        }
                    }
                }
            }
        }
    }

    @Transactional
    public void deletePageData(PageEntity page) {
        List<IndexEntity> indexes = indexRepository.findByPage(page);
        for (IndexEntity index : indexes) {
            LemmaEntity lemma = index.getLemma();
            int updated = lemma.getFrequency() - 1;
            if (updated <= 0) {
                lemmaRepository.delete(lemma);
            } else {
                lemma.setFrequency(updated);
                lemmaRepository.save(lemma);
            }
        }
        indexRepository.deleteAll(indexes);
        pageRepository.delete(page);
    }

    @Transactional
    public void clearSiteData(SiteEntity site) {
        synchronized (dbLock) {
            RuntimeException last = null;
            for (int attempt = 0; attempt < DELETE_RETRIES; attempt++) {
                try {
                    int siteId = site.getId();
                    indexRepository.deleteBySiteId(siteId);
                    lemmaRepository.deleteBySiteId(siteId);
                    pageRepository.deleteBySiteId(siteId);
                    return;
                } catch (RuntimeException ex) {
                    last = ex;
                    if (isDeadlock(ex) && attempt < DELETE_RETRIES - 1) {
                        sleepQuietly(200L * (attempt + 1));
                        continue;
                    }
                    throw ex;
                }
            }
            if (last != null) {
                throw last;
            }
        }
    }

    private Object lockFor(SiteEntity site, String lemma) {
        String key = site.getUrl() + ":" + lemma;
        return lemmaLocks.computeIfAbsent(key, k -> new Object());
    }

    private Object indexLockFor(PageEntity page, String lemma) {
        Integer pageId = page.getId();
        String pageKey = pageId != null ? pageId.toString() : page.getSite().getUrl() + "|" + page.getPath();
        String key = pageKey + ":" + lemma;
        return indexLocks.computeIfAbsent(key, k -> new Object());
    }

    private boolean isDeadlock(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof java.sql.SQLException) {
                java.sql.SQLException sql = (java.sql.SQLException) current;
                if (sql.getErrorCode() == 1213 || "40001".equals(sql.getSQLState())) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
