package com.papasmurf.web;

import java.util.ArrayList;
import java.util.List;

import com.papasmurf.detect.AccountMatch;
import com.papasmurf.detect.DetectionResult;
import com.papasmurf.detect.PairFeatures;

/**
 * JSON-ответ ручки {@code /api/v1/detect}.
 *
 * Структура намеренно плоская и стабильная: её читает Python-бот, поэтому
 * менять имена полей без необходимости нельзя. Кандидаты сразу разделены на
 * две группы — подтверждённые и просто похожие, чтобы клиенту не приходилось
 * повторять логику группировки.
 */
public class DetectResponse {

    public boolean ok = true;

    public String seed;
    public String seedName;

    /** ISO-8601, когда результат был посчитан. */
    public String generatedAt;

    /** Сколько занял сам анализ (без времени на сеть до клиента). */
    public long durationMs;

    /** true, если ответ взят из кеша сервера. */
    public boolean cached;

    /** Сколько аккаунтов реально сравнивалось с целью. */
    public int candidatesChecked;

    /** SteamID цели + подтверждённые аккаунты. */
    public List<String> group = new ArrayList<>();

    /** Минимальный скор внутри группы ("слабое звено"). */
    public double groupConfidence;

    /** Текст проблемы, если анализ не удался (иначе null). */
    public String message;

    public List<Candidate> confirmed = new ArrayList<>();
    public List<Candidate> unconfirmed = new ArrayList<>();
    public List<String> diagnostics = new ArrayList<>();

    /** Один аккаунт-кандидат. */
    public static class Candidate {
        public String steamId;
        public String name;
        public double score;
        public boolean inGroup;
        public List<String> reasons = new ArrayList<>();
        public Signals signals;
    }

    /** Разбивка скора по сигналам — видно, на чём основан вывод. */
    public static class Signals {
        public int commonFriends;
        public double shared;
        public double coAdd;
        public double creation;
        public double directFriend;
        public double profile;
        public double burst;
    }

    public static DetectResponse from(DetectionResult result, long durationMs, boolean cached) {
        DetectResponse response = new DetectResponse();
        response.seed = result.seed;
        response.seedName = result.seedName;
        response.generatedAt = java.time.Instant.now().toString();
        response.durationMs = durationMs;
        response.cached = cached;
        response.candidatesChecked = result.candidatesScored;
        response.groupConfidence = round(result.groupConfidence);
        response.message = result.message;

        if (result.group != null) {
            response.group.addAll(result.group);
        }
        if (result.diagnostics != null) {
            response.diagnostics.addAll(result.diagnostics);
        }
        if (result.matches != null) {
            for (AccountMatch match : result.matches) {
                Candidate candidate = candidate(match);
                if (match.inGroup) {
                    response.confirmed.add(candidate);
                } else {
                    response.unconfirmed.add(candidate);
                }
            }
        }
        return response;
    }

    private static Candidate candidate(AccountMatch match) {
        Candidate candidate = new Candidate();
        candidate.steamId = match.steamId;
        candidate.name = match.personaName;
        candidate.score = round(match.score);
        candidate.inGroup = match.inGroup;
        if (match.reasons != null) {
            candidate.reasons.addAll(match.reasons);
        }
        candidate.signals = signals(match.features);
        return candidate;
    }

    private static Signals signals(PairFeatures features) {
        if (features == null) {
            return null;
        }
        Signals signals = new Signals();
        signals.commonFriends = features.commonFriends;
        signals.shared = round(features.sharedFriends);
        signals.coAdd = round(features.coAdd);
        signals.creation = round(features.creation);
        signals.directFriend = round(features.directFriend);
        signals.profile = round(features.profile);
        signals.burst = round(features.burst);
        return signals;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
