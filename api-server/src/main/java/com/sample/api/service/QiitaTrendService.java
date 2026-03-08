package com.sample.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class QiitaTrendService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${qiita.token}")
    private String qiitaToken;

    @Value("${qiita.per-page:20}")
    private int perPage;

    @Value("${qiita.page:1}")
    private int page;

    @Value("${trend.output-path:data/items.json}")
    private String outputPath;

    @Value("${trend.top-n:10}")
    private int topN;

    public String fetchAndSave() throws Exception {
        String url = "https://qiita.com/api/v2/items?page=" + page + "&per_page=" + perPage;

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

        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("Qiita API error: HTTP " + response.statusCode() + " : " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        if (!root.isArray()) {
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

        Path path = Path.of(outputPath);
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result),
                StandardCharsets.UTF_8
        );

        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
    }

    public String readSavedTrends() throws Exception {
        Path path = Path.of(outputPath);

        if (!Files.exists(path)) {
            ObjectNode empty = objectMapper.createObjectNode();
            empty.put("generated_at", (String) null);
            empty.put("formula", "likes * 0.6 + stocks * 0.4");
            empty.put("source", "Qiita API v2");
            empty.set("items", objectMapper.createArrayNode());

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(empty);
        }

        return Files.readString(path, StandardCharsets.UTF_8);
    }
}