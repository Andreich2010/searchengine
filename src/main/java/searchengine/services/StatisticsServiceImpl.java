package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.model.SiteEntity;
import searchengine.model.SiteStatus;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private final SitesList sites;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexingService indexingService;

    @Override
    public StatisticsResponse getStatistics() {
        TotalStatistics total = new TotalStatistics();
        total.setSites(sites.getSites().size());
        total.setIndexing(indexingService.isIndexing());

        List<DetailedStatisticsItem> detailed = new ArrayList<>();
        for (Site site : sites.getSites()) {
            DetailedStatisticsItem item = new DetailedStatisticsItem();
            item.setName(site.getName());
            item.setUrl(site.getUrl());

            SiteEntity siteEntity = siteRepository.findByUrl(site.getUrl()).orElse(null);
            if (siteEntity == null) {
                item.setStatus(SiteStatus.FAILED.name());
                item.setError("Индексация не запускалась");
                item.setStatusTime(0L);
                item.setPages(0);
                item.setLemmas(0);
            } else {
                item.setStatus(siteEntity.getStatus().name());
                item.setError(siteEntity.getLastError());
                long statusTime = siteEntity.getStatusTime()
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli();
                item.setStatusTime(statusTime);
                int pages = (int) pageRepository.countBySite(siteEntity);
                int lemmas = (int) lemmaRepository.countBySite(siteEntity);
                item.setPages(pages);
                item.setLemmas(lemmas);
                total.setPages(total.getPages() + pages);
                total.setLemmas(total.getLemmas() + lemmas);
            }
            detailed.add(item);
        }

        StatisticsResponse response = new StatisticsResponse();
        StatisticsData data = new StatisticsData();
        data.setTotal(total);
        data.setDetailed(detailed);
        response.setStatistics(data);
        response.setResult(true);
        return response;
    }
}
