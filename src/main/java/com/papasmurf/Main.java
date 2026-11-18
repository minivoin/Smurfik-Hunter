package com.papasmurf;

public class Main {
    public static void main(String[] args){

        SteamGraphBuilder a = new SteamGraphBuilder();

        Analizer v = new Analizer();

        // https://steamcommunity.com/profiles/76561198890900243/ - пример не смурфа
        // https://steamcommunity.com/profiles/76561198669833133 - пример смурфа
        // https://steamcommunity.com/profiles/76561198740323709
        // https://steamcommunity.com/profiles/76561199215017110 - без аватарки

        a.addSteamProfile("76561198669833133", 0, 2);

        // a.exportCSV();

        System.out.println(v.checkProfile("https://steamcommunity.com/profiles/76561198669833133"));
    }
}
