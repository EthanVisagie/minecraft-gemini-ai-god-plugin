package net.bigyous.gptgodmc.reactions;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.bigyous.gptgodmc.GameLoop;
import net.bigyous.gptgodmc.GPTGOD;
import net.bigyous.gptgodmc.memory.MemoryStore;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public class ReactionListener implements Listener {
    private static final JavaPlugin PLUGIN = JavaPlugin.getPlugin(GPTGOD.class);

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        ReactionEngine.onPlayerJoin(event.getPlayer());
        GameLoop.triggerSoon("player join", 20);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player killer = event.getPlayer().getKiller();
        if (killer != null) {
            MemoryStore.recordKill(killer, event.getPlayer());
        }
        ReactionEngine.onPlayerDeath(event.getPlayer(), killer);
        GameLoop.triggerSoon("player death", 20);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(PLUGIN, () -> {
            ReactionEngine.onChat(player, message);
            GameLoop.triggerSoon("player chat", 30);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombat(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker) {
            ReactionEngine.onCombat(attacker, event.getEntity());
            GameLoop.triggerSoon("combat", 20);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        MemoryStore.recordQuit(event.getPlayer());
    }
}
