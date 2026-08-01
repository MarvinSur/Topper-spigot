package me.hsgamer.topper.spigot.plugin.listener;

import me.hsgamer.topper.spigot.plugin.TopperPlugin;
import me.hsgamer.topper.spigot.plugin.template.SpigotTopTemplate;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class JoinListener implements Listener {
    private final TopperPlugin instance;

    public JoinListener(TopperPlugin instance) {
        this.instance = instance;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        instance.get(SpigotTopTemplate.class).getTopManager().create(event.getPlayer().getUniqueId());
    }
}
