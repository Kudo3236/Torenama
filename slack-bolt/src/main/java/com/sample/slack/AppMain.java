package com.sample.slack;

import com.slack.api.bolt.App;
import com.slack.api.bolt.jetty.SlackAppServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppMain {

    private static final HttpClient client = HttpClient.newHttpClient();
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    public static void main(String[] args) throws Exception {
        App app = new App();

        app.command("/torenama-items", (req, ctx) -> {
            var ack = ctx.ack(":hourglass_flowing_sand: 更新を開始しました。完了したら通知します。");

            executor.submit(() -> {
                try {
                    String apiBaseUrl = System.getenv("API_BASE_URL");
                    if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
                        ctx.respond(r -> r.text(":x: API_BASE_URL が設定されていません"));
                        return;
                    }

                    String url = apiBaseUrl + "/admin/update";

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build();

                    HttpResponse<String> response = client.send(
                            request,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                    );

                    if (response.statusCode() / 100 != 2) {
                        ctx.respond(r -> r.text(":x: 更新失敗 HTTP " + response.statusCode() + "\n" + response.body()));
                        return;
                    }

                    ctx.respond(r -> r.text(":white_check_mark: 更新完了しました"));
                } catch (Exception e) {
                    try {
                        ctx.respond(r -> r.text(":x: 失敗しました: " + e.getMessage()));
                    } catch (Exception ignore) {
                    }
                    e.printStackTrace();
                }
            });

            return ack;
        });

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "3000"));
        new SlackAppServer(app, port).start();
    }
}