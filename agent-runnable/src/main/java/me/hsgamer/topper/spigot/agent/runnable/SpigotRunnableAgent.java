package me.hsgamer.topper.spigot.agent.runnable;

import me.hsgamer.topper.agent.core.Agent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public class SpigotRunnableAgent implements Agent {
    private final Runnable runnable;
    private final Plugin plugin;
    private final boolean async;
    private final long interval;
    private BukkitTask task;

    public SpigotRunnableAgent(Runnable runnable, Plugin plugin, boolean async, long interval) {
        this.runnable = runnable;
        this.plugin = plugin;
        this.async = async;
        this.interval = interval;
    }

    @Override
    public void start() {
        if (async) {
            task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, runnable, interval, interval);
        } else {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, runnable, interval, interval);
        }
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
