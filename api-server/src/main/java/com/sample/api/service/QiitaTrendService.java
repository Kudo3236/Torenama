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

    // 最大リトライ回数
    private static final int MAX_RETRY_COUNT = 3;
    // リトライ時の待機時間（ミリ秒）
    private static final long RETRY_WAIT_MILLIS = 2000L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final JdbcTemplate jdbcTemplate;
    private final ZennTrendService zennTrendService;

    public QiitaTrendService(JdbcTemplate jdbcTemplate, ZennTrendService zennTrendService) {
        this.jdbcTemplate = jdbcTemplate;
        this.zennTrendService = zennTrendService;
    }

    @Value("${qiita.token}")
    private String qiitaToken;

    @Value("${qiita.per-page:20}")
    private int perPage;

    @Value("${qiita.page:1}")
    private int page;

    @Value("${trend.top-n:20}")
    private int topN;

    /**
     * Qiita + Zenn の記事を取得し、
     * スコア順にソートしてDBへ保存するメイン処理
     */
    public String fetchAndSave() throws Exception {
        // テーブルが存在しない場合は作成する
        ensureTableExists();

        List<ObjectNode> mergedItems = new ArrayList<>();

        // Qiita 取得
        List<ObjectNode> qiitaItems = fetchQiitaArticles();
        mergedItems.addAll(qiitaItems);

        // Zenn 取得
        try {
            List<ObjectNode> zennItems = zennTrendService.fetchZennArticles();
            mergedItems.addAll(zennItems);
        } catch (Exception e) {
            // Zenn取得失敗時でもQiitaだけで続行
            log.warn("Zenn fetch failed. Continue with Qiita only.", e);
        }

        // スコアで降順にソートする
        mergedItems.sort(Comparator.comparingDouble(o -> -o.path("score").asDouble()));

        // 上位N件抽出する
        ArrayNode topItems = objectMapper.createArrayNode();
        for (int i = 0; i < Math.min(topN, mergedItems.size()); i++) {
            topItems.add(mergedItems.get(i));
        }

        // レスポンス作成
        ObjectNode result = objectMapper.createObjectNode();
        result.put("generated_at", OffsetDateTime.now().toString());

        // QiitaとZennで計算式を変えている
        // Zennは最新記事とタグの数で計算
        result.put("formula", "qiita: likes * 0.6 + stocks * 0.4 / zenn: freshness_score + tag_bonus");
        result.put("source", "Qiita API v2 + Zenn RSS");
        result.set("items", topItems);

        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);

        // DB保存
        try {
            jdbcTemplate.update(
                    "insert into trend_snapshots (payload) values (?)",
                    json
            );
            log.info("Trend snapshot saved. merged_items={}", topItems.size());
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

    private List<ObjectNode> fetchQiitaArticles() throws Exception {

        List<ObjectNode> allItems = new ArrayList<>();

        int maxPage = 3; 

        for (int currentPage = 1; currentPage <= maxPage; currentPage++) {

            String url = "https://qiita.com/api/v2/items?page=" + currentPage + "&per_page=" + perPage;

            log.info("Qiita fetch page={}", currentPage);

            HttpResponse<String> response = callQiitaApiWithRetry(url);

            JsonNode root = objectMapper.readTree(response.body());

            for (JsonNode item : root) {

                int likes = item.path("likes_count").asInt(0);
                int stocks = item.path("stocks_count").asInt(0);

                double score = likes * 0.6 + stocks * 0.4;

                ArrayNode tags = objectMapper.createArrayNode();
                for (JsonNode tag : item.path("tags")) {
                    tags.add(tag.path("name").asText(""));
                }

                ObjectNode node = objectMapper.createObjectNode();
                node.put("title", item.path("title").asText(""));
                node.put("url", item.path("url").asText(""));
                node.put("likes", likes);
                node.put("stocks", stocks);
                node.put("score", score);
                node.put("created_at", item.path("created_at").asText(""));
                node.put("user_id", item.path("user").path("id").asText(""));
                node.put("source", "qiita");
                node.set("tags", tags);

                allItems.add(node);
            }
        }

        log.info("Qiita total fetched count={}", allItems.size());

        return allItems;
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
        empty.put("formula", "qiita: likes * 0.6 + stocks * 0.4 / zenn: freshness_score + tag_bonus");
        empty.put("source", "Qiita API v2 + Zenn RSS");
        empty.set("items", objectMapper.createArrayNode());
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(empty);
    }
}