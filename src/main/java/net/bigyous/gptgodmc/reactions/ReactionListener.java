package net.bigyous.gptgodmc.reactions;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.bigyous.gptgodmc.GameLoop;
import net.bigyous.gptgodmc.GPT.GptActions;
import net.bigyous.gptgodmc.GPTGOD;
import net.bigyous.gptgodmc.memory.MemoryStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
        GptActions.onTrialPlayerDeath(event.getPlayer());
        ReactionEngine.onPlayerDeath(event.getPlayer(), killer);
        GameLoop.triggerSoon("player death", 20);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        if (GptActions.onTrialEntityDeath(event.getEntity(), event.getEntity().getKiller())) {
            GameLoop.triggerSoon("divine trial progress", 20);
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            ReactionEngine.onMobKill(killer, event.getEntity());
            GameLoop.triggerSoon("monster kill", 30);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        ReactionEngine.onAdvancement(event.getPlayer(), event.getAdvancement().getKey().getKey());
        GameLoop.triggerSoon("player advancement", 30);
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (GptActions.useAttunedFavorToken(event.getPlayer(), event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFavorPickup(PlayerAttemptPickupItemEvent event) {
        String theme = GptActions.getFavorTokenTheme(event.getItem().getItemStack());
        if (theme == null) {
            return;
        }
        event.getPlayer().sendActionBar(Component.text(
                "Attuned favor claimed: right-click to release it.", NamedTextColor.GOLD));
        event.getPlayer().playSound(event.getPlayer().getLocation(), org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                0.75f, 1.4f);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFavorHeld(PlayerItemHeldEvent event) {
        ItemStack item = event.getPlayer().getInventory().getItem(event.getNewSlot());
        String theme = GptActions.getFavorTokenTheme(item);
        if (theme == null) {
            return;
        }
        event.getPlayer().sendActionBar(Component.text(
                "Right-click to release " + theme + " favor. Use it in a favor zone to amplify it.",
                NamedTextColor.AQUA));
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
