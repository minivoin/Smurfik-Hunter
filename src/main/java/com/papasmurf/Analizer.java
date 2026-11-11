package com.papasmurf;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.papasmurf.api.dto.Friend;
import com.papasmurf.api.dto.Players;
import com.papasmurf.api.GetSteamAPI;


public class Analizer {

    // Порог "Дня 1" (48 часов в секундах)
    private static final long DAY_ONE_THRESHOLD_SECONDS = 48 * 60 * 60; 

    public String checkProfile(String steamURL){
        // Объявление экземпляра классов
        GetSteamAPI geter = new GetSteamAPI();
        //Вытаскиваем id из ссылки
        Pattern pattern = Pattern.compile("\\d{17}");
        Matcher matcher = pattern.matcher(steamURL);
        String steamid = null;
        if (matcher.find()) {
            steamid = matcher.group();
        }

        //Достаём данные профиля
        Players player = geter.get_profile(steamid);
        //Переменые дял подсчёта очков и вывода статистики 
        int finalSum = 0;
        StringBuilder details = new StringBuilder("Детали анализа:\n");


        //проверка на скрытность акаунта
        if (player.communityvisibilitystate == 1){
            return "Аккаунт сркыт";
        }

        List<Friend> friendsList = geter.get_friendList(steamid);

        Instant createdDate = Instant.ofEpochSecond(player.timecreated);

        long days = ChronoUnit.DAYS.between(createdDate, Instant.now());
        long months = days / 30;


        if (months < 36){
            int MAX_SCORE = 30;
            int MAX_AGE_MONTHS = 36; // Аккаунты старше 3 лет (36 мес) получают 0 очков

            double score = MAX_SCORE - (months * MAX_SCORE) / (double) MAX_AGE_MONTHS;

            finalSum += score;
            details.append(String.format("Возраст: %d мес. - +%.1f очков\n", months, score));
        }else {
            details.append("Возраст: >3 лет - 0 очков\n");
        }

        // проверка на количевсвто друзей
        if (friendsList != null){
            if (friendsList.size() < 7){
                finalSum += 15;
                details.append(String.format("Мало друзей: %d - +15 очков\n", friendsList.size()));
            }else if (friendsList.size() > 150){
                finalSum += 5;
                details.append(String.format("Немного друзей: %d - +5 очков\n", friendsList.size()));
            }
        }

        // проверка на начальный аватар
        if (player.avatar.equals("https://avatars.steamstatic.com/fef49e7fa7e1997310d705b2a6158ff8dc1cdfeb.jpg")){
            finalSum += 10;
            details.append("Дефолтный аватар - +10 очков\n");
        }

        // Проверяем, сколько друзей добавлено в первые 48 часов после создания аккаунта
        if (friendsList != null && !friendsList.isEmpty()) {
            int dayOneFriendsCount = 0;
            long dayOneLimit = player.timecreated + DAY_ONE_THRESHOLD_SECONDS;

            for (Friend friend : friendsList) {
                if (friend.friend_since <= dayOneLimit) {
                    dayOneFriendsCount++;
                }
            }

            if (dayOneFriendsCount >= 3) {
                finalSum += 20;
                details.append(String.format("Друзья Дня 1: %d друзей добавлены в первые 48ч - +25 очков\n", dayOneFriendsCount));
            } else if (dayOneFriendsCount >= 1) {
                finalSum += 15;
                details.append(String.format("Друзья Дня 1: %d друзей добавлены в первые 48ч - +15 очков\n", dayOneFriendsCount));
            } else {
                details.append("Друзья Дня 1: 0 - 0 очков\n");
            }
        }

        // Проверяем, добавлены ли большинство друзей в короткий период
        if (friendsList != null && friendsList.size() >= 3) {
            // Собираем и сортируем все timestamp'ы добавления друзей
            List<Long> friendSinceTimes = friendsList.stream()
                    .map(f -> f.friend_since)
                    .sorted()
                    .toList();
            
            int maxFriendsInWindow = 0;
            long bestWindowDuration = 0; // Для отчёта
            
            // Скользящее окно: для каждого друга считаем, сколько друзей добавлено в следующие N часов
            // Проверяем несколько окон: 24ч, 48ч, 72ч
            long[] windowSizes = {24 * 60 * 60, 48 * 60 * 60, 72 * 60 * 60}; // 1, 2, 3 дня в секундах
            
            for (int i = 0; i < friendSinceTimes.size(); i++) {
                long windowStart = friendSinceTimes.get(i);
                
                // Проверяем каждое окно
                for (int w = 0; w < windowSizes.length; w++) {
                    long windowEnd = windowStart + windowSizes[w];
                    int count = 0;
                    
                    // Считаем, сколько друзей попало в это окно
                    for (int j = i; j < friendSinceTimes.size(); j++) {
                        if (friendSinceTimes.get(j) <= windowEnd) {
                            count++;
                        } else {
                            break; // Дальше уже не влезут, список отсортирован
                        }
                    }
                    
                    if (count > maxFriendsInWindow) {
                        maxFriendsInWindow = count;
                        bestWindowDuration = windowSizes[w];
                    }
                }
            }
            
            // Оцениваем результат
            long bestWindowDays = bestWindowDuration / (24 * 60 * 60);
            
            if (maxFriendsInWindow >= 10) {
                finalSum += 25;
                details.append(String.format("Пачка: %d друзей за %d дн. - +25 очков\n", maxFriendsInWindow, bestWindowDays));
            } else if (maxFriendsInWindow >= 7) {
                finalSum += 20;
                details.append(String.format("Пачка: %d друзей за %d дн. - +20 очков\n", maxFriendsInWindow, bestWindowDays));
            } else if (maxFriendsInWindow >= 5) {
                finalSum += 15;
                details.append(String.format("Пачка: %d друзей за %d дн. - +15 очков\n", maxFriendsInWindow, bestWindowDays));
            } else if (maxFriendsInWindow >= 3) {
                finalSum += 10;
                details.append(String.format("Пачка: %d друзей за %d дн. - +10 очков\n", maxFriendsInWindow, bestWindowDays));
            } else {
                details.append(String.format("Пачка: макс. %d друзей в окне - 0 очков\n", maxFriendsInWindow));
            }
        }

        details.append(String.format("\nИТОГО: %.0f / 100 очков\n", (double) finalSum));

        if (finalSum >= 65) {
            details.append("ВЕРДИКТ: СМУРФ (высокая вероятность)");
        } else if (finalSum >= 45) {
            details.append("ВЕРДИКТ: Подозрительный аккаунт");
        } else {
            details.append("ВЕРДИКТ: Обычный аккаунт");
        }

        return details.toString();
    }

}
