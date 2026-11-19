package com.papasmurf.detect;

import java.util.List;

/**
 * Один аккаунт-кандидат: насколько он похож на цель и почему.
 */
public class AccountMatch {

    public final String steamId;
    public final String personaName;

    /** Итоговый скор схожести с целью (0..1). */
    public final double score;

    /** Попал ли аккаунт в итоговую группу "один человек". */
    public final boolean inGroup;

    /** Разбивка скора по сигналам. */
    public final PairFeatures features;

    /** Человекочитаемые причины, по которым аккаунт признан похожим. */
    public final List<String> reasons;

    public AccountMatch(String steamId, String personaName, double score,
                        boolean inGroup, PairFeatures features, List<String> reasons) {
        this.steamId = steamId;
        this.personaName = personaName;
        this.score = score;
        this.inGroup = inGroup;
        this.features = features;
        this.reasons = reasons;
    }

    /** Копия записи с изменённым флагом inGroup (список неизменяемый). */
    public AccountMatch withGroupFlag(boolean value) {
        return new AccountMatch(steamId, personaName, score, value, features, reasons);
    }

    /** Копия записи с дополнительной причиной в конце списка. */
    public AccountMatch withReason(String reason) {
        List<String> extended = new java.util.ArrayList<>(reasons);
        extended.add(reason);
        return new AccountMatch(steamId, personaName, score, inGroup, features, extended);
    }
}
