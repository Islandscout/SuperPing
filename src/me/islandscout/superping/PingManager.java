package me.islandscout.superping;

import io.netty.buffer.Unpooled;
import net.minecraft.server.v1_8_R3.PacketDataSerializer;
import net.minecraft.server.v1_8_R3.PacketPlayInFlying;
import net.minecraft.server.v1_8_R3.PacketPlayInKeepAlive;
import net.minecraft.server.v1_8_R3.PacketPlayOutKeepAlive;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
    private final Map<UUID, Long> lastKeepaliveTimeMap;
    private final Map<UUID, List<Pair<Integer, Long>>> respondedPingsMap; //last responses since 50ms ago

    PingManager(SuperPing plugin) {
        this.plugin = plugin;
        this.pListener = new PacketListener(this);
        this.pendingPingsMap = new ConcurrentHashMap<>();
        this.lastKeepaliveTimeMap = new ConcurrentHashMap<>();
        this.respondedPingsMap = new ConcurrentHashMap<>();
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
        else if(packet instanceof PacketPlayInFlying){
            handleFlyings(p);
        }
    }

    private void sendPing(Player p) {
        handlePing(p, HandleType.PREPARE, -1, -1);
    }

    void packetOut(Object packet, Player p) {
        if(packet instanceof PacketPlayOutKeepAlive) {
            PacketDataSerializer serializer = new PacketDataSerializer(Unpooled.buffer());
            try {
                ((PacketPlayOutKeepAlive) packet).b(serializer);
            }
            catch (IOException e) {
                e.printStackTrace();
            }
            int id = serializer.e();
            handlePing(p, HandleType.WRITE, id, -1);
        }
    }

    private void computeKeepAlivePing(int id, long currTime, Player p) {
        handlePing(p, HandleType.READ, id, currTime);
    }

    //Flyings are assumed to be received in 50ms intervals. If there is a choke release in the client -> server direction,
    //then most of the time, there should be 1 keepalive response between each flying during the packet burst.
    //In the case of a choke release in the server -> client direction, we want to ensure that the burst of keepalives
    //are accounted for. Because the client does not tick keepalives, but instead replies instantly, the ping variable in
    //SuperPing will drop too soon, giving an inaccurate representation of the ping for the next client tick. The getPing
    //method in here will get the highest ping value from the packet burst, but this handleFlyings method is necessary to
    //filter out client -> server packet bursts.
    private void handleFlyings(Player p) {
        long currTimeMillis = System.currentTimeMillis();
        UUID uuid = p.getUniqueId();
        List<Pair<Integer, Long>> respondedPings = respondedPingsMap.getOrDefault(uuid, new CopyOnWriteArrayList<>());

        //Size must be at least 2. We don't want to end up clearing all of them if multiple flyings manage to get received between keepalives
        //The pair value returns the retrieval timestamp, NOT the ping nor send timestamp.
        if(respondedPings.size() > 1 && currTimeMillis - respondedPings.get(0).getValue() < 50) { //TODO is this less than or greater than 50?
            respondedPings.remove(0);
        }

        respondedPingsMap.put(uuid, respondedPings);
    }

    //I want all three handle situations in here so that they cannot run concurrently
    private synchronized void handlePing(Player p, HandleType type, int id, long currTime) {
        UUID uuid = p.getUniqueId();

        switch (type) {
            //netty thread
            case READ: {
                List<Pair<Integer, Long>> pendingPings = pendingPingsMap.get(uuid);
                if (pendingPings == null) {
                    return;
                }

                long currTimeMillis = System.currentTimeMillis();

                List<Pair<Integer, Long>> replace = new CopyOnWriteArrayList<>(); //replaces the arraylist in the pendingpings map with pings not yet responded
                boolean foundResponse = false;
                int ping = 0;
                for (int i = 0; i < pendingPings.size(); i++) {
                    Pair<Integer, Long> pair = pendingPings.get(i);
                    if (foundResponse) {
                        replace.add(pair);
                    } else if (pair.getKey() == id) {
                        ping = (int) ((currTime - pair.getValue()) / 1000000);
                        foundResponse = true;
                        if (i != 0) { //client made goof-up and is out of order
                            kickPlayer(p, "Invalid ping response");
                        }
                    }
                }
                if (foundResponse) {
                    pendingPingsMap.put(uuid, replace);
                    lastKeepaliveTimeMap.put(p.getUniqueId(), currTimeMillis);

                    List<Pair<Integer, Long>> respondedPings = respondedPingsMap.getOrDefault(uuid, new CopyOnWriteArrayList<>());
                    Pair<Integer, Long> respondedPing = new Pair<>(ping, currTimeMillis);

                    //remove old entries
                    while(respondedPings.size() > 0 && currTimeMillis - respondedPings.get(0).getValue() > 50) {
                        respondedPings.remove(0);
                    }

                    respondedPings.add(respondedPing);
                    respondedPingsMap.put(uuid, respondedPings);
                }
                break;
            }

            //netty thread
            case WRITE: {
                List<Pair<Integer, Long>> pendingPings = pendingPingsMap.get(uuid);
                if(pendingPings == null) {
                    return;
                }

                for (Pair<Integer, Long> pair : pendingPings) {
                    if (pair.getKey() == id) {
                        pair.setValue(System.nanoTime());
                        break;
                    }
                }
                break;
            }

            //main thread
            case PREPARE: {
                List<Pair<Integer, Long>> pendingPings = pendingPingsMap.getOrDefault(uuid, new CopyOnWriteArrayList<>());

                if(pendingPings.size() >= TIMEOUT_TICKS) {
                    kickPlayer(p, "Timed out");
                }

                id = (int)((System.nanoTime() / 1000000L) + (OFFSET_SECONDS * 1000));
                Pair<Integer, Long> pair = new Pair<>(id, 1337L); //set to 1337 as the default value for now
                pendingPings.add(pair);
                pendingPingsMap.put(uuid, pendingPings);

                PacketPlayOutKeepAlive pingPacket = new PacketPlayOutKeepAlive(id);
                ((CraftPlayer)p).getHandle().playerConnection.sendPacket(pingPacket);
                break;
            }
        }
    }

    int getPing(Player p) {
        UUID uuid = p.getUniqueId();
        List<Pair<Integer, Long>> respondedPings = respondedPingsMap.getOrDefault(uuid, new CopyOnWriteArrayList<>());

        int highest = 0;
        //get highest ping from the list (if there are multiple, then it is due to a choke release in the server -> client direction)
        for(Pair<Integer, Long> respondedPing : respondedPings) {
            if(respondedPing.getKey() > highest)
                highest = respondedPing.getKey();
        }

        int ping = highest;
        long currTimeMillis = System.currentTimeMillis();
        long lastKeepaliveTime = lastKeepaliveTimeMap.getOrDefault(uuid, currTimeMillis) + 50;
        int choke = (int) Math.max(0, currTimeMillis - lastKeepaliveTime);

        return ping + choke;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        pListener.add(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        pendingPingsMap.remove(uuid);
        lastKeepaliveTimeMap.remove(uuid);
        respondedPingsMap.remove(uuid);
    }

    PacketListener getPacketListener() {
        return pListener;
    }

    private void kickPlayer(Player p, String msg) {
        pListener.remove(p);
        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> p.kickPlayer(msg));
    }

    private enum HandleType {
        PREPARE,
        WRITE,
        READ
    }
}
