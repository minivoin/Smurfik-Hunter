package com.papasmurf.detect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Результат поиска аккаунтов, принадлежащих одному человеку.
 */
public class DetectionResult {

    /** SteamID цели, от которой шёл поиск. */
    public final String seed;

    /** Ник цели. */
    public final String seedName;

    /** Кандидаты, отсортированные по убыванию скора. */
    public final List<AccountMatch> matches;

    /** Итоговая группа: цель + подтверждённые кандидаты. */
    public final List<String> group;

    /** Минимальный скор внутри группы — "слабое звено". */
    public final double groupConfidence;

    /** Текст проблемы, если анализ не удался (иначе null). */
    public final String message;

    /**
     * Служебные показатели: сколько данных собрано и на каком шаге всё остановилось.
     * Нужны, чтобы отличать "нечего сравнивать" от "сравнили, но не прошли порог".
     */
    public final List<String> diagnostics;

    /** Сколько аккаунтов реально сравнивалось с целью. */
    public final int candidatesScored;

    public DetectionResult(String seed, String seedName, List<AccountMatch> matches,
                           List<String> group, double groupConfidence, String message,
                           List<String> diagnostics, int candidatesScored) {
        this.seed = seed;
        this.seedName = seedName;
        this.matches = matches;
        this.group = group;
        this.groupConfidence = groupConfidence;
        this.message = message;
        this.diagnostics = diagnostics;
        this.candidatesScored = candidatesScored;
    }

    /** Есть ли вообще найденные "родственники". */
    public boolean hasMatches() {
        return matches != null && !matches.isEmpty();
    }

    /**
     * Текстовый отчёт. Формат специально плоский: его же можно отдать
     * Discord-боту и разложить по строкам эмбеда.
     */
    public String toReport() {
        return toReport(true);
    }

    /**
     * @param includeUnconfirmed показывать ли аккаунты, которые делят с целью
     *                           друзей, но не подтверждены группой. Если нужно
     *                           видеть только итог ("кто это"), передайте false.
     */
    public String toReport(boolean includeUnconfirmed) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Поиск аккаунтов, принадлежащих одному человеку ===\n");
        sb.append("Цель: ").append(seedName).append(" (").append(seed).append(")\n\n");

        if (message != null) {
            sb.append(message).append('\n');
            return sb.toString();
        }
        if (!hasMatches()) {
            if (candidatesScored == 0) {
                sb.append("Сравнивать нечего: у цели нет общих друзей с другими аккаунтами.\n");
            } else {
                sb.append("Проверено кандидатов: ").append(candidatesScored)
                        .append(", ни один не набрал минимальный порог похожести.\n");
            }
            return sb.toString();
        }

        sb.append("Проверено кандидатов: ").append(candidatesScored).append('\n');
        if (group.size() > 1) {
            sb.append(String.format(Locale.ROOT, "Аккаунтов в группе: %d (уверенность %.2f)\n",
                    group.size(), groupConfidence));
            sb.append("Один человек: ").append(seedName);
            for (AccountMatch match : matches) {
                if (match.inGroup) {
                    sb.append(", ").append(match.personaName);
                }
            }
            sb.append('\n');
        } else {
            sb.append("Ни один кандидат не подтвердился: либо мало общих друзей, "
                    + "либо аккаунты не похожи между собой.\n");
        }
        sb.append("Число рядом с аккаунтом — схожесть с целью (0..1), а не вероятность.\n\n");

        appendSection(sb, "ОДИН ЧЕЛОВЕК (подтверждено)", true);
        if (includeUnconfirmed && hasUnconfirmed()) {
            sb.append("Эти аккаунты делят с целью часть друзей, но не проходят проверку\n")
                    .append("на совпадение со всей группой. Обычно это просто знакомые.\n\n");
            appendSection(sb, "ПОХОЖИ НА ЦЕЛЬ, НО НЕ ПОДТВЕРЖДЕНЫ", false);
        }
        return sb.toString();
    }

    /** Есть ли кандидаты вне группы. */
    private boolean hasUnconfirmed() {
        for (AccountMatch match : matches) {
            if (!match.inGroup) {
                return true;
            }
        }
        return false;
    }

    /**
     * Печатает одну секцию отчёта: сначала подтверждённые участники группы,
     * потом отдельно всё остальное. Разделение принципиальное — иначе
     * случайный знакомый в общем списке выглядит как найденный аккаунт.
     */
    private void appendSection(StringBuilder sb, String title, boolean inGroup) {
        List<AccountMatch> section = new ArrayList<>();
        for (AccountMatch match : matches) {
            if (match.inGroup == inGroup) {
                section.add(match);
            }
        }
        if (section.isEmpty()) {
            return;
        }

        sb.append("--- ").append(title).append(" ---\n");
        int index = 1;
        for (AccountMatch match : section) {
            sb.append(index++).append(") ").append(match.personaName)
                    .append(" (").append(match.steamId).append(") — ")
                    .append(String.format(Locale.ROOT, "%.2f", match.score))
                    .append('\n');
            for (String reason : match.reasons) {
                sb.append("     - ").append(reason).append('\n');
            }
            sb.append('\n');
        }
    }

    /** Служебная сводка — печатается в конце отчёта. */
    public String diagnosticsReport() {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("--- диагностика ---\n");
        for (String line : diagnostics) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
