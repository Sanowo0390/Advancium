package si.mintymc.advancium;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

/** All calls run on the single I/O executor, never on the server tick thread. */
final class WebhookBoard {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final URI webhook;
    private final Path idFile;
    private final Logger log;
    private final int maxEntries, color, minimumInterval;
    private final String title, footer;
    private String messageId;
    private long lastSuccessfulSend = 0;

    WebhookBoard(String rawUrl, Path idFile, Logger log, int maxEntries, int color,
                 int minimumInterval, String title, String footer) {
        URI uri = URI.create(rawUrl.trim());
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) ||
            !("discord.com".equalsIgnoreCase(host) || "discordapp.com".equalsIgnoreCase(host)) ||
            !uri.getPath().matches("/api(?:/v\\d+)?/webhooks/\\d+/[^/]+")) {
            throw new IllegalArgumentException("Webhook URL must be an HTTPS Discord webhook URL");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Webhook URL must not contain query or fragment");
        }
        this.webhook = uri;
        this.idFile = idFile;
        this.log = log;
        this.maxEntries = Math.max(1, Math.min(20, maxEntries));
        this.color = color;
        this.minimumInterval = Math.max(10, minimumInterval) * 1000;
        this.title = title;
        this.footer = footer;
        try {
            if (Files.exists(idFile)) {
                String value = Files.readString(idFile, StandardCharsets.UTF_8).trim();
                if (value.matches("\\d{10,24}")) this.messageId = value;
            }
        } catch (IOException e) { log.warning("Webhook message ID を読み込めません: " + e.getMessage()); }
    }

    void postOrEdit(List<ProgressStore.Record> board, int total) {
        long now = System.currentTimeMillis();
        if (now - lastSuccessfulSend < minimumInterval) return;
        String description;
        if (board.isEmpty()) {
            description = "まだ進捗データがありません。";
        } else {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < Math.min(maxEntries, board.size()); i++) {
                ProgressStore.Record p = board.get(i);
                String medal = i == 0 ? "🥇" : i == 1 ? "🥈" : i == 2 ? "🥉" : "**" + (i + 1) + ".**";
                b.append(medal).append(" **").append(p.name().replace("*", "\\*").replace("`", ""))
                 .append("** — ").append(p.done()).append('/').append(total)
                 .append(" (**").append(String.format(Locale.ROOT, "%.2f", total == 0 ? 0 : p.done() * 100.0 / total))
                 .append("%**)\n");
            }
            description = b.toString();
        }
        String footerText = footer + " | 対象 " + total + " 件 | 登録 " + board.size() + " 人";
        String timestamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now());
        String payload = "{\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{\"title\":"
                + SimpleJson.quote(title) + ",\"description\":" + SimpleJson.quote(description)
                + ",\"color\":" + color + ",\"footer\":{\"text\":" + SimpleJson.quote(footerText)
                + "},\"timestamp\":" + SimpleJson.quote(timestamp) + "}]}";
        try {
            if (messageId != null) {
                HttpResponse<String> response = send("PATCH", webhook + "/messages/" + messageId, payload);
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    lastSuccessfulSend = System.currentTimeMillis(); return;
                }
                // A deleted message can be recreated. Other errors must not spam a new post.
                if (response.statusCode() != 404) {
                    log.warning("Webhook 編集失敗 HTTP " + response.statusCode()); return;
                }
                messageId = null;
            }
            HttpResponse<String> response = send("POST", webhook + "?wait=true", payload);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warning("Webhook 投稿失敗 HTTP " + response.statusCode()); return;
            }
            String id = SimpleJson.string(SimpleJson.object(SimpleJson.parse(response.body())).get("id"));
            if (!id.matches("\\d{10,24}")) {
                log.warning("Webhook メッセージ ID の取得に失敗しました。次回、再投稿する可能性があります。"); return;
            }
            messageId = id;
            Files.writeString(idFile, id, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            lastSuccessfulSend = System.currentTimeMillis();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Never include URI or response body: webhook token is secret.
            log.warning("Webhook に接続できません: " + e.getClass().getSimpleName());
        }
    }

    private HttpResponse<String> send(String method, String url, String payload) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "Advancium/1.0")
                .method(method, HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
