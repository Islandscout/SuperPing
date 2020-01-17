package me.islandscout.superping;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class SuperPing extends JavaPlugin {

    private static SuperPing superPing;
    private PingManager pingManager;

    @Override
    public void onEnable() {
        superPing = this;
        pingManager = new PingManager(this);
        for(Player p : Bukkit.getOnlinePlayers()) {
            pingManager.getPacketListener().add(p);
        }
        getLogger().info("SuperPing has been enabled.");
    }

    @Override
    public void onDisable() {
        pingManager.getPacketListener().removeAll();
        pingManager = null;
        superPing = null;
        getLogger().info("SuperPing has been disabled.");
    }

    PingManager getPingManager() {
        return pingManager;
    }

    static SuperPing getInstance() {
        return superPing;
    }
}
