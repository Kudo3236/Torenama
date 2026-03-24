package com.sample.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class ZennTrendService {

    private static final Logger log = LoggerFactory.getLogger(ZennTrendService.class);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<ObjectNode> fetchZennArticles() throws Exception {
        String url = "https://zenn.dev/feed";
        log.info("Zenn RSS fetch started. url={}", url);

        // RSS取得
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/rss+xml, application/xml, text/xml")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("Zenn RSS error: HTTP " + response.statusCode());
        }

        List<ObjectNode> results = new ArrayList<>();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();

        Document document = builder.parse(new InputSource(new StringReader(response.body())));
        NodeList itemNodes = document.getElementsByTagName("item");

        for (int i = 0; i < itemNodes.getLength(); i++) {
            Node itemNode = itemNodes.item(i);
            if (itemNode.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }

            Element item = (Element) itemNode;

            // タイトル・URL取得
            String title = getTagValue(item, "title");
            String link = getTagValue(item, "link");
            String pubDate = getTagValue(item, "pubDate");

            // タグ取得
            ArrayNode tags = objectMapper.createArrayNode();
            NodeList categories = item.getElementsByTagName("category");
            for (int j = 0; j < categories.getLength(); j++) {
                String tag = categories.item(j).getTextContent();
                if (tag != null && !tag.isBlank()) {
                    tags.add(tag.trim());
                }
            }

            // タグが1件もない記事は除外
            if (tags.isEmpty()) {
                continue;
            }

            // スコア計算
            double score = calculateZennScore(pubDate, tags.size());

            ObjectNode node = objectMapper.createObjectNode();
            node.put("title", title != null ? title : "");
            node.put("url", link != null ? link : "");
            // Zennの場合は0固定
            node.put("likes", 0);
            node.put("stocks", 0);
            node.put("score", score);
            node.put("created_at", pubDate != null ? pubDate : "");
            node.put("user_id", "");
            node.put("source", "zenn");
            node.set("tags", tags);

            results.add(node);
        }

        log.info("Zenn RSS fetch completed. article_count={}", results.size());
        return results;
    }

    private double calculateZennScore(String pubDate, int tagCount) {
        int freshnessScore = 0;
        int tagBonus = 0;

        try {
            if (pubDate != null && !pubDate.isBlank()) {
                ZonedDateTime published = ZonedDateTime.parse(pubDate, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
                long days = ChronoUnit.DAYS.between(published.toLocalDate(), OffsetDateTime.now().toLocalDate());

                if (days <= 1) {
                    freshnessScore = 15;
                } else if (days <= 3) {
                    freshnessScore = 10;
                } else if (days <= 7) {
                    freshnessScore = 5;
                } else if (days <= 14) {
                    freshnessScore = 3;
                } else {
                    freshnessScore = 0;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse Zenn pubDate: {}", pubDate, e);
        }

        if (tagCount >= 3) {
            tagBonus = 10;
        } else if (tagCount == 2) {
            tagBonus = 5;
        } else if (tagCount == 1) {
            tagBonus = 3;
        }

        return freshnessScore + tagBonus;
    }

    private String getTagValue(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagName(tagName);
        if (list.getLength() == 0) {
            return null;
        }
        Node node = list.item(0);
        return node != null ? node.getTextContent() : null;
    }
}