package com.papasmurf.detect;

/**
 * Признаки похожести одной пары аккаунтов.
 *
 * Все оценки, кроме счётчиков, нормированы в [0, 1], где 1 — полное совпадение
 * сигнала. Сырые значения (число общих друзей и т.п.) хранятся отдельно, чтобы
 * потом показать пользователю, НА ОСНОВАНИИ ЧЕГО сделан вывод.
 */
public class PairFeatures {

    /** Доля меньшего списка друзей, которая совпала (0..1). */
    public final double sharedFriends;

    /** Насколько синхронно аккаунты добавляли общих друзей (0..1). */
    public final double coAdd;

    /** Близость дат регистрации (0..1). */
    public final double creation;

    /** Состоят ли аккаунты в друзьях друг у друга (0 или 1). */
    public final double directFriend;

    /** Совпадение аватара и похожесть ника (0..1). */
    public final double profile;

    /**
     * "Залповый" набор друзей (0..1): какая доля круга добавлена в течение
     * 48 часов. У второго аккаунта круг обычно собирается за один вечер.
     */
    public final double burst;

    /** Сколько друзей у пары общих (сырое число для отчёта). */
    public final int commonFriends;

    /** Сколько общих друзей добавлено с разницей меньше часа. */
    public final int syncedFriends;

    /** Совпадает ли аватар. */
    public final boolean avatarMatches;

    /** Похожесть ников по Левенштейну (0..1). */
    public final double nameSimilarity;

    public PairFeatures(double sharedFriends, double coAdd, double creation,
                        double directFriend, double profile,
                        int commonFriends, int syncedFriends,
                        boolean avatarMatches, double nameSimilarity, double burst) {
        this.sharedFriends = sharedFriends;
        this.coAdd = coAdd;
        this.creation = creation;
        this.directFriend = directFriend;
        this.profile = profile;
        this.commonFriends = commonFriends;
        this.syncedFriends = syncedFriends;
        this.avatarMatches = avatarMatches;
        this.nameSimilarity = nameSimilarity;
        this.burst = burst;
    }

    /**
     * Итоговый скор пары: взвешенная сумма сигналов, делённая на сумму весов.
     * Деление обязательно, иначе максимум был бы 0.85, а не 1.0.
     */
    public double total(Weights w) {
        double sum = w.sum();
        if (sum <= 0) {
            return 0;
        }
        return (w.sharedFriends * sharedFriends
                + w.coAdd * coAdd
                + w.creation * creation
                + w.directFriend * directFriend
                + w.profile * profile
                + w.burst * burst) / sum;
    }
}
