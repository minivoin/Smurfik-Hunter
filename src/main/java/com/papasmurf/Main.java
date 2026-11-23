package com.papasmurf;

import com.papasmurf.detect.DetectionResult;
import com.papasmurf.detect.SamePersonDetector;

public class Main {
    public static void main(String[] args){

        SteamGraphBuilder a = new SteamGraphBuilder();

        // https://steamcommunity.com/profiles/76561198890900243 - основной аккаунт
        // https://steamcommunity.com/profiles/76561198669833133 - второй аккаунт
        // https://steamcommunity.com/profiles/76561198740323709 - третий аккаунт
        // https://steamcommunity.com/profiles/76561199215017110 - без аватарки

        String target = args.length > 0 ? args[0] : "76561198669833133";
        int depth = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        // Третий аргумент "only-group" печатает только подтверждённые аккаунты.
        boolean onlyGroup = args.length > 2 && args[2].equalsIgnoreCase("only-group");

        a.addSteamProfile(target, 0, depth);

        a.exportCSV();

        SamePersonDetector detector = new SamePersonDetector(a);
        DetectionResult result = detector.detect(target, depth);

        System.out.println(result.toReport(!onlyGroup));
        System.out.println(result.diagnosticsReport());
    }
}

