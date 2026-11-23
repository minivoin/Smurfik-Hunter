package com.papasmurf.api;


import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
// import java.time.Instant;
// import java.time.LocalDate;
// import java.time.ZoneId;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.papasmurf.api.dto.Friend;
import com.papasmurf.api.dto.FriendsResponse;
import com.papasmurf.api.dto.Players;
import com.papasmurf.api.dto.ProfileRrspomse;

public class GetSteamAPI {

    private String API;

    /** Один клиент на всё приложение: переиспользует соединения и TLS-сессии. */
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    /** Минимальный интервал между запросами: Steam не любит чаще ~1 раза в секунду. */
    private static final long MIN_REQUEST_INTERVAL_MS = 800;
    private static long lastRequestAt = 0L;

    public GetSteamAPI(){
        this.API = loadEnv("STEAM_API_KEY");
    }

    /** Простой троттлинг, чтобы не получить 429 и временный бан ключа. */
    private static synchronized void throttle() {
        long wait = lastRequestAt + MIN_REQUEST_INTERVAL_MS - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAt = System.currentTimeMillis();
    }

    /**
     * Ключ берётся из переменной окружения, а если её нет — из .env рядом с
     * процессом. В Docker .env в образ не копируется (это секрет), значения
     * приходят из docker-compose, поэтому окружение проверяется первым.
     */
    private String loadEnv(String key) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(".env"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // Пропускаем пустые строки и комментарии
                if (line.isEmpty() || line.startsWith("#")) continue; 
                
                String[] parts = line.split("=", 2);
                if (parts.length == 2 && parts[0].trim().equals(key)) {
                    return parts[1].trim();
                }
            }
        } catch (IOException e) {
            System.err.println("Ошибка: файл .env не найден в корне проекта!");
        }
        return null;
    }


    public Players get_profile(String profileID){

        // System.out.println(profileID);
        String url = String.format(
            "https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/?key=%s&steamids=%s", 
            API, profileID
        );

        // System.out.println(url);
        throttle();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                System.out.println("Успешный ответ от Steam API: профиль получен");
                // System.out.println(response.body());

                Gson gson = new Gson();
                ProfileRrspomse parsed = gson.fromJson(response.body(), ProfileRrspomse.class);

                List<Players> players = parsed.response.players;
                Players player = players.get(0);

                // System.out.println(player.avatar + " " + player.personaname);

                return player;
                
            } else {
                System.out.println("Ошибка! Статус-код сервера: " + response.statusCode());
                return null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    public List<Friend> get_friendList(String profileID){

        String url = String.format(
            "https://api.steampowered.com/ISteamUser/GetFriendList/v1/?key=%s&steamid=%s&relationship=friend", 
            API, profileID
        );

        throttle();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                System.out.println("Успешный ответ от Steam API: друзья получены");
                // System.out.println(response.body());

                Gson gson = new Gson();
                FriendsResponse parsed = gson.fromJson(response.body(), FriendsResponse.class);

                List<Friend> friends = parsed.friendslist.friends;
                // System.out.println("Всего друзей: " + friends.size());   // 30

                // for (Friend f : friends) {
                //     LocalDate since = Instant.ofEpochSecond(f.friend_since)
                //                             .atZone(ZoneId.systemDefault())
                //                             .toLocalDate();
                //     System.out.println(f.steamid + " | друг с " + since);
                // }

                return friends;
            } else {
                System.out.println("Ошибка! Статус-код сервера: " + response.statusCode());
                return null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Превращает красивое имя профиля (steamcommunity.com/id/имя) в SteamID64.
     *
     * Нужно для Discord-бота: людям проще скопировать ссылку на профиль, чем
     * искать 17-значный номер. Steam возвращает {@code success=1} и сам id,
     * либо {@code success=42}, если такого имени нет.
     *
     * @return SteamID64 или null, если имя не найдено / запрос не удался.
     */
    public String resolveVanity(String vanity) {
        if (vanity == null || vanity.isBlank()) {
            return null;
        }
        String url = String.format(
            "https://api.steampowered.com/ISteamUser/ResolveVanityURL/v1/?key=%s&vanityurl=%s",
            API, URLEncoder.encode(vanity.trim(), StandardCharsets.UTF_8)
        );

        throttle();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.out.println("Ошибка! Статус-код сервера: " + response.statusCode());
                return null;
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject data = root.getAsJsonObject("response");
            if (data == null || data.get("success") == null || data.get("success").getAsInt() != 1) {
                return null;
            }
            return data.get("steamid").getAsString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
