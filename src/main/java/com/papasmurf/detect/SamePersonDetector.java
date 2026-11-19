package com.papasmurf.detect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.papasmurf.SteamGraphBuilder;
import com.papasmurf.api.dto.Friend;
import com.papasmurf.api.dto.Players;

/**
 * Ищет аккаунты, которые вероятнее всего принадлежат одному человеку.
 *
 * Идея: человек невидим в графе друзей, но видны его действия. Регистрируя
 * второй аккаунт, он добавляет те же контакты, в то же время и в том же порядке.
 * Поэтому сравниваются не "соседи по графу", а сами действия аккаунтов.
 *
 * Работает поверх уже собранных данных {@link SteamGraphBuilder}, дополнительных
 * запросов к Steam не делает.
 */
public class SamePersonDetector {

    /** Смещение между SteamID64 и номером аккаунта: accountId = steamid64 - BASE. */
    private static final long STEAMID64_BASE = 76561197960265728L;

    /** Постоянная времени для "синхронного добавления": 6 часов. */
    private static final double SYNC_TAU_SECONDS = 6 * 60 * 60;

    /** Постоянная времени для близости дат регистрации: 90 дней. */
    private static final double CREATION_TAU_SECONDS = 90.0 * 24 * 3600;

    /** Масштаб разницы номеров аккаунтов: 10 000 номеров ≈ "один день". */
    private static final double ACCOUNT_NUMBER_SCALE = 10_000.0;

    /**
     * Сглаживание по объёму доказательств: пока общих друзей мало, сигналу
     * нельзя доверять на 100%. Формула x/(x+K) даёт 0 при нуле и стремится к 1.
     * Считается в ШТУКАХ общих друзей, а не в сумме весов: иначе порог
     * "начинает доверять" только к десятку совпадений.
     */
    private static final double SUPPORT_FRIENDS = 2.0;

    /** Порог, при котором аккаунт добавляется в группу. */
    private static final double ADD_THRESHOLD = 0.40;

    /** Порог, при котором аккаунт вообще попадает в отчёт (ниже — уже мусор). */
    private static final double DISPLAY_THRESHOLD = 0.15;

    /** Сколько лучших кандидатов показывать в отчёте (не меньше максимального размера группы). */
    private static final int MAX_REPORTED = 8;

    /**
     * Окно "залпового" добавления друзей: 48 часов.
     * У альта круг друзей почти всегда набирается за один-два вечера,
     * у обычного человека — годами.
     */
    private static final long BURST_WINDOW_SECONDS = 48 * 60 * 60;

    /** Меньше этого числа друзей статистика "залпа" недостоверна. */
    private static final int MIN_BURST_FRIENDS = 3;

    /** Узлы популярнее этого порога считаем хабами и не предлагаем в кандидаты. */
    private static final int HUB_DEGREE = 500;

    /**
     * Сколько друзей друзей докачивать при расширении пула кандидатов.
     * Каждый стоит 2 запроса к Steam, поэтому бюджет жёсткий.
     */
    private static final int EXPAND_BUDGET = 25;

    /**
     * Друзья, которые есть у большего числа собранных аккаунтов, для поиска
     * пар бесполезны (они общие для всех) и только раздувают перебор.
     */
    private static final int MAX_PAIR_POPULARITY = 60;

    /** Больше этого числа аккаунтов в одну группу не объединяем. */
    private static final int MAX_GROUP_SIZE = 8;

    /** Дефолтный аватар Steam — сам по себе слабый признак, поэтому вес меньше. */
    private static final String DEFAULT_AVATAR =
            "https://avatars.steamstatic.com/fef49e7fa7e1997310d705b2a6158ff8dc1cdfeb.jpg";

    private final SteamGraphBuilder builder;
    private final Weights weights;

    /** Кэш "залповости" на один прогон: считается один раз на аккаунт, не на пару. */
    private final Map<String, Double> burstCache = new HashMap<>();

    public SamePersonDetector(SteamGraphBuilder builder) {
        this(builder, Weights.defaults());
    }

    public SamePersonDetector(SteamGraphBuilder builder, Weights weights) {
        this.builder = builder;
        this.weights = weights;
    }

    /** Поиск с глубиной сбора по умолчанию (2 — цель + её друзья и их списки). */
    public DetectionResult detect(String seedSteamId) {
        return detect(seedSteamId, 2);
    }

    // ------------------------------------------------------------------
    // Основной конвейер
    // ------------------------------------------------------------------

    public DetectionResult detect(String seedSteamId, int depth) {
        // Шаг 1. Данные. Если цель ещё не собрана — собираем её окружение.
        if (!builder.playerFriednList.containsKey(seedSteamId)) {
            builder.addSteamProfile(seedSteamId, 0, depth);
        }

        Map<String, Map<String, Long>> friendIndex = buildFriendIndex();

        List<String> diagnostics = new ArrayList<>();
        diagnostics.add("аккаунтов собрано: " + builder.playerFriednList.size());
        diagnostics.add("из них со списком друзей: " + friendIndex.size());

        if (!friendIndex.containsKey(seedSteamId) || friendIndex.get(seedSteamId).isEmpty()) {
            return new DetectionResult(seedSteamId, nameOf(seedSteamId),
                    Collections.emptyList(), List.of(seedSteamId), 0,
                    "Не удалось получить друзей цели: профиль закрыт, список друзей скрыт "
                            + "или Steam вернул ошибку.",
                    diagnostics, 0);
        }

        // Шаг 2. Расширение пула: друзья друзей, которых знает больше всего
        //         знакомых цели. Именно там обычно и живёт второй аккаунт —
        //         альт дружит с реальными друзьями человека, но не с ним самим.
        int expanded = expandCandidates(seedSteamId, friendIndex);
        if (expanded > 0) {
            friendIndex = buildFriendIndex();
        }

        // Шаг 3. Признаки для всех пар, у которых есть хотя бы один общий друг.
        Map<String, Map<String, PairFeatures>> adjacency = buildAdjacency(friendIndex);

        // Шаг 4. Кандидаты — те, кто образовал пару с целью.
        Map<String, PairFeatures> seedPairs = adjacency.getOrDefault(seedSteamId, Map.of());

        diagnostics.add("друзей у цели: " + friendIndex.get(seedSteamId).size());
        diagnostics.add("докачано кандидатов: " + expanded);
        diagnostics.add("пар посчитано всего: " + countPairs(adjacency));
        diagnostics.add("пар с участием цели: " + seedPairs.size());
        diagnostics.add("из них отсеяно как хабы: " + seedPairs.keySet().stream()
                .filter(this::isHub).count());

        List<AccountMatch> matches = new ArrayList<>();
        Map<String, Double> scoreToSeed = new HashMap<>();
        burstCache.clear();

        for (Map.Entry<String, PairFeatures> entry : seedPairs.entrySet()) {
            String candidate = entry.getKey();
            if (isHub(candidate)) {
                continue; // публичные персоны и боты дают только шум
            }
            double score = entry.getValue().total(weights);
            scoreToSeed.put(candidate, score);
            if (score >= DISPLAY_THRESHOLD) {
                matches.add(new AccountMatch(candidate, nameOf(candidate), score, false,
                        entry.getValue(),
                        reasonsFor(entry.getValue(), burstScore(candidate, friendIndex))));
            }
        }

        // Лучший скор среди всех кандидатов — сразу видно, далеко ли до порога.
        double bestScore = 0;
        String bestId = null;
        for (Map.Entry<String, Double> entry : scoreToSeed.entrySet()) {
            if (entry.getValue() > bestScore) {
                bestScore = entry.getValue();
                bestId = entry.getKey();
            }
        }
        diagnostics.add(String.format(Locale.ROOT, "лучший скор: %.2f%s",
                bestScore, bestId == null ? "" : " (" + nameOf(bestId) + ")"));
        diagnostics.add(String.format(Locale.ROOT, "порог показа: %.2f, порог группы: %.2f",
                DISPLAY_THRESHOLD, ADD_THRESHOLD));

        // Разбивка топ-3: сразу видно, какой сигнал тянет скор вниз.
        List<Map.Entry<String, PairFeatures>> top = new ArrayList<>(seedPairs.entrySet());
        top.removeIf(entry -> isHub(entry.getKey()));
        top.sort(Comparator.comparingDouble(
                (Map.Entry<String, PairFeatures> e) -> e.getValue().total(weights)).reversed());
        diagnostics.add("топ-3 кандидата:");
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            String id = top.get(i).getKey();
            PairFeatures f = top.get(i).getValue();
            diagnostics.add(String.format(Locale.ROOT,
                    "  %s: %.2f [общих %d, shared %.2f, coadd %.2f, регистрация %.2f, "
                            + "дружба %.0f, профиль %.2f, залп %.2f]",
                    nameOf(id), scoreToSeed.getOrDefault(id, 0.0), f.commonFriends,
                    f.sharedFriends, f.coAdd, f.creation, f.directFriend, f.profile,
                    f.burst));
        }

        // Шаг 4. Группа: наращиваем от цели, добавляя только тех, кто похож
        //         на ВСЕХ текущих участников. Так мы не тянем цепочки
        //         "A похож на B, B на C, значит A и C родственники".
        List<String> group = growGroup(seedSteamId, adjacency, scoreToSeed);

        double confidence = groupConfidence(group, adjacency, scoreToSeed);
        Set<String> groupIds = new HashSet<>(group);

        // Шаг 5. Помечаем, кто вошёл в группу, и сортируем по скору.
        List<AccountMatch> result = new ArrayList<>(matches.size());
        for (AccountMatch match : matches) {
            boolean inGroup = groupIds.contains(match.steamId);
            AccountMatch marked = match;
            // Кандидат прошёл порог против цели, но не подтвердился остальными
            // участниками группы. Без пояснения это выглядит как ошибка:
            // "скор выше порога, а в группе его нет".
            if (!inGroup && match.score >= ADD_THRESHOLD && group.size() > 1) {
                marked = marked.withReason(
                        "не в группе: похож на цель, но не похож на остальных её участников");
            }
            result.add(marked.withGroupFlag(inGroup));
        }
        result.sort(Comparator.comparingDouble((AccountMatch m) -> m.score).reversed());
        if (result.size() > MAX_REPORTED) {
            result = new ArrayList<>(result.subList(0, MAX_REPORTED));
        }

        return new DetectionResult(seedSteamId, nameOf(seedSteamId), result, group, confidence, null,
                diagnostics, scoreToSeed.size());
    }

    private int countPairs(Map<String, Map<String, PairFeatures>> adjacency) {
        int sum = 0;
        for (Map<String, PairFeatures> neighbours : adjacency.values()) {
            sum += neighbours.size();
        }
        return sum / 2;
    }

    // ------------------------------------------------------------------
    // Подготовка данных
    // ------------------------------------------------------------------

    /** account -> (friendId -> когда друг был добавлен). */
    private Map<String, Map<String, Long>> buildFriendIndex() {
        Map<String, Map<String, Long>> index = new HashMap<>();
        for (Map.Entry<String, List<Friend>> entry : builder.playerFriednList.entrySet()) {
            List<Friend> friends = entry.getValue();
            if (friends == null) {
                continue;
            }
            Map<String, Long> map = new HashMap<>(friends.size() * 2);
            for (Friend friend : friends) {
                if (friend == null || friend.steamid == null) {
                    continue;
                }
                map.put(friend.steamid, friend.friend_since);
            }
            index.put(entry.getKey(), map);
        }
        return index;
    }

    /**
     * Докачивает списки друзей у друзей друзей. Приоритет — у кого больше
     * знакомых из круга цели (это бесплатно считается по уже собранным данным).
     *
     * @return сколько аккаунтов реально попытались докачать
     */
    private int expandCandidates(String seed, Map<String, Map<String, Long>> friendIndex) {
        Map<String, Long> seedFriends = friendIndex.get(seed);
        if (seedFriends == null || EXPAND_BUDGET <= 0) {
            return 0;
        }

        // candidate -> у скольких друзей цели он в друзьях
        Map<String, Integer> incoming = new HashMap<>();
        for (String friendId : seedFriends.keySet()) {
            Map<String, Long> list = friendIndex.get(friendId);
            if (list == null) {
                continue;
            }
            for (String candidate : list.keySet()) {
                incoming.merge(candidate, 1, Integer::sum);
            }
        }

        // Убираем саму цель и всех, чьи данные уже есть.
        incoming.remove(seed);
        for (String known : new ArrayList<>(friendIndex.keySet())) {
            incoming.remove(known);
        }
        incoming.entrySet().removeIf(entry -> isHub(entry.getKey()));

        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(incoming.entrySet());
        ranked.sort(Comparator.comparingInt(
                (Map.Entry<String, Integer> e) -> e.getValue()).reversed());

        int fetched = 0;
        for (Map.Entry<String, Integer> entry : ranked) {
            if (fetched >= EXPAND_BUDGET) {
                break;
            }
            // depth=2, maxDepth=3: берём списки друзей кандидата, но дальше не лезем.
            builder.addSteamProfile(entry.getKey(), 2, 3);
            fetched++;
        }
        return fetched;
    }

    /**
     * Строит матрицу похожести. Пары берутся не "все со всеми" (O(n^2)), а из
     * инвертированного индекса "друг -> кто его имеет": если у друга k владельцев,
     * он порождает C(k,2) пар. Это те пары, у которых реально есть что сравнивать.
     */
    private Map<String, Map<String, PairFeatures>> buildAdjacency(
            Map<String, Map<String, Long>> friendIndex) {

        // Инвертированный индекс: друг -> список аккаунтов, у которых он в друзьях.
        Map<String, List<String>> owners = new HashMap<>();
        for (Map.Entry<String, Map<String, Long>> entry : friendIndex.entrySet()) {
            for (String friendId : entry.getValue().keySet()) {
                owners.computeIfAbsent(friendId, k -> new ArrayList<>()).add(entry.getKey());
            }
        }

        Map<String, Map<String, PairFeatures>> adjacency = new HashMap<>();

        for (Map.Entry<String, List<String>> entry : owners.entrySet()) {
            List<String> accounts = entry.getValue();
            // Слишком "популярный" друг не различает аккаунты — пропускаем.
            if (accounts.size() > MAX_PAIR_POPULARITY || accounts.size() < 2) {
                continue;
            }
            for (int i = 0; i < accounts.size(); i++) {
                for (int j = i + 1; j < accounts.size(); j++) {
                    String a = accounts.get(i);
                    String b = accounts.get(j);
                    if (adjacency.getOrDefault(a, Map.of()).containsKey(b)) {
                        continue; // пара уже посчитана по другому общему другу
                    }
                    PairFeatures features = features(a, b, friendIndex);
                    adjacency.computeIfAbsent(a, k -> new HashMap<>()).put(b, features);
                    adjacency.computeIfAbsent(b, k -> new HashMap<>()).put(a, features);
                }
            }
        }
        return adjacency;
    }

    // ------------------------------------------------------------------
    // Признаки пары
    // ------------------------------------------------------------------

    private PairFeatures features(String a, String b,
                                  Map<String, Map<String, Long>> friendIndex) {

        Map<String, Long> friendsA = friendIndex.getOrDefault(a, Map.of());
        Map<String, Long> friendsB = friendIndex.getOrDefault(b, Map.of());

        int common = 0;
        int synced = 0;
        int datedCommon = 0;
        double syncSum = 0; // сумма exp(-delta/tau) по общим друзьям

        for (Map.Entry<String, Long> entry : friendsA.entrySet()) {
            String friendId = entry.getKey();

            // Друг не может быть одним из сравниваемых аккаунтов: иначе
            // взаимная дружба дважды засчитывается как "общий друг".
            if (friendId.equals(a) || friendId.equals(b)) {
                continue;
            }
            Long sinceB = friendsB.get(friendId);
            if (sinceB == null) {
                continue;
            }

            common++;

            long sinceA = entry.getValue();
            if (sinceA > 0 && sinceB > 0) {
                datedCommon++;
                long delta = Math.abs(sinceA - sinceB);
                syncSum += Math.exp(-delta / SYNC_TAU_SECONDS);
                if (delta <= 3600) {
                    synced++;
                }
            }
        }

        // Чем меньше доказательств, тем ниже доверие к сигналу.
        // Считаем в штуках общих друзей: 1 совпадение — это случайность,
        // 8 совпадений — уже закономерность.
        double support = common > 0 ? common / (common + SUPPORT_FRIENDS) : 0;

        // Коэффициент перекрытия, а не косинус: у смурфа обычно мало друзей,
        // у основы — много. Косинус в такой паре искусственно занижает скор,
        // потому что делит на весь длинный список. Перекрытие спрашивает
        // правильный вопрос: какая доля МЕНЬШЕГО списка совпала.
        int smaller = Math.min(friendsA.size(), friendsB.size());
        double overlap = smaller > 0 ? (double) common / smaller : 0;
        double shared = overlap * support;

        double coAdd = datedCommon > 0 ? (syncSum / datedCommon) * support : 0;

        double creation = creationScore(a, b);

        boolean direct = builder.graph.containsVertex(a)
                && builder.graph.containsVertex(b)
                && builder.graph.containsEdge(a, b);

        Players playerA = builder.playerData.get(a);
        Players playerB = builder.playerData.get(b);

        boolean avatarMatches = playerA != null && playerB != null
                && playerA.avatar != null && playerA.avatar.equals(playerB.avatar);

        double nameSimilarity = (playerA != null && playerB != null)
                ? nameSimilarity(playerA.personaname, playerB.personaname)
                : 0;

        // Дефолтный аватар совпадает у миллионов — это слабый признак.
        double avatarScore = avatarMatches
                ? (isDefaultAvatar(playerA.avatar) ? 0.6 : 1.0)
                : 0;

        double profile = 0.6 * avatarScore + 0.4 * nameSimilarity;

        // "Залп" считаем на пару как максимум: достаточно, чтобы хотя бы один
        // из аккаунтов собирал круг друзей одним заходом. У старой основы
        // круг набирался годами, и это не должно обнулять признак.
        double burst = Math.max(burstScore(a, friendIndex), burstScore(b, friendIndex));

        return new PairFeatures(shared, coAdd, creation, direct ? 1.0 : 0.0, profile,
                common, synced, avatarMatches, nameSimilarity, burst);
    }

    /**
     * "Залповость" круга друзей: какая доля друзей была добавлена в течение
     * одного 48-часового окна. У второго аккаунта круг обычно набирается за
     * один вечер (0.7-1.0), у обычного человека — годами (0.05-0.2).
     * Считается по максимуму в скользящем окне.
     */
    private double burstScore(String account, Map<String, Map<String, Long>> friendIndex) {
        Double cached = burstCache.get(account);
        if (cached != null) {
            return cached;
        }

        Map<String, Long> friends = friendIndex.get(account);
        if (friends == null || friends.size() < MIN_BURST_FRIENDS) {
            burstCache.put(account, 0.0);
            return 0.0;
        }

        List<Long> times = new ArrayList<>();
        for (long since : friends.values()) {
            if (since > 0) {
                times.add(since);
            }
        }
        if (times.size() < MIN_BURST_FRIENDS) {
            burstCache.put(account, 0.0);
            return 0.0;
        }
        Collections.sort(times);

        int best = 0;
        int left = 0;
        for (int right = 0; right < times.size(); right++) {
            while (times.get(right) - times.get(left) > BURST_WINDOW_SECONDS) {
                left++;
            }
            best = Math.max(best, right - left + 1);
        }

        double value = (double) best / times.size();
        burstCache.put(account, value);
        return value;
    }

    /**
     * Близость "момента появления" аккаунта. Два независимых признака:
     * номер аккаунта (SteamID выдавались последовательно) и дата регистрации.
     */
    private double creationScore(String a, String b) {
        long numberA = accountNumber(a);
        long numberB = accountNumber(b);
        boolean haveNumbers = numberA > 0 && numberB > 0;

        double numberScore = 0;
        if (haveNumbers) {
            double delta = Math.abs(numberA - numberB);
            numberScore = 1.0 / (1.0 + delta / ACCOUNT_NUMBER_SCALE);
        }

        Long createdA = timeCreated(a);
        Long createdB = timeCreated(b);
        boolean haveDates = createdA != null && createdB != null && createdA > 0 && createdB > 0;

        if (haveNumbers && haveDates) {
            double deltaSeconds = Math.abs(createdA - createdB);
            return 0.5 * numberScore + 0.5 * Math.exp(-deltaSeconds / CREATION_TAU_SECONDS);
        }
        if (haveNumbers) {
            return numberScore;
        }
        if (haveDates) {
            return Math.exp(-Math.abs(createdA - createdB) / CREATION_TAU_SECONDS);
        }
        return 0;
    }

    private long accountNumber(String steamId) {
        try {
            return Long.parseLong(steamId) - STEAMID64_BASE;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private Long timeCreated(String steamId) {
        Players player = builder.playerData.get(steamId);
        return player == null ? null : player.timecreated;
    }

    /** Похожесть ников: 1 - расстояние Левенштейна / длина. */
    private double nameSimilarity(String first, String second) {
        if (first == null || second == null) {
            return 0;
        }
        String a = first.trim().toLowerCase(Locale.ROOT);
        String b = second.trim().toLowerCase(Locale.ROOT);
        if (a.length() < 3 || b.length() < 3) {
            return a.equals(b) ? 1.0 : 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        int distance = levenshtein(a, b);
        return 1.0 - (double) distance / Math.max(a.length(), b.length());
    }

    private int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    // ------------------------------------------------------------------
    // Формирование группы
    // ------------------------------------------------------------------

    /**
     * Жадно наращивает группу от цели. Кандидат добавляется, только если он
     * похож на КАЖДОГО текущего участника (скор >= ADD_THRESHOLD). Это защита
     * от транзитивности: одиночный "мост" через общего друга группу не создаст.
     */
    private List<String> growGroup(String seed,
                                   Map<String, Map<String, PairFeatures>> adjacency,
                                   Map<String, Double> scoreToSeed) {

        List<String> group = new ArrayList<>();
        group.add(seed);
        Set<String> used = new HashSet<>();
        used.add(seed);

        while (group.size() < MAX_GROUP_SIZE) {
            String best = null;
            double bestAverage = 0;

            for (Map.Entry<String, Double> entry : scoreToSeed.entrySet()) {
                String candidate = entry.getKey();
                if (used.contains(candidate)) {
                    continue;
                }

                double sum = 0;
                boolean fits = true;
                for (String member : group) {
                    // Для ребра "цель — кандидат" берём уже посчитанный скор
                    // с учётом "залпа", чтобы порог применялся к той же величине,
                    // что видит пользователь в отчёте.
                    double score;
                    if (member.equals(seed)) {
                        score = entry.getValue();
                    } else {
                        PairFeatures features = adjacency
                                .getOrDefault(member, Map.of())
                                .get(candidate);
                        if (features == null) {
                            fits = false; // нет общих данных — не можем подтвердить
                            break;
                        }
                        score = features.total(weights);
                    }
                    if (score < ADD_THRESHOLD) {
                        fits = false;
                        break;
                    }
                    sum += score;
                }
                if (!fits) {
                    continue;
                }

                double average = sum / group.size();
                if (average > bestAverage) {
                    bestAverage = average;
                    best = candidate;
                }
            }

            if (best == null) {
                break;
            }
            group.add(best);
            used.add(best);
        }
        return group;
    }

    /** Уверенность группы = минимальный скор среди её пар ("слабое звено"). */
    private double groupConfidence(List<String> group,
                                   Map<String, Map<String, PairFeatures>> adjacency,
                                   Map<String, Double> scoreToSeed) {
        if (group.size() < 2) {
            return 0;
        }
        String seed = group.get(0);
        double min = 1.0;
        for (int i = 0; i < group.size(); i++) {
            for (int j = i + 1; j < group.size(); j++) {
                String first = group.get(i);
                String second = group.get(j);

                double score;
                if (first.equals(seed) && scoreToSeed.containsKey(second)) {
                    score = scoreToSeed.get(second);
                } else {
                    PairFeatures features = adjacency
                            .getOrDefault(first, Map.of())
                            .get(second);
                    if (features == null) {
                        return 0;
                    }
                    score = features.total(weights);
                }
                min = Math.min(min, score);
            }
        }
        return min;
    }

    // ------------------------------------------------------------------
    // Вспомогательное
    // ------------------------------------------------------------------

    private boolean isHub(String steamId) {
        return builder.graph.containsVertex(steamId)
                && builder.graph.degreeOf(steamId) > HUB_DEGREE;
    }

    private static boolean isDefaultAvatar(String avatar) {
        return DEFAULT_AVATAR.equals(avatar);
    }

    private String nameOf(String steamId) {
        Players player = builder.playerData.get(steamId);
        return (player == null || player.personaname == null) ? steamId : player.personaname;
    }

    /**
     * Причины, по которым пара признана похожей — для отчёта и Discord.
     *
     * @param candidateBurst "залповость" именно кандидата, а не пары: иначе
     *                       свежая цель приписывала бы этот признак всем подряд
     */
    private List<String> reasonsFor(PairFeatures features, double candidateBurst) {
        List<String> reasons = new ArrayList<>();

        if (features.commonFriends > 0) {
            reasons.add(String.format(Locale.ROOT,
                    "%d общих друзей (совпадение %.2f)",
                    features.commonFriends, features.sharedFriends));
        }
        if (features.syncedFriends > 0) {
            reasons.add(String.format(Locale.ROOT,
                    "%d из них добавлены с разницей меньше часа (синхронность %.2f)",
                    features.syncedFriends, features.coAdd));
        }
        if (candidateBurst >= 0.5) {
            reasons.add(String.format(Locale.ROOT,
                    "%.0f%% друзей добавлены одним заходом — характерно для второго аккаунта",
                    candidateBurst * 100));
        }
        if (features.directFriend > 0) {
            reasons.add("аккаунты в друзьях друг у друга");
        }
        if (features.creation >= 0.5) {
            reasons.add(String.format(Locale.ROOT,
                    "похожий момент регистрации (%.2f)", features.creation));
        }
        if (features.avatarMatches) {
            reasons.add("одинаковый аватар");
        }
        if (features.nameSimilarity >= 0.6) {
            reasons.add(String.format(Locale.ROOT,
                    "похожие ники (%.2f)", features.nameSimilarity));
        }
        if (reasons.isEmpty()) {
            reasons.add("совпадений по доступным признакам нет");
        }
        return reasons;
    }
}
