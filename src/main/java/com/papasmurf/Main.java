package com.papasmurf;

public class Main {
    public static void main(String[] args){
        GetSteamAPI geter = new GetSteamAPI();

        geter.get_profile("76561198890900243");
        geter.get_friendList("76561198890900243");
    }
}
