package com.papasmurf;

import java.util.Scanner;

public class Main {
    public static void main(String[] args){

        SteamGraphBuilder a = new SteamGraphBuilder();

        Analizer v = new Analizer();

        
        // https://steamcommunity.com/profiles/76561198890900243/ - пример не смурфа
        // https://steamcommunity.com/profiles/76561198669833133 - пример смурфа
        // https://steamcommunity.com/profiles/76561198740323709


        Scanner scan = new Scanner(System.in);

        System.out.println("Вставтеь ссылку на Steam профиль");

        String steamURL = scan.nextLine();

        System.out.println(v.checkProfile(steamURL));

        scan.close();
    }
}
