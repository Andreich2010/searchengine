package searchengine.services;

import searchengine.dto.response.ResultResponse;

public interface IndexingService {
    ResultResponse startIndexing();
    ResultResponse stopIndexing();
    ResultResponse indexPage(String url);
    boolean isIndexing();
}
