package com.papasmurf;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DefaultUndirectedGraph;

import com.papasmurf.api.GetSteamAPI;
import com.papasmurf.api.dto.Players;
import com.papasmurf.api.dto.Friend;

public class SteamGraphBuilder {
    public Graph<String, DefaultEdge> graph = new DefaultUndirectedGraph<>(DefaultEdge.class);

    public GetSteamAPI geter = new GetSteamAPI();
    private Set<String> visited;

    public Map<String, Players> playerData = new HashMap<>();
    public Map<String, List<Friend>> playerFriednList = new HashMap<>();

    // graph.addVertex(steamId); // Добавляем точку в граф
    // playerData.put(steamId); 

    public boolean isSuspect(String steamid){
        Players player = geter.get_profile(steamid);
        int finalSum = 0;

        if (player.communityvisibilitystate == 1){
            return false;
        }
        List<Friend> friendsList = geter.get_friendList(steamid);

        Instant createdDate = Instant.ofEpochSecond(player.timecreated);

        long days = ChronoUnit.DAYS.between(createdDate, Instant.now());
        long years = days / 365;
        long months = days / 30;

        System.out.println(years);
        System.out.println(months);

        return true;
    }

    public void addSteamProfile(String steamid, int depth, int maxDepth){
        if (depth >= maxDepth){
            return;
        }

        if (visited.contains(steamid)){
            return;
        }

        visited.add(steamid);
        Players thisPlayer = geter.get_profile(steamid);
        List<Friend> friendsList = geter.get_friendList(steamid);

        graph.addVertex(steamid);
        playerData.put(steamid, thisPlayer);
        playerFriednList.put(steamid, friendsList);

        for (Friend friend: friendsList){
            graph.addVertex(friend.steamid);
            graph.addEdge(steamid, friend.steamid);

            if (true){
                addSteamProfile(friend.steamid, depth + 1, maxDepth);
            }
        }
    }
}
