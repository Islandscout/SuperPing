package me.islandscout.superping;

import net.minecraft.server.v1_8_R3.PacketPlayInKeepAlive;
import net.minecraft.server.v1_8_R3.PacketPlayOutKeepAlive;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PingManager implements Listener {

    //This ensures that we don't interfere with NMS's
    //ping calculation. We do not want to generate the
    //same numbers that NMS uses. NMS's pseudo random
    //number generator is based off nanotime, so we'll
    //do the same, but offset it by some great amount of
    //time to guarantee that we won't have a collision.
    private static final int OFFSET_SECONDS = -500;

    private final SuperPing plugin;
    private final PacketListener pListener;
    private final Map<UUID, List<Pair<Integer, Long>>> pendingPingsMap;
    private final Map<UUID, Long> lastPacketTimeMap;
    private final Map<UUID, Integer> pingMap;
    private int schedulerId;

    public PingManager(SuperPing plugin) {
        this.plugin = plugin;
        this.pListener = new PacketListener(this);
        this.pendingPingsMap = new ConcurrentHashMap<>();
        this.lastPacketTimeMap = new ConcurrentHashMap<>();
        this.pingMap = new ConcurrentHashMap<>();
        this.beginScheduler();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void beginScheduler() {
        schedulerId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, new Runnable() {
            @Override
            public void run() {
                for(Player p : Bukkit.getOnlinePlayers()) {
                    sendPing(p);
                }
            }
        }, 0L, 1L);
    }

    public void packetIn(Object packet, Player p) {
        long currTime = System.nanoTime();
        if(packet instanceof PacketPlayInKeepAlive) {
            computeKeepAlivePing(((PacketPlayInKeepAlive) packet).a(), currTime, p);
        }
        else {
            accumulateOthers(currTime, p);
        }
        lastPacketTimeMap.put(p.getUniqueId(), currTime);
    }

    private void computeKeepAlivePing(int id, long currTime, Player p) {
        UUID uuid = p.getUniqueId();
        List<Pair<Integer, Long>> pendingPings = pendingPingsMap.get(uuid);
        if(pendingPings == null) {
            return;
        }

        List<Pair<Integer, Long>> replace = new ArrayList<>();
        boolean foundResponse = false;
        int ping = 0;
        //TODO concurrency issues? main thread accesses map too!
        for (int i = 0; i < pendingPings.size(); i++) {
            Pair<Integer, Long> pair = pendingPings.get(i);
            if (foundResponse) {
                replace.add(pair);
            } else if (pair.getKey() == id) {
                ping = (int) (currTime - pair.getValue()) / 1000000;
                foundResponse = true;
            }
        }

        pendingPingsMap.put(uuid, replace);
        pingMap.put(uuid, ping);
    }

    private void accumulateOthers(long currTime, Player p) {
        UUID uuid = p.getUniqueId();
        int ping = getPing(p);
        long lastPacketTime = lastPacketTimeMap.getOrDefault(uuid, currTime);
        int add = (int) Math.max(0, currTime - lastPacketTime - 50000000) / 1000000;
        pingMap.put(uuid, ping + add);
    }

    private void sendPing(Player p) {
        UUID uuid = p.getUniqueId();
        List<Pair<Integer, Long>> pendingPings = pendingPingsMap.getOrDefault(uuid, new ArrayList<>());

        int id = (int)((System.nanoTime() / 1000000L) + (OFFSET_SECONDS * 1000));
        PacketPlayOutKeepAlive pingPacket = new PacketPlayOutKeepAlive(id);
        ((CraftPlayer)p).getHandle().playerConnection.sendPacket(pingPacket);

        Pair<Integer, Long> pair = new Pair<>(id, System.nanoTime());
        pendingPings.add(pair);
        pendingPingsMap.put(uuid, pendingPings);
    }

    public int getPing(Player p) {
        return pingMap.getOrDefault(p.getUniqueId(), 0);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        pListener.add(e.getPlayer());
    }

    public PacketListener getPacketListener() {
        return pListener;
    }
}
