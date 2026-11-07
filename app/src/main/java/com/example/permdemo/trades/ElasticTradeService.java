package com.example.permdemo.trades;

import com.example.permdemo.config.DemoProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Elasticsearch counterpart of TradeQueryService: same idea (a base
 * date-range filter plus OPA's compiled predicate, plus a keyset-style
 * page boundary), rendered as Elasticsearch Query DSL and sent to
 * {index}/_search instead of a SQL WHERE clause.
 */
@Service
public class ElasticTradeService {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper;
    private final String baseUrl;

    public ElasticTradeService(ObjectMapper mapper, DemoProperties props) {
        this.mapper = mapper;
        this.baseUrl = props.getElasticsearchUrl();
    }

    /** compiledClause == null means "no extra condition" -- AlwaysAllow was resolved upstream. */
    public JsonNode search(LocalDate from, LocalDate to, Map<String, Object> compiledClause,
                            int size, LocalDate afterDate, Long afterId) {
        List<Object> filters = new ArrayList<>();
        filters.add(Map.of("range", Map.of("trade_date", Map.of("gte", from.toString(), "lte", to.toString()))));
        if (compiledClause != null) {
            filters.add(compiledClause);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", size);
        body.put("sort", List.of(Map.of("trade_date", "asc"), Map.of("id", "asc")));
        body.put("query", Map.of("bool", Map.of("filter", filters)));
        if (afterDate != null && afterId != null) {
            body.put("search_after", List.of(afterDate.toString(), afterId));
        }

        return post("/trades/_search", body);
    }

    private JsonNode post(String path, Object body) {
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "Elasticsearch returned HTTP " + response.statusCode() + " for " + path + ": " + response.body());
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to call Elasticsearch at " + baseUrl + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling Elasticsearch at " + baseUrl + path, e);
        }
    }
}
