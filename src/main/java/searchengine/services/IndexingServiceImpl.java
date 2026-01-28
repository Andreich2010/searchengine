package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.response.ResultResponse;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;
import searchengine.model.SiteStatus;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.util.LemmaFinder;

import java.net.URI;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String REFERRER = "http://www.google.com";
    private static final int WORKER_THREADS = 2;
    private static final int REQUEST_DELAY_MS = 200;
    private static final int SAVE_RETRIES = 3;

    private final SitesList sitesList;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final IndexingDataService indexingDataService;
    private final LemmaFinder lemmaFinder;

    private final AtomicBoolean indexing = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicInteger activeSites = new AtomicInteger(0);
    private final ConcurrentHashMap<String, ExecutorService> executors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> siteLocks = new ConcurrentHashMap<>();

    @Override
    public ResultResponse startIndexing() {
        if (!indexing.compareAndSet(false, true)) {
            return ResultResponse.fail("Индексация уже запущена");
        }
        stopRequested.set(false);
        List<Site> sites = sitesList.getSites();
        activeSites.set(sites.size());
        for (Site site : sites) {
            Thread thread = new Thread(() -> indexSite(site));
            thread.setDaemon(true);
            thread.start();
        }
        return ResultResponse.ok();
    }

    @Override
    public ResultResponse stopIndexing() {
        if (!indexing.get()) {
            return ResultResponse.fail("Индексация не запущена");
        }
        stopRequested.set(true);
        indexing.set(false);
        executors.values().forEach(ExecutorService::shutdownNow);
        executors.clear();
        List<SiteEntity> indexingSites = siteRepository.findByStatus(SiteStatus.INDEXING);
        for (SiteEntity site : indexingSites) {
            site.setStatus(SiteStatus.FAILED);
            site.setLastError("Индексация остановлена пользователем");
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);
        }
        return ResultResponse.ok();
    }

    @Override
    public ResultResponse indexPage(String url) {
        Site siteConfig = findSiteConfig(url);
        if (siteConfig == null) {
            return ResultResponse.fail("Данная страница находится за пределами сайтов, указанных в конфигурационном файле");
        }

        SiteEntity site = siteRepository.findByUrl(siteConfig.getUrl()).orElseGet(() -> {
            SiteEntity entity = new SiteEntity();
            entity.setUrl(siteConfig.getUrl());
            entity.setName(siteConfig.getName());
            entity.setStatus(SiteStatus.INDEXING);
            entity.setStatusTime(LocalDateTime.now());
            return siteRepository.save(entity);
        });

        String path = getPath(url, site.getUrl());
        pageRepository.findByPathAndSite(path, site).ifPresent(indexingDataService::deletePageData);

        try {
            savePageWithRetry(site, url, path);
            site.setStatus(SiteStatus.INDEXED);
            site.setLastError(null);
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);
            return ResultResponse.ok();
        } catch (Exception ex) {
            site.setStatus(SiteStatus.FAILED);
            site.setLastError(ex.getMessage());
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);
            return ResultResponse.fail("Ошибка индексации: " + ex.getMessage());
        }
    }

    @Override
    public boolean isIndexing() {
        return indexing.get();
    }

    private void indexSite(Site siteConfig) {
        SiteEntity site = prepareSiteEntity(siteConfig);
        String rootUrl = normalizeUrl(site.getUrl());
        AtomicBoolean siteIndexing = new AtomicBoolean(true);

        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        Set<String> visited = ConcurrentHashMap.newKeySet();
        queue.offer(rootUrl);
        visited.add(rootUrl);

        ExecutorService executor = Executors.newFixedThreadPool(WORKER_THREADS);
        executors.put(site.getUrl(), executor);
        AtomicInteger inProgress = new AtomicInteger(0);

        for (int i = 0; i < WORKER_THREADS; i++) {
            executor.submit(() -> workerLoop(site, rootUrl, queue, visited, inProgress, siteIndexing));
        }

        executor.shutdown();
        try {
            while (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                if (!indexing.get()) {
                    executor.shutdownNow();
                    break;
                }
            }
            if (indexing.get() && siteIndexing.get() && site.getStatus() != SiteStatus.FAILED) {
                site.setStatus(SiteStatus.INDEXED);
                site.setLastError(null);
            } else if (stopRequested.get() && site.getStatus() != SiteStatus.FAILED) {
                site.setStatus(SiteStatus.FAILED);
                site.setLastError("Индексация остановлена пользователем");
            } else if (site.getStatus() != SiteStatus.FAILED) {
                site.setStatus(SiteStatus.FAILED);
                site.setLastError("Индексация остановлена");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            site.setStatus(SiteStatus.FAILED);
            site.setLastError("Индексация прервана");
        } finally {
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);
            executors.remove(site.getUrl());
            if (activeSites.decrementAndGet() == 0) {
                indexing.set(false);
            }
        }
    }

    private SiteEntity prepareSiteEntity(Site siteConfig) {
        synchronized (siteLockFor(siteConfig.getUrl())) {
            siteRepository.findByUrl(siteConfig.getUrl()).ifPresent(existing -> {
                indexingDataService.clearSiteData(existing);
                siteRepository.delete(existing);
            });

            SiteEntity site = new SiteEntity();
            site.setUrl(siteConfig.getUrl());
            site.setName(siteConfig.getName());
            site.setStatus(SiteStatus.INDEXING);
            site.setStatusTime(LocalDateTime.now());
            site.setLastError(null);
            return siteRepository.save(site);
        }
    }

    private void workerLoop(SiteEntity site,
                            String rootUrl,
                            BlockingQueue<String> queue,
                            Set<String> visited,
                            AtomicInteger inProgress,
                            AtomicBoolean siteIndexing) {
        while (indexing.get() && siteIndexing.get() && !Thread.currentThread().isInterrupted()) {
            try {
                String url = queue.poll(500, TimeUnit.MILLISECONDS);
                if (url == null) {
                    if (queue.isEmpty() && inProgress.get() == 0) {
                        return;
                    }
                    continue;
                }
                inProgress.incrementAndGet();
                try {
                    processUrl(site, url, rootUrl, queue, visited, siteIndexing);
                } finally {
                    inProgress.decrementAndGet();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void processUrl(SiteEntity site,
                            String url,
                            String rootUrl,
                            BlockingQueue<String> queue,
                            Set<String> visited,
                            AtomicBoolean siteIndexing) {
        if (!indexing.get() || !siteIndexing.get()) {
            return;
        }
        String normalized = normalizeUrl(url);
        String path = getPath(normalized, rootUrl);
        if (pageRepository.existsByPathAndSite(path, site)) {
            return;
        }

        try {
            Connection connection = Jsoup.connect(normalized)
                    .userAgent(USER_AGENT)
                    .referrer(REFERRER)
                    .timeout(15000)
                    .ignoreHttpErrors(true)
                    .followRedirects(true);
            Connection.Response response = connection.execute();
            int statusCode = response.statusCode();
            String content = response.body();
            if (content == null) {
                content = "";
            }

            String effectiveUrl = normalizeUrl(response.url().toString());
            visited.add(effectiveUrl);
            String effectivePath = getPath(effectiveUrl, rootUrl);

            savePageWithRetry(site, effectiveUrl, effectivePath, statusCode, content);
            updateStatusTime(site);

            if (isRootPage(rootUrl, effectiveUrl) && statusCode >= 400) {
                site.setStatus(SiteStatus.FAILED);
                site.setLastError("Ошибка индексации: код ответа " + statusCode);
                site.setStatusTime(LocalDateTime.now());
                siteRepository.save(site);
                siteIndexing.set(false);
                return;
            }

            if (statusCode < 400) {
                Document document = Jsoup.parse(content, effectiveUrl);
                for (Element element : document.select("a[href]")) {
                    String link = normalizeUrl(element.absUrl("href"));
                    if (isValidLink(link, rootUrl) && visited.add(link)) {
                        queue.offer(link);
                    }
                }
            }
        } catch (Exception ex) {
            site.setLastError(ex.getMessage());
            siteRepository.save(site);
        } finally {
            sleepQuietly(REQUEST_DELAY_MS);
        }
    }

    private void savePageWithRetry(SiteEntity site, String url, String path) throws Exception {
        savePageWithRetry(site, url, path, null, null);
    }

    private void savePageWithRetry(SiteEntity site, String url, String path, Integer statusCode, String content) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < SAVE_RETRIES; attempt++) {
            try {
                if (statusCode == null || content == null) {
                    Connection connection = Jsoup.connect(url)
                            .userAgent(USER_AGENT)
                            .referrer(REFERRER)
                            .timeout(15000)
                            .ignoreHttpErrors(true)
                            .followRedirects(true);
                    Connection.Response response = connection.execute();
                    statusCode = response.statusCode();
                    content = response.body();
                    if (content == null) {
                        content = "";
                    }
                }

                PageEntity page = new PageEntity();
                page.setSite(site);
                page.setPath(path);
                page.setCode(statusCode);
                page.setContent(content);

                indexingDataService.savePageAndIndex(page, lemmaFinder.collectLemmas(Jsoup.parse(content).text()));
                return;
            } catch (Exception ex) {
                last = ex;
                if (isDeadlock(ex) && attempt < SAVE_RETRIES - 1) {
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

    private void updateStatusTime(SiteEntity site) {
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
    }

    private Site findSiteConfig(String url) {
        for (Site site : sitesList.getSites()) {
            if (isSameHost(url, site.getUrl())) {
                return site;
            }
        }
        return null;
    }

    private String getPath(String url, String rootUrl) {
        String normalized = normalizeUrl(url);
        if (!isSameHost(normalized, rootUrl)) {
            return "/";
        }
        String path = URI.create(normalized).getPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        String query = URI.create(normalized).getQuery();
        if (query != null && !query.isBlank()) {
            path = path + "?" + query;
        }
        return path;
    }

    private String normalizeUrl(String url) {
        String normalized = url.trim();
        normalized = normalized.replaceFirst("^(?i)(https?://)www\\.", "$1");
        int hashIndex = normalized.indexOf('#');
        if (hashIndex >= 0) {
            normalized = normalized.substring(0, hashIndex);
        }
        if (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean isValidLink(String link, String rootUrl) {
        if (link == null || link.isBlank()) {
            return false;
        }
        if (!isSameHost(link, rootUrl)) {
            return false;
        }
        if (link.contains("#")) {
            return false;
        }
        String lower = link.toLowerCase();
        return !(lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".png")
                || lower.endsWith(".gif")
                || lower.endsWith(".bmp")
                || lower.endsWith(".pdf")
                || lower.endsWith(".doc")
                || lower.endsWith(".docx")
                || lower.endsWith(".xls")
                || lower.endsWith(".xlsx")
                || lower.endsWith(".ppt")
                || lower.endsWith(".pptx")
                || lower.endsWith(".zip")
                || lower.endsWith(".rar")
                || lower.endsWith(".7z")
                || lower.endsWith(".svg"));
    }

    private boolean isRootPage(String rootUrl, String url) {
        String rootNormalized = normalizeUrl(rootUrl);
        String urlNormalized = normalizeUrl(url);
        if (!isSameHost(rootNormalized, urlNormalized)) {
            return false;
        }
        String path = URI.create(urlNormalized).getPath();
        return path == null || path.isBlank() || "/".equals(path);
    }

    private boolean isSameHost(String url, String rootUrl) {
        try {
            URI urlUri = URI.create(normalizeUrl(url));
            URI rootUri = URI.create(normalizeUrl(rootUrl));
            String urlHost = urlUri.getHost();
            String rootHost = rootUri.getHost();
            if (urlHost == null || rootHost == null) {
                return false;
            }
            return urlHost.equalsIgnoreCase(rootHost);
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isDeadlock(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SQLException) {
                SQLException sql = (SQLException) current;
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

    private Object siteLockFor(String siteUrl) {
        String key = normalizeUrl(siteUrl);
        return siteLocks.computeIfAbsent(key, k -> new Object());
    }
}
