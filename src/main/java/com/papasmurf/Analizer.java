package com.papasmurf;

import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class Analizer {
    public void checkProfile(String steamURL){
        
        SteamGraphBuilder graph = new SteamGraphBuilder();
        // https://steamcommunity.com/profiles/76561198890900243/
        Pattern pattern = Pattern.compile("\\d{17}");
        Matcher matcher = pattern.matcher(steamURL);
        String steamid;

        if (matcher.find()) {
            steamid = matcher.group();
        }


    }

}
