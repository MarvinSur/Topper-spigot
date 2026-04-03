package me.hsgamer.topper.spigot.plugin.template;

import me.hsgamer.hscore.common.CollectionUtils;
import me.hsgamer.topper.agent.update.UpdateAgent;
import me.hsgamer.topper.spigot.plugin.timed.TimePeriod;
import me.hsgamer.topper.template.topplayernumber.holder.NumberTopHolder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

public class SpigotTopHolderSettings extends NumberTopHolder.MapSettings {
    private final List<String> ignorePermissions;
    private final List<String> resetPermissions;

    public SpigotTopHolderSettings(Map<String, Object> map) {
        super(map);
        ignorePermissions = CollectionUtils.createStringListFromObject(map.get("ignore-permission"), true);
        resetPermissions = CollectionUtils.createStringListFromObject(map.get("reset-permission"), true);
    }

    public String defaultLine() {
        return Objects.toString(map.get("line"), null);
    }

    /**
     * Returns enabled timed periods from config.
     * Config example:
     * <pre>
     * timed:
     *   - weekly
     *   - monthly
     * </pre>
     */
    public List<TimePeriod> timedPeriods() {
        Object raw = map.get("timed");
        if (!(raw instanceof List)) return Collections.emptyList();
        List<TimePeriod> result = new ArrayList<>();
        for (Object item : (List<?>) raw) {
            TimePeriod p = TimePeriod.fromString(Objects.toString(item, ""));
            if (p != null) result.add(p);
        }
        return result;
    }

    @Override
    public UpdateAgent.FilterResult filter(UUID uuid) {
        if (ignorePermissions.isEmpty() && resetPermissions.isEmpty()) {
            return UpdateAgent.FilterResult.CONTINUE;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return UpdateAgent.FilterResult.SKIP;
        }
        if (!resetPermissions.isEmpty() && resetPermissions.stream().anyMatch(player::hasPermission)) {
            return UpdateAgent.FilterResult.RESET;
        }
        if (!ignorePermissions.isEmpty() && ignorePermissions.stream().anyMatch(player::hasPermission)) {
            return UpdateAgent.FilterResult.SKIP;
        }
        return UpdateAgent.FilterResult.CONTINUE;
    }
}
