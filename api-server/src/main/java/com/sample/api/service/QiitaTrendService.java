package com.sample.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class QiitaTrendService {

    private static final Logger log = LoggerFactory.getLogger(QiitaTrendService.class);

    private static final int MAX_RETRY_COUNT = 3;
    private static final long RETRY_WAIT_MILLIS = 2000L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final JdbcTemplate jdbcTemplate;

    public QiitaTrendService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Value("${qiita.token}")
    private String qiitaToken;

    @Value("${qiita.per-page:20}")
    private int perPage;

    @Value("${qiita.page:1}")
    private int page;

    @Value("${trend.top-n:10}")
    private int topN;

    public String fetchAndSave() throws Exception {
        ensureTableExists();

        String url = "https://qiita.com/api/v2/items?page=" + page + "&per_page=" + perPage;
        log.info("Qiita update started. url={}, topN={}", url, topN);

        HttpResponse<String> response = callQiitaApiWithRetry(url);

        JsonNode root = objectMapper.readTree(response.body());
        if (!root.isArray()) {
            log.error("Qiita response is not array. body={}", response.body());
            throw new RuntimeException("Qiita response is not array.");
        }

        List<ObjectNode> rankedItems = new ArrayList<>();

        for (JsonNode item : root) {
            int likes = item.path("likes_count").asInt(0);
            int stocks = item.path("stocks_count").asInt(0);
            double score = likes * 0.6 + stocks * 0.4;

            ObjectNode node = objectMapper.createObjectNode();
            node.put("title", item.path("title").asText(""));
            node.put("url", item.path("url").asText(""));
            node.put("likes", likes);
            node.put("stocks", stocks);
            node.put("score", score);
            node.put("created_at", item.path("created_at").asText(""));
            node.put("user_id", item.path("user").path("id").asText(""));

            rankedItems.add(node);
        }

        rankedItems.sort(Comparator.comparingDouble(o -> -o.path("score").asDouble()));

        ArrayNode topItems = objectMapper.createArrayNode();
        for (int i = 0; i < Math.min(topN, rankedItems.size()); i++) {
            topItems.add(rankedItems.get(i));
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("generated_at", OffsetDateTime.now().toString());
        result.put("formula", "likes * 0.6 + stocks * 0.4");
        result.put("source", "Qiita API v2");
        result.set("items", topItems);

        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);

        try {
            jdbcTemplate.update(
                    "insert into trend_snapshots (payload) values (?)",
                    json
            );
            log.info("Qiita update succeeded. saved_items={}", topItems.size());
        } catch (Exception e) {
            log.error("Failed to save trend snapshot to DB.", e);
            throw e;
        }

        return json;
    }

    public String readSavedTrends() throws Exception {
        ensureTableExists();

        try {
            List<String> rows = jdbcTemplate.query(
                    "select payload from trend_snapshots order by created_at desc limit 1",
                    (rs, rowNum) -> rs.getString("payload")
            );

            if (rows.isEmpty()) {
                log.warn("No trend snapshots found. Returning empty result.");
                return buildEmptyResult();
            }

            return rows.get(0);
        } catch (Exception e) {
            log.error("Failed to read trend snapshots from DB.", e);
            throw e;
        }
    }

    private HttpResponse<String> callQiitaApiWithRetry(String url) throws Exception {
        int attempt = 0;
        Exception lastException = null;

        while (attempt < MAX_RETRY_COUNT) {
            attempt++;

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + qiitaToken)
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );

                int status = response.statusCode();
                log.info("Qiita API response received. attempt={}, status={}", attempt, status);

                if (status / 100 == 2) {
                    return response;
                }

                if (status == 429) {
                    log.warn("Qiita API rate limited. attempt={}, waiting={}ms", attempt, RETRY_WAIT_MILLIS);
                    if (attempt < MAX_RETRY_COUNT) {
                        Thread.sleep(RETRY_WAIT_MILLIS);
                        continue;
                    }
                }

                if ((status == 500 || status == 502 || status == 503 || status == 504) && attempt < MAX_RETRY_COUNT) {
                    log.warn("Qiita API temporary error. attempt={}, status={}, waiting={}ms", attempt, status, RETRY_WAIT_MILLIS);
                    Thread.sleep(RETRY_WAIT_MILLIS);
                    continue;
                }

                throw new RuntimeException("Qiita API error: HTTP " + status + " : " + response.body());

            } catch (Exception e) {
                lastException = e;
                log.warn("Qiita API call failed. attempt={}", attempt, e);

                if (attempt < MAX_RETRY_COUNT) {
                    Thread.sleep(RETRY_WAIT_MILLIS);
                }
            }
        }

        throw new RuntimeException("Qiita API call failed after retries.", lastException);
    }

    private void ensureTableExists() {
        jdbcTemplate.execute("""
            create table if not exists trend_snapshots (
                id bigserial primary key,
                payload text not null,
                created_at timestamptz not null default now()
            )
        """);
    }

    private String buildEmptyResult() throws Exception {
        ObjectNode empty = objectMapper.createObjectNode();
        empty.put("generated_at", (String) null);
        empty.put("formula", "likes * 0.6 + stocks * 0.4");
        empty.put("source", "Qiita API v2");
        empty.set("items", objectMapper.createArrayNode());
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(empty);
    }
}