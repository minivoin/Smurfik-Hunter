package com.papasmurf.analyze;

import java.util.ArrayList;
import java.util.List;

/**
 * Результат первичного анализа одного профиля — «насколько аккаунт похож на смурф».
 *
 * Это грубая эвристика на 100 очков по данным, которые Steam отдаёт без
 * загрузки графа: возраст, число друзей, аватар, скорость набора друзей.
 * Она ничего не доказывает и не заменяет поиск смежных аккаунтов — только
 * показывает, стоит ли копать дальше.
 */
public class ProfileReport {

    public boolean ok = true;

    /** Причина отказа, если ok = false: PROFILE_NOT_FOUND / PROFILE_PRIVATE / NO_DATA. */
    public String code;
    public String message;

    public String steamId;
    public String name;
    public String avatar;
    public String profileUrl;

    /** Дата регистрации в ISO-8601; null, если Steam её скрывает. */
    public String createdAt;
    public long ageMonths;

    public int friends;
    /** true, если список друзей недоступен (закрыт приватностью). */
    public boolean friendsHidden;

    public boolean defaultAvatar;
    public int dayOneFriends;
    public int maxFriendsInWindow;
    public int windowDays;

    /** Итоговый балл 0..100. */
    public double score;
    public int scoreMax = 100;

    /** Машиночитаемый вердикт: SMURF / SUSPICIOUS / NORMAL. */
    public String verdict;
    /** Тот же вердикт по-русски. */
    public String verdictLabel;

    /** Из чего сложился балл — по пункту на проверку. */
    public List<Check> checks = new ArrayList<>();

    /** Один пункт оценки: сколько очков дал и почему. */
    public static class Check {
        public String code;
        public String title;
        public double points;
        public double maxPoints;
        public String detail;

        public Check(String code, String title, double points, double maxPoints, String detail) {
            this.code = code;
            this.title = title;
            this.points = points;
            this.maxPoints = maxPoints;
            this.detail = detail;
        }
    }

    public static ProfileReport error(String code, String message) {
        ProfileReport report = new ProfileReport();
        report.ok = false;
        report.code = code;
        report.message = message;
        return report;
    }
}
