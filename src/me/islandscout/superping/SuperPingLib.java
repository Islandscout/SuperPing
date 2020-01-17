package me.islandscout.superping;

import org.bukkit.entity.Player;

public final class SuperPingLib {

    public SuperPingLib() {
    }

    public static int getPing(Player p) {
        return SuperPing.getInstance().getPingManager().getPing(p);
    }
}
