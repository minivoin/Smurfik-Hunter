package com.papasmurf.analyze;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.papasmurf.api.GetSteamAPI;
import com.papasmurf.api.dto.Friend;
import com.papasmurf.api.dto.Players;

/**
 * Первичный анализ одного профиля по ссылке.
 *
 * Логика перенесена из старого {@code Analizer}: те же проверки и те же веса,
 * но с исправленными ошибками и структурным результатом вместо склеенной
 * строки:
 * <ul>
 *   <li>балл считался в {@code int}, из-за чего дробная часть очков за возраст
 *       отбрасывалась;</li>
 *   <li>в тексте про «Друзья Дня 1» было написано +25, а начислялось 20;</li>
 *   <li>дефолтный аватар сравнивался с одним конкретным URL, который Steam уже
 *       менял — теперь сравнивается хеш картинки.</li>
 * </ul>
 *
 * Все проверки чистые: {@link #score(Players, List, long)} не ходит в сеть и
 * поэтому легко тестируется.
 */
public class ProfileAnalyzer {

    /** Максимум очков за молодой аккаунт. */
    private static final int MAX_AGE_POINTS = 30;
    /** С какого возраста аккаунт перестаёт считаться молодым (месяцы). */
    private static final int MAX_AGE_MONTHS = 36;

    private static final int FEW_FRIENDS = 7;
    private static final int MANY_FRIENDS = 150;
    private static final int FEW_FRIENDS_POINTS = 15;
    private static final int MANY_FRIENDS_POINTS = 5;

    private static final int DEFAULT_AVATAR_POINTS = 10;

    private static final long DAY_ONE_SECONDS = 48 * 60 * 60L;
    private static final int DAY_ONE_MANY = 3;
    private static final int DAY_ONE_MANY_POINTS = 20;
    private static final int DAY_ONE_FEW_POINTS = 15;

    /** Окна, в которых ищем «пачку» добавленных друзей: 1, 2 и 3 дня. */
    private static final long[] PACK_WINDOWS = { 24 * 60 * 60L, 48 * 60 * 60L, 72 * 60 * 60L };

    private static final int VERDICT_SMURF = 65;
    private static final int VERDICT_SUSPICIOUS = 45;

    /** Хеш стандартного аватара Steam: он встречается во всех его URL. */
    private static final String DEFAULT_AVATAR_HASH = "fef49e7fa7e1997310d705b2a6158ff8dc1cdfeb";

    private final GetSteamAPI steam;

    public ProfileAnalyzer(GetSteamAPI steam) {
        this.steam = steam;
    }

    /**
     * Качает профиль и список друзей и сразу считает балл.
     *
     * @param steamId SteamID64 (ссылку разбирает вызывающий код)
     */
    public ProfileReport analyze(String steamId) {
        Players player = steam.get_profile(steamId);
        if (player == null || player.steamid == null) {
            return ProfileReport.error("PROFILE_NOT_FOUND",
                    "Steam не вернул профиль " + steamId + ". Проверьте ссылку.");
        }
        // 1 — профиль закрыт, 3 — открыт. У закрытого не будет и списка друзей.
        if (player.communityvisibilitystate == 1) {
            return ProfileReport.error("PROFILE_PRIVATE",
                    "Профиль закрыт настройками приватности: ни друзей, ни даты "
                    + "регистрации Steam не отдаёт, анализировать нечего.");
        }

        List<Friend> friends = steam.get_friendList(steamId);
        return score(player, friends, System.currentTimeMillis() / 1000L);
    }

    /**
     * Считает балл по уже загруженным данным.
     *
     * @param friends список друзей или null, если он скрыт
     * @param nowEpochSeconds текущее время в секундах — параметром, чтобы тесты
     *                        не зависели от календаря
     */
    public static ProfileReport score(Players player, List<Friend> friends, long nowEpochSeconds) {
        ProfileReport report = new ProfileReport();
        report.steamId = player.steamid;
        report.name = player.personaname;
        report.avatar = player.avatar;
        report.profileUrl = "https://steamcommunity.com/profiles/" + player.steamid;

        if (player.timecreated != null) {
            Instant created = Instant.ofEpochSecond(player.timecreated);
            report.createdAt = created.toString();
            long days = ChronoUnit.DAYS.between(created, Instant.ofEpochSecond(nowEpochSeconds));
            report.ageMonths = Math.max(0, days / 30);
        }

        boolean hasFriends = friends != null;
        report.friendsHidden = !hasFriends;
        report.friends = hasFriends ? friends.size() : 0;
        report.defaultAvatar = isDefaultAvatar(player.avatar);

        addAgeCheck(report);
        addFriendsCheck(report);
        addAvatarCheck(report);
        addDayOneCheck(report, player, friends);
        addPackCheck(report, friends);

        double total = 0;
        for (ProfileReport.Check check : report.checks) {
            total += check.points;
        }
        report.score = Math.round(Math.min(total, report.scoreMax) * 10.0) / 10.0;
        applyVerdict(report);
        return report;
    }

    // ------------------------------------------------------------------
    // Проверки
    // ------------------------------------------------------------------

    private static void addAgeCheck(ProfileReport report) {
        if (report.createdAt == null) {
            report.checks.add(new ProfileReport.Check("age", "Возраст аккаунта",
                    0, MAX_AGE_POINTS, "дата регистрации скрыта"));
            return;
        }
        if (report.ageMonths < MAX_AGE_MONTHS) {
            double points = MAX_AGE_POINTS - (report.ageMonths * (double) MAX_AGE_POINTS) / MAX_AGE_MONTHS;
            report.checks.add(new ProfileReport.Check("age", "Возраст аккаунта",
                    round(points), MAX_AGE_POINTS,
                    String.format(Locale.ROOT, "%d мес. — моложе %d лет", report.ageMonths, MAX_AGE_MONTHS / 12)));
        } else {
            report.checks.add(new ProfileReport.Check("age", "Возраст аккаунта",
                    0, MAX_AGE_POINTS,
                    String.format(Locale.ROOT, "%d мес. — старше %d лет", report.ageMonths, MAX_AGE_MONTHS / 12)));
        }
    }

    private static void addFriendsCheck(ProfileReport report) {
        if (report.friendsHidden) {
            report.checks.add(new ProfileReport.Check("friends", "Количество друзей",
                    0, FEW_FRIENDS_POINTS, "список друзей скрыт"));
            return;
        }
        if (report.friends < FEW_FRIENDS) {
            report.checks.add(new ProfileReport.Check("friends", "Количество друзей",
                    FEW_FRIENDS_POINTS, FEW_FRIENDS_POINTS,
                    String.format(Locale.ROOT, "%d — меньше %d", report.friends, FEW_FRIENDS)));
        } else if (report.friends > MANY_FRIENDS) {
            report.checks.add(new ProfileReport.Check("friends", "Количество друзей",
                    MANY_FRIENDS_POINTS, FEW_FRIENDS_POINTS,
                    String.format(Locale.ROOT, "%d — больше %d", report.friends, MANY_FRIENDS)));
        } else {
            report.checks.add(new ProfileReport.Check("friends", "Количество друзей",
                    0, FEW_FRIENDS_POINTS,
                    String.format(Locale.ROOT, "%d — в обычных пределах", report.friends)));
        }
    }

    private static void addAvatarCheck(ProfileReport report) {
        report.checks.add(new ProfileReport.Check("avatar", "Аватар",
                report.defaultAvatar ? DEFAULT_AVATAR_POINTS : 0, DEFAULT_AVATAR_POINTS,
                report.defaultAvatar ? "стандартная картинка Steam" : "свой аватар"));
    }

    private static void addDayOneCheck(ProfileReport report, Players player, List<Friend> friends) {
        if (friends == null || player.timecreated == null) {
            report.checks.add(new ProfileReport.Check("day1", "Друзья Дня 1",
                    0, DAY_ONE_MANY_POINTS, "нет данных: список друзей или дата регистрации скрыты"));
            return;
        }

        long limit = player.timecreated + DAY_ONE_SECONDS;
        int count = 0;
        for (Friend friend : friends) {
            if (friend.friend_since <= limit) {
                count++;
            }
        }
        report.dayOneFriends = count;

        if (count >= DAY_ONE_MANY) {
            report.checks.add(new ProfileReport.Check("day1", "Друзья Дня 1",
                    DAY_ONE_MANY_POINTS, DAY_ONE_MANY_POINTS,
                    String.format(Locale.ROOT, "%d друзей добавлены в первые 48 ч", count)));
        } else if (count >= 1) {
            report.checks.add(new ProfileReport.Check("day1", "Друзья Дня 1",
                    DAY_ONE_FEW_POINTS, DAY_ONE_MANY_POINTS,
                    String.format(Locale.ROOT, "%d друг добавлен в первые 48 ч", count)));
        } else {
            report.checks.add(new ProfileReport.Check("day1", "Друзья Дня 1",
                    0, DAY_ONE_MANY_POINTS, "в первые 48 ч друзей не добавляли"));
        }
    }

    /**
     * Ищет самую плотную «пачку»: сколько друзей добавлено за 1/2/3 дня.
     * Окно перебирается от короткого к длинному, поэтому при равном числе
     * друзей остаётся самое короткое окно.
     */
    private static void addPackCheck(ProfileReport report, List<Friend> friends) {
        if (friends == null) {
            report.checks.add(new ProfileReport.Check("pack", "Плотность добавлений",
                    0, 25, "список друзей скрыт"));
            return;
        }
        if (friends.size() < 3) {
            report.checks.add(new ProfileReport.Check("pack", "Плотность добавлений",
                    0, 25, "друзей слишком мало, чтобы говорить о пачке"));
            return;
        }

        List<Long> times = friends.stream()
                .map(friend -> friend.friend_since)
                .sorted(Comparator.naturalOrder())
                .toList();

        int best = 0;
        long bestWindow = 0;
        for (int i = 0; i < times.size(); i++) {
            for (long window : PACK_WINDOWS) {
                long end = times.get(i) + window;
                int count = 0;
                for (int j = i; j < times.size() && times.get(j) <= end; j++) {
                    count++;
                }
                if (count > best) {
                    best = count;
                    bestWindow = window;
                }
            }
        }

        report.maxFriendsInWindow = best;
        report.windowDays = (int) (bestWindow / (24 * 60 * 60L));

        int points;
        if (best >= 10) {
            points = 25;
        } else if (best >= 7) {
            points = 20;
        } else if (best >= 5) {
            points = 15;
        } else if (best >= 3) {
            points = 10;
        } else {
            points = 0;
        }

        String detail = points == 0
                ? String.format(Locale.ROOT, "максимум %d друзей за %d дн.", best, report.windowDays)
                : String.format(Locale.ROOT, "%d друзей за %d дн.", best, report.windowDays);
        report.checks.add(new ProfileReport.Check("pack", "Плотность добавлений",
                points, 25, detail));
    }

    private static void applyVerdict(ProfileReport report) {
        if (report.score >= VERDICT_SMURF) {
            report.verdict = "SMURF";
            report.verdictLabel = "Похоже на смурф";
        } else if (report.score >= VERDICT_SUSPICIOUS) {
            report.verdict = "SUSPICIOUS";
            report.verdictLabel = "Подозрительный аккаунт";
        } else {
            report.verdict = "NORMAL";
            report.verdictLabel = "Обычный аккаунт";
        }
    }

    private static boolean isDefaultAvatar(String avatar) {
        return avatar != null && avatar.contains(DEFAULT_AVATAR_HASH);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
