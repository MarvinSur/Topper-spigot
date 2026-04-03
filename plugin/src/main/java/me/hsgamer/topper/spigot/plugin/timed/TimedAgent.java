package me.hsgamer.topper.spigot.plugin.timed;

import me.hsgamer.hscore.logger.common.LogLevel;
import me.hsgamer.hscore.logger.common.Logger;
import me.hsgamer.hscore.logger.provider.LoggerProvider;
import me.hsgamer.topper.agent.core.Agent;
import me.hsgamer.topper.agent.core.DataEntryAgent;
import me.hsgamer.topper.agent.snapshot.SnapshotHolderAgent;
import me.hsgamer.topper.data.core.DataEntry;
import me.hsgamer.topper.storage.core.DataStorage;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core engine for one timed leaderboard period (WEEKLY or MONTHLY).
 *
 * Accumulates positive value deltas from the main holder, persists them to a
 * dedicated DataStorage, auto-resets when the period boundary is crossed, and
 * maintains a ranked snapshot for queries / commands.
 */
public class TimedAgent<K> implements Agent, DataEntryAgent<K, Double>, Runnable {

    private static final Logger LOGGER = LoggerProvider.getLogger(TimedAgent.class);

    private final TimePeriod period;
    private final TimedDataHolder<K> holder;
    private final DataStorage<K, Double> storage;
    private final SnapshotHolderAgent<K, Double> snapshot;

    private final AtomicReference<Map<K, Double>> storeMap    = new AtomicReference<>(new ConcurrentHashMap<>());
    private final Queue<Map.Entry<K, Double>>     saveQueue   = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Map<K, Double>> savingMap   = new AtomicReference<>();
    private final AtomicBoolean                   saving      = new AtomicBoolean(false);
    private final AtomicLong                      nextResetMs = new AtomicLong(0);

    private int    maxEntryPerCall = 10;
    private ZoneId zoneId         = ZoneId.systemDefault();

    public TimedAgent(TimePeriod period, DataStorage<K, Double> storage, Comparator<Double> comparator) {
        this.period   = period;
        this.storage  = storage;
        this.holder   = new TimedDataHolder<>();
        this.snapshot = new SnapshotHolderAgent<>(holder);
        this.snapshot.setComparator(comparator);
        this.snapshot.setDataFilter(e -> e.getValue() != null && e.getValue() > 0);
    }

    // ── Agent lifecycle ───────────────────────────────────────────────────────

    @Override
    public void start() {
        storage.onRegister();
        try {
            storage.load().forEach((key, value) -> holder.getOrCreateEntry(key).setValue(value, false));
        } catch (Exception e) {
            LOGGER.log(LogLevel.ERROR, "Failed to load timed data [" + period.id() + "]", e);
        }
        scheduleNextReset();
        snapshot.start();
    }

    @Override
    public void stop() {
        snapshot.stop();
        storage.onUnregister();
    }

    @Override
    public void beforeStop() {
        persist(true);
    }

    // ── Periodic tick ─────────────────────────────────────────────────────────

    @Override
    public void run() {
        // checkReset FIRST: clears holder and queues before snapshot reads them
        checkReset();
        persist(false);
        // snapshot.run LAST: reads clean holder state after any reset
        snapshot.run();
    }

    // ── Delta accumulation ────────────────────────────────────────────────────

    @Override
    public void onUpdate(DataEntry<K, Double> entry, Double oldValue, Double newValue) {
        if (newValue == null) return;
        double delta = newValue - (oldValue != null ? oldValue : 0.0);
        if (delta <= 0) return;

        K key = entry.getKey();
        double current = holder.getEntry(key)
                .map(DataEntry::getValue)
                .map(v -> v != null ? v : 0.0)
                .orElse(0.0);
        double updated = current + delta;

        holder.getOrCreateEntry(key).setValue(updated);
        // Capture reference BEFORE potential swap in persist() to avoid losing the update
        storeMap.get().merge(key, updated, Double::max);
    }

    @Override
    public void onCreate(DataEntry<K, Double> entry) {
        holder.getOrCreateEntry(entry.getKey());
    }

    @Override
    public void onRemove(DataEntry<K, Double> entry) {
        // keep timed score even when player goes offline
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    private void scheduleNextReset() {
        nextResetMs.set(period.nextReset(ZonedDateTime.now(zoneId)).toInstant().toEpochMilli());
    }

    private void checkReset() {
        if (System.currentTimeMillis() < nextResetMs.get()) return;
        scheduleNextReset(); // schedule FIRST so next tick doesn't double-reset

        // Clear queues to avoid writing stale data after wipe
        saveQueue.clear();
        storeMap.set(new ConcurrentHashMap<>());
        savingMap.set(null);

        // Wipe in-memory holder
        new ArrayList<>(holder.getEntryMap().keySet()).forEach(holder::removeEntry);

        // Wipe storage
        storage.modify().ifPresent(mod -> {
            try {
                Set<K> keys = new HashSet<>(storage.keys());
                if (!keys.isEmpty()) mod.remove(keys);
                mod.commit();
            } catch (Throwable t) {
                LOGGER.log(LogLevel.ERROR, "Failed to wipe timed storage [" + period.id() + "]", t);
                mod.rollback();
            }
        });
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void persist(boolean urgent) {
        if (saving.get() && !urgent) return;
        saving.set(true);

        storeMap.getAndSet(new ConcurrentHashMap<>())
                .forEach((k, v) -> saveQueue.add(new AbstractMap.SimpleEntry<>(k, v)));

        Map<K, Double> batch = savingMap.updateAndGet(old -> old == null ? new HashMap<>() : old);

        int count = 0;
        while (urgent || maxEntryPerCall <= 0 || count < maxEntryPerCall) {
            Map.Entry<K, Double> e = saveQueue.poll();
            if (e == null) break;
            batch.merge(e.getKey(), e.getValue(), Double::max);
            count++;
        }

        if (batch.isEmpty()) {
            savingMap.set(null);
            saving.set(false);
            return;
        }

        Optional<DataStorage.Modifier<K, Double>> optMod = storage.modify();
        if (!optMod.isPresent()) { saving.set(false); return; }

        DataStorage.Modifier<K, Double> mod = optMod.get();
        try {
            mod.save(new HashMap<>(batch));
            mod.commit();
            savingMap.set(null);
        } catch (Throwable t) {
            LOGGER.log(LogLevel.ERROR, "Failed to persist timed data [" + period.id() + "]", t);
            mod.rollback();
        } finally {
            saving.set(false);
        }
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public TimePeriod                    getPeriod()         { return period; }
    public TimedDataHolder<K>            getHolder()         { return holder; }
    public SnapshotHolderAgent<K, Double> getSnapshotAgent() { return snapshot; }
    public void setMaxEntryPerCall(int max)                  { this.maxEntryPerCall = max; }
    public void setZoneId(ZoneId z)                         { this.zoneId = z; scheduleNextReset(); }
}
