package com.papasmurf.web;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.papasmurf.api.GetSteamAPI;
import com.papasmurf.analyze.ProfileAnalyzer;
import com.papasmurf.analyze.ProfileReport;
import com.papasmurf.detect.DetectionResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * HTTP-API поверх детектора. Нужен, чтобы Python-бот (и любой другой клиент)
 * не запускал Java-код напрямую, а общался с ним по обычному REST.
 *
 * Сервер построен на встроенном {@code com.sun.net.httpserver} — никаких новых
 * зависимостей, запускается одной командой, стартует за секунду. Если позже
 * понадобится Spring, менять придётся только этот файл: контракт JSON уже
 * зафиксирован в {@link DetectResponse}.
 *
 * Ручки:
 * <pre>
 *   GET  /health
 *   GET  /api/v1/profile/{profile}             — первичный анализ
 *   POST /api/v1/profile            {"steamId":"..."}
 *   GET  /api/v1/detect/{steamId}?depth=2      — смежные аккаунты
 *   POST /api/v1/detect           {"steamId":"...","depth":2}
 * </pre>
 */
public class ApiServer {

    private static final String DETECT_PATH = "/api/v1/detect";
    private static final String PROFILE_PATH = "/api/v1/profile";
    private static final long STEAMID64_BASE = 76561197960265728L;

    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private final HttpServer server;
    private final ExecutorService executor;
    private final DetectionService service = new DetectionService();

    public ApiServer(String host, int port) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.executor = Executors.newFixedThreadPool(4);

        server.setExecutor(executor);
        server.createContext("/", this::handleRoot);
        server.createContext("/health", this::handleHealth);
        server.createContext(DETECT_PATH, this::handleDetect);
        server.createContext(PROFILE_PATH, this::handleProfile);
    }

    public void start() {
        server.start();
        System.out.println("Smurfik-Hunter API запущен: " + baseUrl());
        System.out.println("  GET  /health");
        System.out.println("  GET  /api/v1/profile/{steamId}            первичный анализ");
        System.out.println("  POST /api/v1/profile                      {\"steamId\":\"...\"}");
        System.out.println("  GET  /api/v1/detect/{steamId}?depth=2      смежные аккаунты");
        System.out.println("  POST /api/v1/detect                       {\"steamId\":\"...\",\"depth\":2}");
        if (setting("STEAM_API_KEY", null) == null) {
            System.out.println("ВНИМАНИЕ: STEAM_API_KEY не найден ни в окружении, ни в .env");
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    public void stop() {
        server.stop(0);
        executor.shutdown();
    }

    public static void main(String[] args) throws IOException {
        String host = setting("API_HOST", "127.0.0.1");
        int port = intSetting("API_PORT", 8080);
        new ApiServer(host, port).start();
    }

    // ------------------------------------------------------------------
    // Ручки
    // ------------------------------------------------------------------

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, new ErrorResponse("METHOD_NOT_ALLOWED", "Только GET"));
            return;
        }
        String text = "Smurfik-Hunter API\n"
                + "GET  /health\n"
                + "GET  /api/v1/profile/{profile}            первичный анализ\n"
                + "POST /api/v1/profile                      {\"steamId\":\"...\"}\n"
                + "GET  /api/v1/detect/{steamId}?depth=2      смежные аккаунты\n"
                + "POST /api/v1/detect                       {\"steamId\":\"...\",\"depth\":2}\n";
        sendText(exchange, 200, text);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, new ErrorResponse("METHOD_NOT_ALLOWED", "Только GET"));
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("ok", true);
        body.addProperty("status", "ok");
        body.addProperty("steamKeyConfigured", setting("STEAM_API_KEY", null) != null);
        send(exchange, 200, body);
    }

    private void handleDetect(HttpExchange exchange) throws IOException {
        String steamId64;
        int depth;
        try {
            Input input = readInput(exchange, DETECT_PATH);
            steamId64 = requireSteamId(input.profile());
            depth = parseDepth(input.depth());
        } catch (RequestError error) {
            send(exchange, error.status, new ErrorResponse(error.code, error.getMessage()));
            return;
        }

        long startedAt = System.currentTimeMillis();
        DetectionService.Run run;
        try {
            run = service.detect(steamId64, depth);
        } catch (RuntimeException e) {
            System.out.println("[api] ошибка анализа " + steamId64 + ": " + e);
            send(exchange, 500, new ErrorResponse("INTERNAL", "Внутренняя ошибка анализа: " + e));
            log(exchange, steamId64, 500, System.currentTimeMillis() - startedAt);
            return;
        }

        long totalMs = System.currentTimeMillis() - startedAt;
        DetectionResult result = run.result();

        if (result.message != null) {
            send(exchange, 422, new ErrorResponse("NO_DATA", result.message));
            log(exchange, steamId64, 422, totalMs);
            return;
        }

        DetectResponse response = DetectResponse.from(result, run.durationMs(), run.cached());
        send(exchange, 200, response);
        log(exchange, steamId64, 200, totalMs);
    }

    /**
     * Первичный анализ профиля: быстро (1–2 запроса к Steam) и без графа.
     * Отвечает баллом 0..100 и разбивкой по проверкам.
     */
    private void handleProfile(HttpExchange exchange) throws IOException {
        long startedAt = System.currentTimeMillis();
        String steamId64;
        try {
            steamId64 = requireSteamId(readInput(exchange, PROFILE_PATH).profile());
        } catch (RequestError error) {
            send(exchange, error.status, new ErrorResponse(error.code, error.getMessage()));
            return;
        }

        ProfileReport report;
        try {
            report = new ProfileAnalyzer(steam()).analyze(steamId64);
        } catch (RuntimeException e) {
            System.out.println("[api] ошибка первичного анализа " + steamId64 + ": " + e);
            send(exchange, 500, new ErrorResponse("INTERNAL", "Внутренняя ошибка анализа: " + e));
            log(exchange, steamId64, 500, System.currentTimeMillis() - startedAt);
            return;
        }

        long totalMs = System.currentTimeMillis() - startedAt;
        if (!report.ok) {
            send(exchange, 422, new ErrorResponse(report.code, report.message));
            log(exchange, steamId64, 422, totalMs);
            return;
        }

        send(exchange, 200, report);
        log(exchange, steamId64, 200, totalMs);
    }

    // ------------------------------------------------------------------
    // Разбор запроса
    // ------------------------------------------------------------------

    /** Результат разбора запроса: профиль в любом виде и глубина (может быть null). */
    private record Input(String profile, String depth) {
    }

    /**
     * Читает вход из GET-пути/строки запроса или из JSON-тела POST.
     *
     * @param basePath префикс ручки, например {@code /api/v1/detect}
     */
    private static Input readInput(HttpExchange exchange, String basePath) {
        String method = exchange.getRequestMethod();

        if ("GET".equals(method)) {
            String fromPath = pathTail(exchange.getRequestURI().getPath(), basePath);
            String fromQuery = queryParam(exchange.getRequestURI().getRawQuery(), "q");
            String depth = queryParam(exchange.getRequestURI().getRawQuery(), "depth");
            return new Input(fromPath != null ? fromPath : fromQuery, depth);
        }

        if ("POST".equals(method)) {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RequestError(400, "INVALID_JSON", "Не удалось прочитать тело запроса");
            }
            JsonObject json = parseObject(body);
            if (json == null) {
                throw new RequestError(400, "INVALID_JSON",
                        "Тело запроса должно быть JSON вида {\"steamId\":\"...\",\"depth\":2}");
            }
            return new Input(optString(json, "steamId"), optString(json, "depth"));
        }

        throw new RequestError(405, "METHOD_NOT_ALLOWED", "Только GET или POST");
    }

    /** Разбирает профиль из любого вида записи или бросает ошибку для ответа. */
    private static String requireSteamId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new RequestError(400, "INVALID_STEAM_ID",
                    "Не указан профиль. Нужен SteamID64 (17 цифр), ссылка на профиль "
                    + "или имя из steamcommunity.com/id/имя");
        }
        String steamId64;
        try {
            steamId64 = resolveSteamId(raw);
        } catch (RuntimeException e) {
            throw new RequestError(500, "INTERNAL", "Не удалось разобрать профиль: " + e);
        }
        if (steamId64 == null) {
            throw new RequestError(404, "PROFILE_NOT_FOUND",
                    "Профиль не найден: " + raw + ". Проверьте ссылку или SteamID64.");
        }
        return steamId64;
    }

    private static int parseDepth(String depthRaw) {
        if (depthRaw == null || depthRaw.isBlank()) {
            return 2;
        }
        int depth;
        try {
            depth = Integer.parseInt(depthRaw.trim());
        } catch (NumberFormatException e) {
            throw new RequestError(400, "INVALID_DEPTH", "depth должен быть числом 1..3");
        }
        if (depth < 1 || depth > 3) {
            throw new RequestError(400, "INVALID_DEPTH", "depth должен быть 1..3");
        }
        return depth;
    }

    /** Ошибка разбора запроса: сразу несёт HTTP-статус и код для клиента. */
    private static final class RequestError extends RuntimeException {
        final int status;
        final String code;

        RequestError(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    private static String pathTail(String path, String basePath) {
        if (path == null || path.length() <= basePath.length()) {
            return null;
        }
        String tail = path.substring(basePath.length());
        if (tail.startsWith("/")) {
            tail = tail.substring(1);
        }
        int slash = tail.indexOf('/');
        if (slash >= 0) {
            tail = tail.substring(0, slash);
        }
        return tail.isBlank() ? null : tail.trim();
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        for (String part : rawQuery.split("&")) {
            int eq = part.indexOf('=');
            String key = eq < 0 ? part : part.substring(0, eq);
            if (key.equals(name)) {
                return eq < 0 ? "" : part.substring(eq + 1);
            }
        }
        return null;
    }

    private static JsonObject parseObject(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String optString(JsonObject json, String name) {
        if (!json.has(name) || json.get(name).isJsonNull()) {
            return null;
        }
        try {
            return json.get(name).getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Приводит всё, что ввёл пользователь, к SteamID64.
     *
     * Поддерживаются: сам номер, ссылка {@code steamcommunity.com/profiles/...},
     * ссылка {@code steamcommunity.com/id/имя} и просто имя профиля. Последние два
     * варианта требуют запроса к Steam (ResolveVanityURL), поэтому метод не
     * гарантирует мгновенный ответ.
     *
     * @return SteamID64 либо null, если разобрать вход не удалось.
     */
    private static String resolveSteamId(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }

        int host = value.toLowerCase(java.util.Locale.ROOT).indexOf("steamcommunity.com");
        if (host >= 0) {
            String tail = value.substring(host + "steamcommunity.com".length());
            int cut = tail.length();
            for (char stop : new char[] { '?', '#' }) {
                int at = tail.indexOf(stop);
                if (at >= 0 && at < cut) {
                    cut = at;
                }
            }
            tail = trimSlashes(tail.substring(0, cut));
            if (tail.startsWith("profiles/")) {
                return validSteamId(tail.substring("profiles/".length()));
            }
            if (tail.startsWith("id/")) {
                return resolveVanity(tail.substring("id/".length()));
            }
            return null;
        }

        if (value.matches("\\d{17}")) {
            return validSteamId(value);
        }
        // Похоже на имя профиля: буквы, цифры, дефис, подчёркивание.
        if (value.matches("[A-Za-z0-9_-]{2,32}")) {
            return resolveVanity(value);
        }
        return null;
    }

    /** Проверяет, что это правдоподобный SteamID64, а не просто 17 цифр. */
    private static String validSteamId(String candidate) {
        String value = trimSlashes(candidate == null ? "" : candidate.trim());
        if (!value.matches("\\d{17}")) {
            return null;
        }
        try {
            return Long.parseLong(value) > STEAMID64_BASE ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String resolveVanity(String vanity) {
        String name = trimSlashes(vanity);
        if (name.isEmpty()) {
            return null;
        }
        return validSteamId(steam().resolveVanity(name));
    }

    /** Один экземпляр на процесс: общий HTTP-клиент и общий троттлинг Steam. */
    private static GetSteamAPI steamInstance;

    private static synchronized GetSteamAPI steam() {
        if (steamInstance == null) {
            steamInstance = new GetSteamAPI();
        }
        return steamInstance;
    }

    private static String trimSlashes(String value) {
        String result = value;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Ответ и логи
    // ------------------------------------------------------------------

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sendText(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void log(HttpExchange exchange, String steamId, int status, long totalMs) {
        System.out.println(String.format("[api] %s %s -> %d (%d ms)",
                exchange.getRequestMethod(), steamId, status, totalMs));
    }

    // ------------------------------------------------------------------
    // Настройки
    // ------------------------------------------------------------------

    /** Читает переменную окружения, иначе ищет её в .env рядом с процессом. */
    static String setting(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            value = readDotEnv(name);
        }
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private static int intSetting(String name, int fallback) {
        String raw = setting(name, null);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            System.out.println("Некорректное значение " + name + "=" + raw + ", беру " + fallback);
            return fallback;
        }
    }

    private static String readDotEnv(String key) {
        try (BufferedReader reader = new BufferedReader(new FileReader(".env"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("=", 2);
                if (parts.length == 2 && parts[0].trim().equals(key)) {
                    return parts[1].trim();
                }
            }
        } catch (IOException ignored) {
            // .env не обязателен: настройки могут приходить из окружения.
        }
        return null;
    }

    private String baseUrl() {
        InetSocketAddress address = server.getAddress();
        String host = address.getHostString();
        if ("0.0.0.0".equals(host) || "::".equals(host)) {
            host = "127.0.0.1";
        }
        return "http://" + host + ":" + address.getPort();
    }
}
