package com.papasmurf;
import java.io.FileWriter;
import java.io.Writer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JFrame;

import org.jgrapht.Graph;
import org.jgrapht.ext.JGraphXAdapter;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DefaultUndirectedGraph;

import com.mxgraph.layout.mxCircleLayout;
import com.mxgraph.layout.mxOrganicLayout;
import com.mxgraph.layout.hierarchical.mxHierarchicalLayout;
import com.mxgraph.swing.mxGraphComponent;
import com.mxgraph.layout.mxCompactTreeLayout;


import com.papasmurf.api.GetSteamAPI;
import com.papasmurf.api.dto.Players;
import com.papasmurf.api.dto.Friend;

public class SteamGraphBuilder {
    public Graph<String, DefaultEdge> graph = new DefaultUndirectedGraph<>(DefaultEdge.class);

    public GetSteamAPI geter = new GetSteamAPI();
    private Set<String> visited = new HashSet<>();

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
        long months = days / 30;


        if (months < 36){
            int MAX_SCORE = 50;
            int MAX_AGE_MONTHS = 36; // Аккаунты старше 3 лет (36 мес) получают 0 очков

            double score = MAX_SCORE - (months * MAX_SCORE) / (double) MAX_AGE_MONTHS;

            finalSum += score;
        }

        if (friendsList != null){
            if (friendsList.size() < 7){
                finalSum += 30;
            }else if (friendsList.size() > 150){
                finalSum += 15;
            }
        }

        if (player.avatar.equals("https://avatars.steamstatic.com/fef49e7fa7e1997310d705b2a6158ff8dc1cdfeb.jpg")){
            finalSum += 20;
        }

        // System.out.println(finalSum);
        if (finalSum >= 60){
            return true;
        }else{
            return false;
        }
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

        if (friendsList != null){
            for (Friend friend: friendsList){
            graph.addVertex(friend.steamid);
            graph.addEdge(steamid, friend.steamid);

            if (true){ //isSuspect(friend.steamid)
                addSteamProfile(friend.steamid, depth + 1, maxDepth);
            }
        }
            // try{
            //     Thread.sleep(3000);
            // }catch(InterruptedException e){
            //     Thread.currentThread().interrupt(); 
            //     System.out.println("Ожидание было прервано");
            // }
        }
    }



    public void showGraph(){
        JGraphXAdapter<String, DefaultEdge> graphAdapter = new JGraphXAdapter<>(graph);

        // 3. Применяем алгоритм layout (расстановки узлов)
        // OrganicLayout идеален для социальных графов, он группирует связанные узлы
        mxCircleLayout layout = new mxCircleLayout(graphAdapter);
        layout.setRadius(100);
        layout.execute(graphAdapter.getDefaultParent());

        // Создаем компонент
        mxGraphComponent component = new mxGraphComponent(graphAdapter);

        // Увеличиваем окно
        JFrame frame = new JFrame("Steam Social Graph");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(component);
        frame.setSize(2000, 1500);  // Большое окно
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        component.zoomTo(0.4, true);
        }
}
