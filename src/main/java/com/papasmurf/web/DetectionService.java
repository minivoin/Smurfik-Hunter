package com.papasmurf.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.papasmurf.SteamGraphBuilder;
import com.papasmurf.detect.DetectionResult;
import com.papasmurf.detect.SamePersonDetector;

/**
 * Запускает детектор и кеширует результаты.
 *
 * Один анализ занимает 1–2 минуты: детектор докачивает профили друзей, а
 * {@code GetSteamAPI} специально тормозит запросы, чтобы не получить 429.
 * Для Discord-бота это критично — без кеша каждый пользователь, спросивший
 * один и тот же SteamID, запускал бы полный проход заново.
 *
 * Параллельные запросы на один и тот же ключ схлопываются: пока первый считает,
 * остальные ждут его результата, а не дублируют работу.
 */
public class DetectionService {

    /** Сколько живёт результат. Друзья меняются нечасто, 30 минут — компромисс. */
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

    private static final int CACHE_CLEANUP_THRESHOLD = 200;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    /** Результат одного вызова: данные + сколько заняло + признак кеша. */
    public record Run(DetectionResult result, long durationMs, boolean cached) {
    }

    private record Entry(DetectionResult result, long durationMs, long storedAt) {
        boolean expired(long now) {
            return now - storedAt > CACHE_TTL_MS;
        }
    }

    public Run detect(String steamId, int depth) {
        String key = steamId + ":" + depth;

        Run cached = freshRun(key);
        if (cached != null) {
            return cached;
        }

        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            // Пока ждали блокировку, другой поток мог уже всё посчитать.
            Run second = freshRun(key);
            if (second != null) {
                return second;
            }

            long startedAt = System.currentTimeMillis();
            SteamGraphBuilder builder = new SteamGraphBuilder();
            DetectionResult result = new SamePersonDetector(builder).detect(steamId, depth);
            long durationMs = System.currentTimeMillis() - startedAt;

            // Ошибки не кешируем: список друзей могли открыть через минуту.
            if (result.message == null) {
                cache.put(key, new Entry(result, durationMs, System.currentTimeMillis()));
                cleanupIfNeeded();
            }
            return new Run(result, durationMs, false);
        }
    }

    private Run freshRun(String key) {
        Entry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (entry.expired(now)) {
            cache.remove(key);
            return null;
        }
        return new Run(entry.result(), entry.durationMs(), true);
    }

    private void cleanupIfNeeded() {
        if (cache.size() <= CACHE_CLEANUP_THRESHOLD) {
            return;
        }
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(entry -> entry.getValue().expired(now));
    }
}
