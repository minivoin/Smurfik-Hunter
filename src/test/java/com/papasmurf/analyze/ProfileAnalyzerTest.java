package com.papasmurf.analyze;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.papasmurf.api.dto.Friend;
import com.papasmurf.api.dto.Players;

/**
 * Проверка баллов первичного анализа. Сеть не нужна: даты и друзья задаются
 * вручную, "сейчас" передаётся параметром.
 */
public class ProfileAnalyzerTest {

    /** 2023-11-14T22:13:20Z — просто удобная фиксированная точка. */
    private static final long NOW = 1_700_000_000L;
    private static final long DAY = 86_400L;

    private static final String DEFAULT_AVATAR =
            "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/avatars/fe/"
                    + "fef49e7fa7e1997310d705b2a6158ff8dc1cdfeb_full.jpg";
    private static final String CUSTOM_AVATAR =
            "https://avatars.steamstatic.com/0123456789abcdef0123456789abcdef01234567_full.jpg";

    @Test
    public void youngAccountWithDayOneFriendsScoresAsSmurf() {
        long created = NOW - 60 * DAY;
        Players player = player(created, DEFAULT_AVATAR);

        List<Friend> friends = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            friends.add(friend(created + 3_600 + i * 600));
        }

        ProfileReport report = ProfileAnalyzer.score(player, friends, NOW);

        assertEquals(2, report.ageMonths);
        assertTrue("стандартный аватар должен распознаваться", report.defaultAvatar);
        assertEquals(4, report.dayOneFriends);
        assertEquals(4, report.maxFriendsInWindow);
        assertEquals(1, report.windowDays);
        assertTrue("балл " + report.score, report.score >= 80);
        assertEquals("SMURF", report.verdict);
    }

    @Test
    public void oldAccountWithSpreadFriendsScoresAsNormal() {
        long created = NOW - 60 * 30 * DAY;
        Players player = player(created, CUSTOM_AVATAR);

        List<Friend> friends = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            friends.add(friend(created + (200 + i * 5) * DAY));
        }

        ProfileReport report = ProfileAnalyzer.score(player, friends, NOW);

        assertFalse(report.defaultAvatar);
        assertEquals(0, report.dayOneFriends);
        assertTrue("пачки быть не должно: " + report.maxFriendsInWindow, report.maxFriendsInWindow <= 1);
        assertEquals(5.0, report.score, 0.001);
        assertEquals("NORMAL", report.verdict);
    }

    @Test
    public void hiddenFriendsListDoesNotBreakScoring() {
        long created = NOW - 5 * DAY;
        Players player = player(created, CUSTOM_AVATAR);

        ProfileReport report = ProfileAnalyzer.score(player, null, NOW);

        assertTrue("список друзей скрыт", report.friendsHidden);
        assertEquals(0, report.friends);
        // Возраст 0 месяцев даёт максимум, остальные проверки без данных — ноль.
        assertEquals(30.0, report.score, 0.001);
        for (ProfileReport.Check check : report.checks) {
            if ("day1".equals(check.code) || "pack".equals(check.code)) {
                assertEquals(0.0, check.points, 0.001);
            }
        }
    }

    @Test
    public void missingCreationDateSkipsAgeCheck() {
        Players player = player(0, CUSTOM_AVATAR);
        player.timecreated = null;

        ProfileReport report = ProfileAnalyzer.score(player, new ArrayList<>(), NOW);

        assertNull(report.createdAt);
        assertEquals(0.0, check(report, "age").points, 0.001);
        assertEquals(0.0, check(report, "day1").points, 0.001);
        // Пустой список друзей сам по себе даёт баллы за "мало друзей".
        assertEquals(15.0, report.score, 0.001);
        assertEquals("NORMAL", report.verdict);
    }

    // ------------------------------------------------------------------

    private static ProfileReport.Check check(ProfileReport report, String code) {
        for (ProfileReport.Check check : report.checks) {
            if (code.equals(check.code)) {
                return check;
            }
        }
        throw new AssertionError("нет проверки " + code);
    }

    private static Players player(long createdAt, String avatar) {
        Players player = new Players();
        player.steamid = "76561198000000001";
        player.personaname = "Тестовый аккаунт";
        player.avatar = avatar;
        player.communityvisibilitystate = 3;
        player.timecreated = createdAt;
        return player;
    }

    private static Friend friend(long friendSince) {
        Friend friend = new Friend();
        friend.steamid = "7656119800000" + friendSince;
        friend.relationship = "friend";
        friend.friend_since = friendSince;
        return friend;
    }
}
