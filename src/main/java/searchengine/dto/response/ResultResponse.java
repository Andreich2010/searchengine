package searchengine.dto.response;

import lombok.Data;

@Data
public class ResultResponse {
    private boolean result;
    private String error;

    public static ResultResponse ok() {
        ResultResponse response = new ResultResponse();
        response.setResult(true);
        return response;
    }

    public static ResultResponse fail(String error) {
        ResultResponse response = new ResultResponse();
        response.setResult(false);
        response.setError(error);
        return response;
    }
}
