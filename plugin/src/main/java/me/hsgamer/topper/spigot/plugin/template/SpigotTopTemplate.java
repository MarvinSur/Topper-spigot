package me.hsgamer.topper.spigot.plugin.template;

import io.github.projectunified.minelib.plugin.base.Loadable;
import io.github.projectunified.minelib.scheduler.async.AsyncScheduler;
import io.github.projectunified.minelib.scheduler.global.GlobalScheduler;
import me.hsgamer.topper.agent.core.Agent;
import me.hsgamer.topper.agent.core.DataEntryAgent;
import me.hsgamer.topper.agent.snapshot.SnapshotAgent;
import me.hsgamer.topper.query.core.QueryResult;
import me.hsgamer.topper.query.forward.QueryForwardContext;
import me.hsgamer.topper.query.simple.SimpleQueryDisplay;
import me.hsgamer.topper.spigot.agent.runnable.SpigotRunnableAgent;
import me.hsgamer.topper.spigot.plugin.TopperPlugin;
import me.hsgamer.topper.spigot.plugin.config.MainConfig;
import me.hsgamer.topper.spigot.plugin.config.MessageConfig;
import me.hsgamer.topper.spigot.plugin.event.GenericEntryUpdateEvent;
import me.hsgamer.topper.spigot.plugin.manager.ValueProviderManager;
import me.hsgamer.topper.spigot.plugin.timed.TimedAgent;
import me.hsgamer.topper.spigot.plugin.timed.TimePeriod;
import me.hsgamer.topper.spigot.query.forward.plugin.PluginContext;
import me.hsgamer.topper.storage.core.DataStorage;
import me.hsgamer.topper.storage.flat.converter.NumberFlatValueConverter;
import me.hsgamer.topper.storage.flat.converter.UUIDFlatValueConverter;
import me.hsgamer.topper.storage.sql.converter.NumberSqlValueConverter;
import me.hsgamer.topper.storage.sql.converter.UUIDSqlValueConverter;
import me.hsgamer.topper.template.storagesupplier.StorageSupplierTemplate;
import me.hsgamer.topper.template.topplayernumber.TopPlayerNumberTemplate;
import me.hsgamer.topper.template.topplayernumber.holder.NumberTopHolder;
import me.hsgamer.topper.template.topplayernumber.manager.ReloadManager;
import me.hsgamer.topper.value.core.ValueProvider;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;

public class SpigotTopTemplate extends TopPlayerNumberTemplate implements Loadable {
    private final TopperPlugin plugin;
    private final SpigotDataStorageSupplierSettings dataStorageSupplierSettings;

    // holderName -> (period -> TimedAgent)
    private final Map<String, Map<TimePeriod, TimedAgent<UUID>>> timedAgents = new ConcurrentHashMap<>();

    public SpigotTopTemplate(TopperPlugin plugin) {
        super(new Settings() {
            @Override
            public Map<String, NumberTopHolder.Settings> holders() {
                return plugin.get(MainConfig.class).getHolders();
            }
            @Override
            public int taskSaveEntryPerTick() {
                return plugin.get(MainConfig.class).getTaskSaveEntryPerTick();
            }
            @Override
            public int taskUpdateEntryPerTick() {
                return plugin.get(MainConfig.class).getTaskUpdateEntryPerTick();
            }
            @Override
            public int taskUpdateMaxSkips() {
                return plugin.get(MainConfig.class).getTaskUpdateMaxSkips();
            }
        });
        this.plugin = plugin;
        this.dataStorageSupplierSettings = new SpigotDataStorageSupplierSettings(plugin);
    }

    // ── Storage suppliers ─────────────────────────────────────────────────────

    private Function<String, DataStorage<UUID, Double>> buildStorageSupplier() {
        return plugin.get(StorageSupplierTemplate.class)
                .getDataStorageSupplier(dataStorageSupplierSettings)
                .getStorageSupplier(
                        new UUIDFlatValueConverter(),
                        new NumberFlatValueConverter<>(Number::doubleValue),
                        new UUIDSqlValueConverter("uuid"),
                        new NumberSqlValueConverter<>("value", true, Number::doubleValue)
                );
    }

    @Override
    public Function<String, DataStorage<UUID, Double>> getStorageSupplier() {
        return buildStorageSupplier();
    }

    @Override
    public Optional<ValueProvider<UUID, Double>> createValueProvider(Map<String, Object> settings) {
        return plugin.get(ValueProviderManager.class).build(settings);
    }

    // ── Task factory ──────────────────────────────────────────────────────────

    private Agent createTask(Runnable runnable, boolean async, long delay) {
        return new SpigotRunnableAgent(
                runnable,
                async ? AsyncScheduler.get(plugin) : GlobalScheduler.get(plugin),
                delay
        );
    }

    @Override
    public Agent createTask(Runnable runnable, NumberTopHolder.TaskType taskType, Map<String, Object> settings) {
        MainConfig mainConfig = plugin.get(MainConfig.class);
        switch (taskType) {
            case SET:
                return createTask(runnable, true, mainConfig.getTaskUpdateSetDelay());
            case STORAGE:
                return createTask(runnable, true, mainConfig.getTaskSaveDelay());
            case UPDATE: {
                boolean async = Optional.ofNullable(settings.get("async"))
                        .map(Object::toString).map(String::toLowerCase).map(Boolean::parseBoolean)
                        .orElse(false);
                return createTask(runnable, async, mainConfig.getTaskUpdateDelay());
            }
            default:
                return createTask(runnable, true, 20L);
        }
    }

    @Override
    public void logWarning(String message, @Nullable Throwable throwable) {
        plugin.getLogger().log(Level.WARNING, message, throwable);
    }

    // ── modifyAgents: wire timed agents + load players ────────────────────────

    @Override
    public void modifyAgents(NumberTopHolder holder, List<Agent> agents, List<DataEntryAgent<UUID, Double>> entryAgents) {
        // Load players on start
        agents.add(new Agent() {
            @Override
            public void start() {
                if (plugin.get(MainConfig.class).isLoadAllOfflinePlayers()) {
                    GlobalScheduler.get(plugin).run(() -> {
                        for (OfflinePlayer player : plugin.getServer().getOfflinePlayers())
                            holder.getOrCreateEntry(player.getUniqueId());
                    });
                } else {
                    GlobalScheduler.get(plugin).run(() -> {
                        for (Player player : plugin.getServer().getOnlinePlayers())
                            holder.getOrCreateEntry(player.getUniqueId());
                    });
                }
            }
        });

        // Wire timed agents if this holder has timed periods configured
        NumberTopHolder.Settings rawSettings = holder.getSettings();
        if (!(rawSettings instanceof SpigotTopHolderSettings)) return;
        SpigotTopHolderSettings settings = (SpigotTopHolderSettings) rawSettings;
        List<TimePeriod> periods = settings.timedPeriods();
        if (periods.isEmpty()) return;

        Function<String, DataStorage<UUID, Double>> storageSupplier = buildStorageSupplier();
        boolean reverse = settings.reverse();
        Comparator<Double> comparator = reverse ? Comparator.naturalOrder() : Comparator.reverseOrder();

        Map<TimePeriod, TimedAgent<UUID>> periodMap = new EnumMap<>(TimePeriod.class);

        for (TimePeriod period : periods) {
            // Storage key e.g. "money_weekly", "money_monthly"
            String storageName = holder.getName() + "_" + period.id();
            DataStorage<UUID, Double> timedStorage = storageSupplier.apply(storageName);

            TimedAgent<UUID> agent = new TimedAgent<>(period, timedStorage, comparator);
            agent.setMaxEntryPerCall(plugin.get(MainConfig.class).getTaskSaveEntryPerTick());

            // Periodic tick for this agent (async, every 20 ticks)
            agents.add(agent);
            agents.add(createTask(agent, true, 20L));

            // Listen to main holder updates to accumulate deltas
            entryAgents.add(agent);

            periodMap.put(period, agent);
        }

        timedAgents.put(holder.getName(), periodMap);
    }

    // ── Timed agent accessors ─────────────────────────────────────────────────

    public Optional<TimedAgent<UUID>> getTimedAgent(String holderName, TimePeriod period) {
        Map<TimePeriod, TimedAgent<UUID>> map = timedAgents.get(holderName);
        if (map == null) return Optional.empty();
        return Optional.ofNullable(map.get(period));
    }

    // ── enable / disable ──────────────────────────────────────────────────────

    @Override
    public void enable() {
        super.enable();

        // Timed PAPI placeholders: %topper_money_weekly;top_name;1%
        // Registered here so they are available after holders are loaded
        for (Map.Entry<String, Map<TimePeriod, TimedAgent<UUID>>> holderEntry : timedAgents.entrySet()) {
            String holderName = holderEntry.getKey();
            for (Map.Entry<TimePeriod, TimedAgent<UUID>> periodEntry : holderEntry.getValue().entrySet()) {
                TimePeriod period = periodEntry.getKey();
                TimedAgent<UUID> agent = periodEntry.getValue();
                registerTimedQueryContext(holderName, period, agent);
            }
        }

        getEntryConsumeManager().addConsumer(context ->
                AsyncScheduler.get(plugin).run(() ->
                        Bukkit.getPluginManager().callEvent(new GenericEntryUpdateEvent(
                                context.group, context.holder, context.uuid,
                                context.oldValue, context.value, true))
                )
        );

        getReloadManager().add(new ReloadManager.ReloadEntry() {
            @Override
            public void reload() {
                plugin.get(MainConfig.class).reloadConfig();
                plugin.get(MessageConfig.class).reloadConfig();
            }
        });
    }

    @Override
    public void disable() {
        super.disable();
        timedAgents.clear();
    }

    // ── Timed query context registration ─────────────────────────────────────
    // Registers a PAPI expansion named "topper_money_weekly" etc.
    // Supports same actions as alltime: top_name, top_value, top_rank, top_size, value, rank

    private void registerTimedQueryContext(String holderName, TimePeriod period, TimedAgent<UUID> agent) {
        String contextName = "topper_" + holderName + "_" + period.id();

        Optional<NumberTopHolder> optHolder = getTopManager().getHolder(holderName);
        if (!optHolder.isPresent()) return;
        SimpleQueryDisplay<UUID, Double> display = optHolder.get().getValueDisplay();
        SnapshotAgent<UUID, Double> snap = agent.getSnapshotAgent();

        addQueryForwardContext(plugin, contextName, (uuid, params) -> {
            // params format mirrors alltime: "top_name;1", "top_value;3", "rank", "value" etc.
            String[] split = params.split(";", 3);
            String action = split[0].toLowerCase(java.util.Locale.ROOT);

            switch (action) {
                case "top_name": {
                    int idx = parseIndex(split, 1, 1) - 1;
                    UUID key = snap.getSnapshotByIndex(idx).map(Map.Entry::getKey).orElse(null);
                    return QueryResult.handled(display.getDisplayName(key));
                }
                case "top_key": {
                    int idx = parseIndex(split, 1, 1) - 1;
                    UUID key = snap.getSnapshotByIndex(idx).map(Map.Entry::getKey).orElse(null);
                    return QueryResult.handled(display.getDisplayKey(key));
                }
                case "top_value": {
                    int idx = parseIndex(split, 1, 1) - 1;
                    String fmt = split.length > 2 ? split[2] : (split.length > 1 && !isInt(split[1]) ? split[1] : "");
                    Double val = snap.getSnapshotByIndex(idx).map(Map.Entry::getValue).orElse(null);
                    return QueryResult.handled(display.getDisplayValue(val, fmt));
                }
                case "top_value_raw": {
                    int idx = parseIndex(split, 1, 1) - 1;
                    Double val = snap.getSnapshotByIndex(idx).map(Map.Entry::getValue).orElse(null);
                    return QueryResult.handled(display.getDisplayValue(val, "raw"));
                }
                case "top_rank": {
                    if (uuid == null) return QueryResult.handled("0");
                    int rank = snap.getSnapshotIndex(uuid);
                    String fmt = split.length > 1 ? split[1] : "";
                    return QueryResult.handled(rank < 0 ? "0" : display.getDisplayValue((double)(rank + 1), fmt));
                }
                case "top_size":
                    return QueryResult.handled(String.valueOf(snap.getSnapshot().size()));
                case "value": {
                    String fmt = split.length > 1 ? split[1] : "";
                    Double val = uuid == null ? null : agent.getHolder().getEntry(uuid)
                            .map(me.hsgamer.topper.data.core.DataEntry::getValue).orElse(null);
                    return QueryResult.handled(display.getDisplayValue(val, fmt));
                }
                case "value_raw": {
                    Double val = uuid == null ? null : agent.getHolder().getEntry(uuid)
                            .map(me.hsgamer.topper.data.core.DataEntry::getValue).orElse(null);
                    return QueryResult.handled(display.getDisplayValue(val, "raw"));
                }
                case "rank": {
                    if (uuid == null) return QueryResult.handled("0");
                    int rank = snap.getSnapshotIndex(uuid);
                    String fmt = split.length > 1 ? split[1] : "";
                    return QueryResult.handled(rank < 0 ? "0" : display.getDisplayValue((double)(rank + 1), fmt));
                }
                default:
                    return QueryResult.notHandled();
            }
        });
    }

    private static int parseIndex(String[] split, int pos, int def) {
        if (split.length <= pos) return def;
        try { return Integer.parseInt(split[pos]); } catch (NumberFormatException e) { return def; }
    }

    private static boolean isInt(String s) {
        try { Integer.parseInt(s); return true; } catch (NumberFormatException e) { return false; }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void addQueryForwardContext(Plugin plugin, String name, BiFunction<@Nullable UUID, @NotNull String, @NotNull QueryResult> query) {
        getQueryForwardManager().addContext(new PluginTopQueryForwardContext() {
            @Override public String getName() { return name; }
            @Override public BiFunction<@Nullable UUID, @NotNull String, @NotNull QueryResult> getQuery() { return query; }
            @Override public Plugin getPlugin() { return plugin; }
        });
    }

    private interface PluginTopQueryForwardContext extends QueryForwardContext<UUID>, PluginContext {}
}
