# SuperPing
SuperPing is a super responsive ping API which allows developers to obtain a player's nearly instantaneous ping. It is a super responsive system: player's ping is upated for every packet recieved from them, with keepalive packets sent every tick in order to correct any errors. In a sense, ping is updated nearly continuously!

To compile this, make sure that you have Spigot 1_8_R3 as a build dependency in your IDE. If you do not want to use 1.8, you will need to edit the some imports and method calls in a few classes.

This API features one static method to get the player's ping in `me.islandscout.superping.SuperPingLib`
```
int getPing(Player)
```
For example, you can do something like this in your own plugin
```
for(Player p : Bukkit.getOnlinePlayers()) {
    p.sendMessage("Your ping: " + SuperPingLib.getPing(p) + "ms");
}
```
