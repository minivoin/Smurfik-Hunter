package com.papasmurf;


import com.papasmurf.api.GetSteamAPI;


public class Main {
    public static void main(String[] args){
        GetSteamAPI geter = new GetSteamAPI();

        SteamGraphBuilder a = new SteamGraphBuilder();

        a.isSuspect("76561198890900243");

        // geter.get_profile("76561198890900243");
        // geter.get_friendList("76561198890900243");
    }
}
