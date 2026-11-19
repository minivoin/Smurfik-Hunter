package com.papasmurf.detect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.papasmurf.SteamGraphBuilder;
import com.papasmurf.api.dto.Friend;
import com.papasmurf.api.dto.Players;

/**
 * Проверка детектора на синтетических данных — без запросов к Steam.
 * Сценарий имитирует реальный случай: у человека есть основной аккаунт,
 * второй аккаунт (альт) и посторонние друзья.
 */
public class SamePersonDetectorTest {

    private static final long STEAMID64_BASE = 76561197960265728L;
    private static final long NOW = 1_700_000_000L;

    private static final String AVATAR = "https://avatars.steamstatic.com/aaa.jpg";
    private static final String OTHER_AVATAR = "https://avatars.steamstatic.com/bbb.jpg";

    private static final long DAY = 24 * 60 * 60;

    @Test
    public void findsAltAccountWithSynchronizedFriends() {
        SteamGraphBuilder builder = new SteamGraphBuilder();

        String seed = id(100_000);
        String alt = id(100_050);          // зарегистрирован почти одновременно с целью
        String randomFriend = id(900_000); // просто друг, общих друзей нет
        String semi = id(700_000);         // 3 общих друга, но добавлены в разное время

        // Общий круг: 10 реальных друзей человека.
        String[] shared = new String[10];
        for (int i = 0; i < shared.length; i++) {
            shared[i] = id(500_000 + i);
            // У этих аккаунтов списки друзей закрыты — так часто бывает в реальности.
            // Пустой список помечает их как "уже известные", чтобы детектор
            // не пытался докачать их из сети во время теста.
            builder.playerFriednList.put(shared[i], new ArrayList<>());
        }

        // Основной аккаунт.
        List<Friend> seedFriends = new ArrayList<>();
        for (int i = 0; i < shared.length; i++) {
            seedFriends.add(friend(shared[i], NOW + i * 100L));
        }
        seedFriends.add(friend(alt, NOW + 5_000));
        put(builder, seed, "smurf_main", AVATAR, NOW - 400 * DAY, seedFriends);

        // Альт: тот же круг, добавлен примерно через 10 минут после основного.
        List<Friend> altFriends = new ArrayList<>();
        for (int i = 0; i < shared.length; i++) {
            altFriends.add(friend(shared[i], NOW + i * 100L + 600));
        }
        altFriends.add(friend(seed, NOW + 5_000));
        put(builder, alt, "smurf_main2", AVATAR, NOW - 395 * DAY, altFriends);

        // Посторонний друг: с целью его связывает только дружба.
        put(builder, randomFriend, "SomeDude", OTHER_AVATAR, NOW - 3000 * DAY,
                List.of(friend(seed, NOW)));

        // Полупохожий: общие друзья есть, но добавлены в разное время.
        List<Friend> semiFriends = new ArrayList<>();
        semiFriends.add(friend(shared[0], NOW - 30 * DAY));
        semiFriends.add(friend(shared[1], NOW - 40 * DAY));
        semiFriends.add(friend(shared[2], NOW - 50 * DAY));
        put(builder, semi, "DifferentPerson", OTHER_AVATAR, NOW - 2000 * DAY, semiFriends);

        // Слой дружбы (для сигнала directFriend).
        builder.graph.addVertex(seed);
        builder.graph.addVertex(alt);
        builder.graph.addVertex(randomFriend);
        builder.graph.addVertex(semi);
        builder.graph.addEdge(seed, alt);
        builder.graph.addEdge(seed, randomFriend);
        builder.graph.addEdge(seed, semi);

        DetectionResult result = new SamePersonDetector(builder).detect(seed);

        assertNull(result.message);
        assertTrue(result.hasMatches());

        // Альт найден и включён в группу.
        AccountMatch altMatch = find(result, alt);
        assertNotNull("альт должен быть среди кандидатов", altMatch);
        assertTrue("альт должен попасть в группу", altMatch.inGroup);
        assertTrue("скор альта должен быть высоким, а не " + altMatch.score,
                altMatch.score > 0.7);

        // Группа = цель + альт.
        assertTrue(result.group.contains(seed));
        assertTrue(result.group.contains(alt));
        assertTrue("уверенность группы должна быть высокой", result.groupConfidence > 0.7);

        assertEquals("в группе должны быть только цель и альт",
                2, result.group.size());

        // Посторонние не попали в группу.
        assertFalse("случайный друг не должен попасть в группу", result.group.contains(semi));

        // Причины должны быть человекочитаемыми и указывать на синхронность.
        boolean mentionsSync = altMatch.reasons.stream()
                .anyMatch(r -> r.contains("меньше часа"));
        assertTrue("в причинах должна быть синхронность добавления: " + altMatch.reasons,
                mentionsSync);
    }

    /**
     * Реальный случай пользователя (числа взяты с настоящих аккаунтов):
     * цель — 30 друзей, второй аккаунт — 9 друзей, из них 8 общие с целью;
     * обычный друг — 28 друзей, из них 11 общие с целью.
     *
     * Второй аккаунт покрывает 89% своего круга, друг — только 39%.
     * Именно это соотношение и отличает альт от просто близкого знакомого.
     */
    @Test
    public void separatesAltFromCloseFriend() {
        SteamGraphBuilder builder = new SteamGraphBuilder();

        String seed = id(100_000);
        String alt = id(100_030);
        String closeFriend = id(600_000);

        // 11 человек, которых знают и цель, и обычный друг.
        String[] sharedWithFriend = new String[11];
        for (int i = 0; i < sharedWithFriend.length; i++) {
            sharedWithFriend[i] = id(500_000 + i);
            builder.playerFriednList.put(sharedWithFriend[i], new ArrayList<>());
        }

        // Свои друзья цели, которых не знает никто из этой тройки.
        List<Friend> seedFriends = new ArrayList<>();
        for (int i = 0; i < sharedWithFriend.length; i++) {
            seedFriends.add(friend(sharedWithFriend[i], NOW - (300 + i * 60L) * DAY));
        }
        for (int i = 0; i < 17; i++) {
            String own = id(810_000 + i);
            builder.playerFriednList.put(own, new ArrayList<>());
            seedFriends.add(friend(own, NOW - (500 + i * 45L) * DAY));
        }
        seedFriends.add(friend(alt, NOW - 200 * DAY));
        seedFriends.add(friend(closeFriend, NOW - 400 * DAY));
        put(builder, seed, "captain", AVATAR, NOW - 800 * DAY, seedFriends);

        // Второй аккаунт: 9 друзей, 8 из них — из круга цели.
        // Круг собран за один вечер сразу после регистрации.
        List<Friend> altFriends = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            altFriends.add(friend(sharedWithFriend[i], NOW - 199 * DAY + i * 300L));
        }
        altFriends.add(friend(seed, NOW - 199 * DAY));
        put(builder, alt, "captain_alt", AVATAR, NOW - 200 * DAY, altFriends);

        // Обычный друг: 28 друзей, из них только 11 пересекаются с целью.
        List<Friend> friendFriends = new ArrayList<>();
        for (int i = 0; i < sharedWithFriend.length; i++) {
            friendFriends.add(friend(sharedWithFriend[i], NOW - (1900 - i * 100L) * DAY));
        }
        for (int i = 0; i < 16; i++) {
            String own = id(820_000 + i);
            builder.playerFriednList.put(own, new ArrayList<>());
            friendFriends.add(friend(own, NOW - (2100 - i * 30L) * DAY));
        }
        friendFriends.add(friend(seed, NOW - 1000 * DAY));
        put(builder, closeFriend, "Vasya", OTHER_AVATAR, NOW - 2000 * DAY, friendFriends);

        builder.graph.addVertex(seed);
        builder.graph.addVertex(alt);
        builder.graph.addVertex(closeFriend);
        builder.graph.addEdge(seed, alt);
        builder.graph.addEdge(seed, closeFriend);

        DetectionResult result = new SamePersonDetector(builder).detect(seed);

        AccountMatch altMatch = find(result, alt);
        assertNotNull("второй аккаунт должен быть среди кандидатов", altMatch);
        assertTrue("второй аккаунт должен попасть в группу, скор " + altMatch.score,
                altMatch.inGroup);

        AccountMatch friendMatch = find(result, closeFriend);
        assertNotNull("друг должен быть в списке кандидатов", friendMatch);
        assertFalse("обычный друг не должен попадать в группу, скор " + friendMatch.score,
                friendMatch.inGroup);
        assertTrue("второй аккаунт должен быть выше друга: " + altMatch.score
                        + " vs " + friendMatch.score,
                altMatch.score > friendMatch.score);
    }

    @Test
    public void reportsErrorWhenFriendsAreHidden() {        SteamGraphBuilder builder = new SteamGraphBuilder();
        String seed = id(123_456);
        builder.playerFriednList.put(seed, null);

        DetectionResult result = new SamePersonDetector(builder).detect(seed);

        assertNotNull("должно быть сообщение об ошибке", result.message);
        assertFalse(result.hasMatches());
    }

    // ------------------------------------------------------------------

    private static String id(long number) {
        return Long.toString(STEAMID64_BASE + number);
    }

    private static Friend friend(String steamId, long since) {
        Friend friend = new Friend();
        friend.steamid = steamId;
        friend.relationship = "friend";
        friend.friend_since = since;
        return friend;
    }

    private static Players player(String steamId, String name, String avatar, long created) {
        Players player = new Players();
        player.steamid = steamId;
        player.personaname = name;
        player.avatar = avatar;
        player.timecreated = created;
        player.communityvisibilitystate = 3;
        return player;
    }

    private static void put(SteamGraphBuilder builder, String steamId, String name,
                            String avatar, long created, List<Friend> friends) {
        builder.playerFriednList.put(steamId, friends);
        builder.playerData.put(steamId, player(steamId, name, avatar, created));
    }

    private static AccountMatch find(DetectionResult result, String steamId) {
        for (AccountMatch match : result.matches) {
            if (match.steamId.equals(steamId)) {
                return match;
            }
        }
        return null;
    }
}
