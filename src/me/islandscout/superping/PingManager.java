package me.islandscout.superping;

import io.netty.buffer.Unpooled;
import net.minecraft.server.v1_8_R3.PacketDataSerializer;
import net.minecraft.server.v1_8_R3.PacketPlayInKeepAlive;
import net.minecraft.server.v1_8_R3.PacketPlayOutKeepAlive;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.IOException;
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
    private static final int TIMEOUT_TICKS = 600;

    private final SuperPing plugin;
    private final PacketListener pListener;
    private final Map<UUID, List<Pair<Integer, Long>>> pendingPingsMap;
    private final Map<UUID, Long> lastPacketTimeMap;
    private final Map<UUID, Integer> pingMap;

    PingManager(SuperPing plugin) {
        this.plugin = plugin;
        this.pListener = new PacketListener(this);
        this.pendingPingsMap = new ConcurrentHashMap<>();
        this.lastPacketTimeMap = new ConcurrentHashMap<>();
        this.pingMap = new ConcurrentHashMap<>();
        this.beginScheduler();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void beginScheduler() {
        Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            for(Player p : Bukkit.getOnlinePlayers()) {
                sendPing(p);
            }
        }, 0L, 1L);
    }

    void packetIn(Object packet, Player p) {
        long currTime = System.nanoTime();
        if(packet instanceof PacketPlayInKeepAlive) {
            computeKeepAlivePing(((PacketPlayInKeepAlive) packet).a(), currTime, p);
        }
        else {
            accumulateOthers(currTime, p);
        }
        lastPacketTimeMap.put(p.getUniqueId(), currTime);
    }

    void packetOut(Object packet, Player p) {
        if(packet instanceof PacketPlayOutKeepAlive) {

            UUID uuid = p.getUniqueId();
            List<Pair<Integer, Long>> pendingPings = pendingPingsMap.get(uuid);
            if(pendingPings == null) {
                return;
            }

            PacketDataSerializer serializer = new PacketDataSerializer(Unpooled.buffer());
            try {
                ((PacketPlayOutKeepAlive) packet).b(serializer);
            }
            catch (IOException e) {
                e.printStackTrace();
            }
            int id = serializer.e();

            //Do not replace with foreach because it may cause a concurrent mod exception.
            //The standard for-loop fixes this, and no negative effects will occur even
            //if a pendingPing entry is added from the main thread while this is looping.
            for (int i = 0; i < pendingPings.size(); i++) {
                Pair<Integer, Long> pair = pendingPings.get(i);
                if (pair.getKey() == id) {
                    pair.setValue(System.nanoTime());
                    break;
                }
            }
        }
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
        for (int i = 0; i < pendingPings.size(); i++) {
            Pair<Integer, Long> pair = pendingPings.get(i);
            if (foundResponse) {
                replace.add(pair);
            } else if (pair.getKey() == id) {
                ping = (int) ((currTime - pair.getValue()) / 1000000);
                foundResponse = true;
                if(i != 0) { //client made goof-up and is out of order
                    p.kickPlayer("Invalid ping response");
                }
            }
        }

        pendingPingsMap.put(uuid, replace);
        pingMap.put(uuid, ping);
    }

    private void accumulateOthers(long currTime, Player p) {
        UUID uuid = p.getUniqueId();
        int ping = getPing(p);
        long lastPacketTime = lastPacketTimeMap.getOrDefault(uuid, currTime);
        int add = (int) (Math.max(0, currTime - lastPacketTime - 50000000) / 1000000);
        pingMap.put(uuid, ping + add);
    }

    private void sendPing(Player p) {
        UUID uuid = p.getUniqueId();
        List<Pair<Integer, Long>> pendingPings = pendingPingsMap.getOrDefault(uuid, new ArrayList<>());

        if(pendingPings.size() >= TIMEOUT_TICKS) {
            p.kickPlayer("Timed out");
        }

        int id = (int)((System.nanoTime() / 1000000L) + (OFFSET_SECONDS * 1000));
        Pair<Integer, Long> pair = new Pair<>(id, 0L); //set to 0 for now
        pendingPings.add(pair);
        pendingPingsMap.put(uuid, pendingPings);

        PacketPlayOutKeepAlive pingPacket = new PacketPlayOutKeepAlive(id);
        ((CraftPlayer)p).getHandle().playerConnection.sendPacket(pingPacket);
    }

    int getPing(Player p) {
        return pingMap.getOrDefault(p.getUniqueId(), 0);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        pListener.add(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        pendingPingsMap.remove(uuid);
        lastPacketTimeMap.remove(uuid);
        pingMap.remove(uuid);
    }

    PacketListener getPacketListener() {
        return pListener;
    }
}
