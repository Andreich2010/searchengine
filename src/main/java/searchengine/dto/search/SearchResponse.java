package searchengine.dto.search;

import lombok.Data;

import java.util.List;

@Data
public class SearchResponse {
    private boolean result;
    private String error;
    private int count;
    private List<SearchResultItem> data;

    public static SearchResponse ok(List<SearchResultItem> data) {
        SearchResponse response = new SearchResponse();
        response.setResult(true);
        response.setData(data);
        response.setCount(data.size());
        return response;
    }

    public static SearchResponse fail(String error) {
        SearchResponse response = new SearchResponse();
        response.setResult(false);
        response.setError(error);
        response.setCount(0);
        return response;
    }
}
