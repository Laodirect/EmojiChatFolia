package com.example.emojichat;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public final class UploadServer {

    private final JavaPlugin plugin;
    private final DynamicEmojiService service;
    private HttpServer server;
    private ExecutorService executor;
    private volatile Semaphore packDownloadSemaphore = new Semaphore(1, true);
    private final AtomicInteger packDownloadWaiters = new AtomicInteger(0);

    public UploadServer(JavaPlugin plugin, DynamicEmojiService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("upload-server.enabled", true)) {
            plugin.getLogger().info("Emoji upload server is disabled.");
            return;
        }
        String bind = plugin.getConfig().getString("upload-server.bind", "0.0.0.0");
        int port = plugin.getConfig().getInt("upload-server.port", 8123);
        try {
            int maxConcurrentDownloads = Math.max(1, plugin.getConfig().getInt("resource-pack.download-queue.max-concurrent-downloads", 1));
            this.packDownloadSemaphore = new Semaphore(maxConcurrentDownloads, true);
            int backlog = Math.max(16, plugin.getConfig().getInt("upload-server.backlog", 128));
            server = HttpServer.create(new InetSocketAddress(bind, port), backlog);
            server.createContext("/upload", this::handleUpload);
            server.createContext("/packs", this::handlePack);
            if (plugin.getConfig().getBoolean("qq-bot-api.enabled", false)) {
                String qqPath = qqBotApiPath();
                server.createContext(qqPath, this::handleQqBotUpload);
                plugin.getLogger().info("QQ bot emoji upload API reserved at " + qqPath + " . Keep qq-bot-api.auth-token secret.");
            }
            int defaultHttpThreads = Math.max(4, maxConcurrentDownloads + 3);
            int maxHttpThreads = Math.max(2, plugin.getConfig().getInt("upload-server.max-http-threads", defaultHttpThreads));
            executor = Executors.newFixedThreadPool(maxHttpThreads, runnable -> {
                Thread thread = new Thread(runnable, "NTCEmojiChatFolia-UploadServer");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.start();
            plugin.getLogger().info("Emoji upload server listening on " + bind + ":" + port
                    + "; pack download concurrency=" + maxConcurrentDownloads
                    + "; http threads=" + maxHttpThreads
                    + "; backlog=" + backlog + ".");
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to start emoji upload server", exception);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void handleUpload(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String token = path.substring("/upload".length()).replaceFirst("^/", "");
        if (token.isBlank()) {
            sendText(exchange, 404, "Missing upload token.");
            return;
        }

        Optional<UploadToken> uploadToken = service.getUploadToken(token);
        if (uploadToken.isEmpty()) {
            sendText(exchange, 410, "Upload token is invalid or expired. Please run /emoji upload <name> again.");
            return;
        }

        if (exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            sendHtml(exchange, uploadForm(uploadToken.get()));
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendText(exchange, 405, "Method not allowed.");
            return;
        }

        try {
            int maxKb = plugin.getConfig().getInt("upload-server.max-file-size-kb", 1024);
            long maxBytes = maxKb * 1024L;
            byte[] body = readLimited(exchange, maxBytes + 128 * 1024L);
            MultipartFile file = extractMultipartFile(exchange.getRequestHeaders(), body);
            if (file.bytes().length == 0) {
                throw new IOException("请选择一个图片文件。");
            }
            if (file.bytes().length > maxBytes) {
                throw new IOException("图片过大：最大允许 " + maxKb + " KB。");
            }
            DynamicEmoji emoji = service.completeUpload(token, file.bytes(), file.filename());
            service.notifyOwnerUploadComplete(emoji.owner(), emoji);
            String serverName = serverName();
            sendHtml(exchange, "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + escapeHtml(serverName) + " 上传完成</title></head>"
                    + "<body style=\"font-family:sans-serif;max-width:640px;margin:32px auto;line-height:1.6\">"
                    + "<h2>上传完成</h2><p>表情 <b>" + escapeHtml(emoji.token()) + "</b> 已保存到 " + escapeHtml(serverName) + "。</p>"
                    + "<p>你可以关闭此页面并返回 Minecraft。如需要刷新材质，请在游戏内执行 <code>/emoji sync</code>。</p></body></html>");
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Emoji upload failed", exception);
            sendHtml(exchange, "<!doctype html><html><head><meta charset=\"utf-8\"><title>上传失败</title></head>"
                    + "<body style=\"font-family:sans-serif;max-width:640px;margin:32px auto;line-height:1.6\">"
                    + "<h2>上传失败</h2><pre>" + escapeHtml(exception.getMessage()) + "</pre>"
                    + "<p>请返回游戏内重新执行 <code>/emoji upload &lt;name&gt;</code> 获取新的上传链接。</p></body></html>");
        }
    }

    private void handleQqBotUpload(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals(qqBotApiPath())) {
            sendText(exchange, 404, "Not found.");
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendJson(exchange, 405, "{\"success\":false,\"error\":\"method_not_allowed\",\"message\":\"Use POST multipart/form-data.\"}");
            return;
        }

        String configuredToken = plugin.getConfig().getString("qq-bot-api.auth-token", "change-me");
        if (configuredToken == null || configuredToken.isBlank() || configuredToken.equals("change-me")) {
            sendJson(exchange, 503, "{\"success\":false,\"error\":\"api_token_not_configured\",\"message\":\"Set qq-bot-api.auth-token before enabling the API.\"}");
            return;
        }
        if (!constantTimeEquals(configuredToken, providedApiToken(exchange))) {
            sendJson(exchange, 401, "{\"success\":false,\"error\":\"unauthorized\",\"message\":\"Missing or invalid API token.\"}");
            return;
        }

        try {
            Map<String, String> params = queryParameters(exchange.getRequestURI().getRawQuery());
            String rawName = firstNonBlank(params.get("name"), params.get("emojiName"), params.get("emoji"));
            if (rawName == null) {
                throw new IOException("Missing required query parameter: name.");
            }

            int maxKb = plugin.getConfig().getInt("upload-server.max-file-size-kb", 1024);
            long maxBytes = maxKb * 1024L;
            byte[] body = readLimited(exchange, maxBytes + 128 * 1024L);
            MultipartFile file = extractMultipartFile(exchange.getRequestHeaders(), body);
            if (file.bytes().length == 0) {
                throw new IOException("请选择一个图片文件。");
            }
            if (file.bytes().length > maxBytes) {
                throw new IOException("图片过大：最大允许 " + maxKb + " KB。");
            }

            UUID owner = parseUuidOrDefault(params.get("ownerUuid"), plugin.getConfig().getString("qq-bot-api.default-owner-uuid", "00000000-0000-0000-0000-000000000000"));
            String ownerName = firstNonBlank(params.get("ownerName"), plugin.getConfig().getString("qq-bot-api.default-owner-name", "KiKi-FusionBot"));
            boolean addToOwnerLibrary = plugin.getConfig().getBoolean("qq-bot-api.add-to-owner-library", false);
            String sourceDescription = qqSourceDescription(params);

            DynamicEmoji emoji = service.completeExternalUpload(owner, ownerName, rawName, file.bytes(), file.filename(), sourceDescription, addToOwnerLibrary);
            sendJson(exchange, 201, "{"
                    + "\"success\":true,"
                    + "\"id\":\"" + escapeJson(emoji.id()) + "\","
                    + "\"token\":\"" + escapeJson(emoji.token()) + "\","
                    + "\"name\":\"" + escapeJson(emoji.name()) + "\","
                    + "\"ownerUuid\":\"" + escapeJson(emoji.owner().toString()) + "\","
                    + "\"ownerName\":\"" + escapeJson(emoji.ownerName()) + "\","
                    + "\"packUrl\":\"" + escapeJson(service.serverPackUrl()) + "\","
                    + "\"message\":\"uploaded; players still need /emoji sync to receive the updated resource pack\""
                    + "}");
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "QQ bot emoji upload failed", exception);
            sendJson(exchange, 400, "{\"success\":false,\"error\":\"upload_failed\",\"message\":\"" + escapeJson(exception.getMessage()) + "\"}");
        }
    }

    private void handlePack(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String name = path.substring("/packs".length()).replaceFirst("^/", "");
        if (!name.endsWith(".zip")) {
            sendText(exchange, 404, "Not found.");
            return;
        }

        Path pack;
        String downloadName;
        if (name.equalsIgnoreCase("server.zip") || name.equalsIgnoreCase("EmojiChatServerPack.zip")) {
            pack = service.generatedServerPack();
            downloadName = "NTCEmojiChatFolia-server.zip";
        } else {
            String uuidText = name.substring(0, name.length() - 4);
            try {
                UUID.fromString(uuidText);
            } catch (IllegalArgumentException exception) {
                sendText(exchange, 400, "Bad pack id.");
                return;
            }
            // Backward compatibility: old per-player pack URLs now serve the full server emoji pack.
            pack = service.generatedServerPack();
            downloadName = "NTCEmojiChatFolia-server.zip";
        }

        if (!Files.isRegularFile(pack)) {
            service.rebuildServerPackSilently();
        }
        if (!Files.isRegularFile(pack)) {
            sendText(exchange, 404, "Pack not generated yet. Run /emoji sync in game.");
            return;
        }

        boolean queueEnabled = plugin.getConfig().getBoolean("resource-pack.download-queue.enabled", true);
        Semaphore semaphore = packDownloadSemaphore;
        boolean acquired = false;
        boolean countedWaiter = false;
        try {
            if (queueEnabled) {
                int maxWaitingDownloads = Math.max(0, plugin.getConfig().getInt("resource-pack.download-queue.max-waiting-downloads", 80));
                int currentWaiting = packDownloadWaiters.incrementAndGet();
                countedWaiter = true;
                if (maxWaitingDownloads > 0 && currentWaiting > maxWaitingDownloads) {
                    sendText(exchange, 503, "Resource pack download queue is full. Please retry later.");
                    return;
                }

                int waitSeconds = Math.max(1, plugin.getConfig().getInt("resource-pack.download-queue.wait-timeout-seconds", 900));
                acquired = semaphore.tryAcquire(waitSeconds, TimeUnit.SECONDS);
                if (!acquired) {
                    sendText(exchange, 503, "Resource pack download queue is busy. Please retry later.");
                    return;
                }
            }
            streamPack(exchange, pack, downloadName);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            sendText(exchange, 503, "Resource pack download was interrupted. Please retry later.");
        } finally {
            if (acquired) {
                semaphore.release();
            }
            if (countedWaiter) {
                packDownloadWaiters.decrementAndGet();
            }
        }
    }

    private void streamPack(HttpExchange exchange, Path pack, String downloadName) throws IOException {
        long size = Files.size(pack);
        int bufferSize = Math.max(8, plugin.getConfig().getInt("resource-pack.download-queue.buffer-size-kb", 64)) * 1024;
        Headers responseHeaders = exchange.getResponseHeaders();
        responseHeaders.set("Content-Type", "application/zip");
        responseHeaders.set("Content-Disposition", "attachment; filename=" + downloadName);
        responseHeaders.set("Cache-Control", "no-store, max-age=0");
        exchange.sendResponseHeaders(200, size);
        long maxBytesPerSecond = Math.max(0L, plugin.getConfig().getLong("resource-pack.download-queue.max-bytes-per-second", 0L));
        long startedAtNanos = System.nanoTime();
        long sentBytes = 0L;
        byte[] buffer = new byte[bufferSize];
        try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(pack));
             OutputStream out = exchange.getResponseBody()) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                sentBytes += read;
                throttleDownloadIfNeeded(sentBytes, maxBytesPerSecond, startedAtNanos);
            }
        }
    }

    private void throttleDownloadIfNeeded(long sentBytes, long maxBytesPerSecond, long startedAtNanos) throws IOException {
        if (maxBytesPerSecond <= 0L) {
            return;
        }
        long expectedNanos = (sentBytes * 1_000_000_000L) / maxBytesPerSecond;
        long elapsedNanos = System.nanoTime() - startedAtNanos;
        long sleepNanos = expectedNanos - elapsedNanos;
        if (sleepNanos <= 0L) {
            return;
        }
        try {
            long sleepMillis = sleepNanos / 1_000_000L;
            int extraNanos = (int) (sleepNanos % 1_000_000L);
            Thread.sleep(sleepMillis, extraNanos);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Resource pack download was interrupted.", exception);
        }
    }

    private String uploadForm(UploadToken token) {
        int maxKb = plugin.getConfig().getInt("upload-server.max-file-size-kb", 1024);
        long maxBytes = maxKb * 1024L;
        String serverName = serverName();
        return "<!doctype html>\n" +
                "<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<title>" + escapeHtml(serverName) + " 表情上传</title>" +
                "<style>body{font-family:system-ui,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;max-width:680px;margin:32px auto;padding:0 16px;line-height:1.6;background:#f7f7fb;color:#222}.card{background:#fff;border-radius:18px;padding:24px;box-shadow:0 10px 28px rgba(0,0,0,.08)}.muted{color:#666}.limit{background:#fff7e6;border:1px solid #ffd58a;border-radius:12px;padding:10px 12px}button{border:0;border-radius:12px;padding:10px 18px;background:#6d5dfc;color:white;font-weight:700;cursor:pointer}button:disabled{opacity:.55;cursor:not-allowed}input{display:block;margin:12px 0}.error{color:#c62828;font-weight:700}</style>" +
                "</head><body><main class=\"card\">" +
                "<h1>" + escapeHtml(serverName) + " 表情上传</h1>" +
                "<h2>上传表情：" + escapeHtml(token.emojiName()) + "</h2>" +
                "<p class=\"muted\">支持 PNG / JPG / JPEG / GIF。GIF 会取第一帧，上传后会统一缩放为服务器表情尺寸。</p>" +
                "<p class=\"limit\">图片大小限制：最大 <b>" + maxKb + " KB</b>；最大分辨率 <b>" + plugin.getConfig().getInt("upload-server.max-image-width", 2048) + "x" + plugin.getConfig().getInt("upload-server.max-image-height", 2048) + "</b>。</p>" +
                "<form id=\"uploadForm\" method=\"post\" enctype=\"multipart/form-data\">" +
                "<input id=\"fileInput\" type=\"file\" name=\"file\" accept=\"image/png,image/jpeg,image/gif\" required>" +
                "<p id=\"message\" class=\"error\"></p>" +
                "<p><button id=\"submitButton\" type=\"submit\">上传到 " + escapeHtml(serverName) + "</button></p>" +
                "</form>" +
                "<p class=\"muted\">上传完成后不会主动向玩家发送资源包；请回到游戏内执行 <code>/emoji sync</code> 手动同步。</p>" +
                "</main><script>const maxBytes=" + maxBytes + ";const maxKb=" + maxKb + ";const input=document.getElementById('fileInput');const form=document.getElementById('uploadForm');const msg=document.getElementById('message');const btn=document.getElementById('submitButton');function check(){msg.textContent='';btn.disabled=false;const file=input.files&&input.files[0];if(!file)return true;if(file.size>maxBytes){msg.textContent='图片过大：当前约 '+Math.ceil(file.size/1024)+' KB，最大允许 '+maxKb+' KB。';btn.disabled=true;return false;}return true;}input.addEventListener('change',check);form.addEventListener('submit',e=>{if(!check())e.preventDefault();});</script>" +
                "</body></html>";
    }

    private String serverName() {
        String name = plugin.getConfig().getString("settings.server-name", "NeoTcc");
        return name == null || name.isBlank() ? "NeoTcc" : name;
    }

    private byte[] readLimited(HttpExchange exchange, long limit) throws IOException {
        try (var in = exchange.getRequestBody(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new IOException("上传内容过大，已超过服务器限制。");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private MultipartFile extractMultipartFile(Headers headers, byte[] body) throws IOException {
        String contentType = headers.getFirst("Content-Type");
        if (contentType == null || !contentType.contains("multipart/form-data")) {
            throw new IOException("Expected multipart/form-data upload.");
        }
        String boundary = null;
        for (String part : contentType.split(";")) {
            part = part.trim();
            if (part.startsWith("boundary=")) {
                boundary = part.substring("boundary=".length());
                if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
            }
        }
        if (boundary == null || boundary.isBlank()) {
            throw new IOException("Missing multipart boundary.");
        }

        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int pos = indexOf(body, boundaryBytes, 0);
        while (pos >= 0) {
            int partStart = pos + boundaryBytes.length;
            if (partStart + 2 < body.length && body[partStart] == '-' && body[partStart + 1] == '-') {
                break;
            }
            if (partStart + 2 <= body.length && body[partStart] == '\r' && body[partStart + 1] == '\n') {
                partStart += 2;
            }
            int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), partStart);
            if (headerEnd < 0) {
                break;
            }
            String headerText = new String(body, partStart, headerEnd - partStart, StandardCharsets.ISO_8859_1);
            int dataStart = headerEnd + 4;
            int nextBoundary = indexOf(body, boundaryBytes, dataStart);
            if (nextBoundary < 0) {
                break;
            }
            int dataEnd = nextBoundary;
            if (dataEnd >= 2 && body[dataEnd - 2] == '\r' && body[dataEnd - 1] == '\n') {
                dataEnd -= 2;
            }
            if (headerText.contains("name=\"file\"")) {
                String filename = "upload";
                int fileIndex = headerText.indexOf("filename=\"");
                if (fileIndex >= 0) {
                    int start = fileIndex + "filename=\"".length();
                    int end = headerText.indexOf('"', start);
                    if (end > start) {
                        filename = headerText.substring(start, end);
                    }
                }
                return new MultipartFile(filename, Arrays.copyOfRange(body, dataStart, dataEnd));
            }
            pos = nextBoundary;
        }
        throw new IOException("No file field found.");
    }

    private int indexOf(byte[] source, byte[] target, int fromIndex) {
        outer:
        for (int i = Math.max(0, fromIndex); i <= source.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (source[i + j] != target[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private String qqBotApiPath() {
        String path = plugin.getConfig().getString("qq-bot-api.path", "/api/qq/emoji/upload");
        if (path == null || path.isBlank()) {
            return "/api/qq/emoji/upload";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private String providedApiToken(HttpExchange exchange) {
        String headerToken = exchange.getRequestHeaders().getFirst("X-Emoji-Api-Token");
        if (headerToken != null && !headerToken.isBlank()) {
            return headerToken.trim();
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return authorization.substring("Bearer ".length()).trim();
        }
        return queryParameters(exchange.getRequestURI().getRawQuery()).getOrDefault("token", "");
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            actual = "";
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        int diff = expectedBytes.length ^ actualBytes.length;
        int max = Math.max(expectedBytes.length, actualBytes.length);
        for (int index = 0; index < max; index++) {
            byte expectedByte = index < expectedBytes.length ? expectedBytes[index] : 0;
            byte actualByte = index < actualBytes.length ? actualBytes[index] : 0;
            diff |= expectedByte ^ actualByte;
        }
        return diff == 0;
    }

    private Map<String, String> queryParameters(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int equalsIndex = pair.indexOf('=');
            String key = equalsIndex >= 0 ? pair.substring(0, equalsIndex) : pair;
            String value = equalsIndex >= 0 ? pair.substring(equalsIndex + 1) : "";
            params.put(urlDecode(key), urlDecode(value));
        }
        return params;
    }

    private String urlDecode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private UUID parseUuidOrDefault(String raw, String fallback) {
        String value = firstNonBlank(raw, fallback, "00000000-0000-0000-0000-000000000000");
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return new UUID(0L, 0L);
        }
    }

    private String qqSourceDescription(Map<String, String> params) {
        String groupId = firstNonBlank(params.get("groupId"), params.get("qqGroupId"));
        String userId = firstNonBlank(params.get("userId"), params.get("qqUserId"));
        StringBuilder description = new StringBuilder("QQ群机器人上传至 ").append(serverName());
        if (groupId != null) {
            description.append("，QQ群：").append(groupId);
        }
        if (userId != null) {
            description.append("，QQ用户：").append(userId);
        }
        return description.toString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private void sendHtml(HttpExchange exchange, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sendText(HttpExchange exchange, int code, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length() + 16);
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            switch (ch) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        return builder.toString();
    }

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private record MultipartFile(String filename, byte[] bytes) {
    }
}
