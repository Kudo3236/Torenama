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

public class AppMain {

    public static void main(String[] args) throws Exception {
        // 環境変数で渡す（後述）
        App app = new App();

        // /qiita [query] で検索クエリを渡せるようにする
        app.command("/qiita", (req, ctx) -> {
            // 3秒以内にackが必要なので、まず即応答
            ctx.ack(":hourglass_flowing_sand: Qiita記事を取得して output.json に保存します…");

            // 非同期で取得＆保存
            new Thread(() -> {
                try {
                    String queryText = req.getPayload().getText(); // 例: "java user:Qiita"
                    String url = buildQiitaUrl(queryText);

                    String json = fetch(url);

                    // JSONを整形して保存（整形不要ならそのままwriteStringでOK）
                    ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
                    Object obj = mapper.readValue(json, Object.class);
                    String pretty = mapper.writeValueAsString(obj);

                    Files.writeString(Path.of("output.json"), pretty, StandardCharsets.UTF_8);

                    ctx.respond(r -> r.text(":white_check_mark: 保存完了：output.json（取得URL: " + url + "）"));
                } catch (Exception e) {
                    try {
                        ctx.respond(r -> r.text(":x: 失敗しました: " + e.getMessage()));
                    } catch (Exception ignore) {}
                }
            }).start();

            return ctx.ack(); // 形式上（上でack済みだが問題回避用）
        });

        // Jettyで待ち受け（例: 3000）
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "3000"));
        new SlackAppServer(app, port).start();
    }

    private static String buildQiitaUrl(String queryText) {
        // デフォルトは最新20件
        String base = "https://qiita.com/api/v2/items?page=1&per_page=20";

        if (queryText == null || queryText.isBlank()) {
            return base;
        }
        String encoded = URLEncoder.encode(queryText, StandardCharsets.UTF_8);
        return base + "&query=" + encoded;
    }

    private static String fetch(String url) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() / 100 != 2) {
            throw new RuntimeException("Qiita API HTTP " + res.statusCode() + " : " + res.body());
        }
        return res.body();
    }
}