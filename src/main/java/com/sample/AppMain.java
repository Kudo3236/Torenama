package com.sample;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.slack.api.bolt.App;
import com.slack.api.bolt.jetty.SlackAppServer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppMain {

    private static final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    public static void main(String[] args) throws Exception {
        App app = new App();

        app.command("/items", (req, ctx) -> {
            ctx.ack(":hourglass_flowing_sand: Qiita記事を取得して output.json に保存します…");

            executor.submit(() -> {
                try {
                    String token = System.getenv("QIITA_TOKEN");
                    if (token == null || token.isBlank()) {
                        ctx.respond(r -> r.text(":x: QIITA_TOKEN が未設定です"));
                        return;
                    }

                    String queryText = req.getPayload().getText();
                    String url = buildQiitaUrl(queryText);

                    String json = fetch(url, token);

                    Object obj = mapper.readValue(json, Object.class);
                    String pretty = mapper.writeValueAsString(obj);

                    Files.writeString(Path.of("output.json"), pretty, StandardCharsets.UTF_8);

                    ctx.respond(r -> r.text(":white_check_mark: 保存完了：output.json（URL: " + url + "）"));
                } catch (Exception e) {
                    try {
                        ctx.respond(r -> r.text(":x: 失敗しました: " + e.getMessage()));
                    } catch (Exception ignore) {}
                    e.printStackTrace();
                }
            });

            return ctx.ack(); 
        });

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "3000"));
        new SlackAppServer(app, port).start();
    }

    private static String buildQiitaUrl(String queryText) {
        String base = "https://qiita.com/api/v2/items?page=1&per_page=20";
        if (queryText == null || queryText.isBlank()) return base;
        String encoded = URLEncoder.encode(queryText, StandardCharsets.UTF_8);
        return base + "&query=" + encoded;
    }

    private static String fetch(String url, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() / 100 != 2) {
            throw new RuntimeException("Qiita API HTTP " + res.statusCode() + " : " + res.body());
        }
        return res.body();
    }
}