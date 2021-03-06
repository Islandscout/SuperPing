package me.islandscout.superping;

import io.netty.channel.*;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;

class PacketListener {

    private static final String HANDLER_NAME = "superping_packet_processor";

    private PingManager pingManager;

    PacketListener(PingManager pingManager) {
        this.pingManager = pingManager;
    }

    void add(Player p) {
        ChannelDuplexHandler channelDuplexHandler = new ChannelDuplexHandler() {
            @Override
            public void channelRead(ChannelHandlerContext context, Object packet) throws Exception {

                try {
                    pingManager.packetIn(packet, p);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                super.channelRead(context, packet);
            }

            @Override
            public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) throws Exception {

                try {
                    pingManager.packetOut(packet, p);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                super.write(context, packet, promise);
            }
        };
        ChannelPipeline pipeline;
        pipeline = ((CraftPlayer) p).getHandle().playerConnection.networkManager.channel.pipeline();
        if (pipeline == null)
            return;
        if (pipeline.get(HANDLER_NAME) != null)
            pipeline.remove(HANDLER_NAME);
        pipeline.addBefore("packet_handler", HANDLER_NAME, channelDuplexHandler);
    }

    void remove(Player p) {
        Channel channel = ((CraftPlayer) p).getHandle().playerConnection.networkManager.channel;

        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(HANDLER_NAME) != null)
            channel.eventLoop().submit(() -> {
                pipeline.remove(HANDLER_NAME);
            });
    }

    void removeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Channel channel = ((CraftPlayer) p).getHandle().playerConnection.networkManager.channel;

            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null)
                pipeline.remove(HANDLER_NAME);
        }
    }
}
