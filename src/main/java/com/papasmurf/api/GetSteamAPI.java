package com.papasmurf.api;


import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
// import java.time.Instant;
// import java.time.LocalDate;
// import java.time.ZoneId;
import java.util.List;

import com.google.gson.Gson;
import com.papasmurf.api.dto.Friend;
import com.papasmurf.api.dto.FriendsResponse;
import com.papasmurf.api.dto.Players;
import com.papasmurf.api.dto.ProfileRrspomse;

public class GetSteamAPI {

    private String API;

    public GetSteamAPI(){
        this.API = loadEnv("STEAM_API_KEY");
    }

    private String loadEnv(String key) {
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

        String url = String.format(
            "https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/?key=%s&steamids=%s", 
            API, profileID
        );

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                System.out.println("Успешный ответ от Steam API:");
                // System.out.println(response.body());

                Gson gson = new Gson();
                ProfileRrspomse parsed = gson.fromJson(response.body(), ProfileRrspomse.class);

                List<Players> players = parsed.response.players;
                Players player = players.get(0);

                // System.out.println(player);

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

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                System.out.println("Успешный ответ от Steam API:");
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
}
