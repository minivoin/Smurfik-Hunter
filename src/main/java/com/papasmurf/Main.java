package com.papasmurf;


public class Main {
    public static void main(String[] args){

        SteamGraphBuilder a = new SteamGraphBuilder();

        Analizer v = new Analizer();

        v.checkProfile("https://steamcommunity.com/profiles/76561198890900243/");

        // a.addSteamProfile("76561198669833133",  0, 2);

        // a.showGraph();
    }
}
