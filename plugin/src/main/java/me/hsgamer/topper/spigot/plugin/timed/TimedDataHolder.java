package me.hsgamer.topper.spigot.plugin.timed;

import me.hsgamer.topper.data.simple.SimpleDataHolder;
import org.jetbrains.annotations.Nullable;

/**
 * In-memory holder for accumulated timed scores for one period.
 * Generic over key type K so it works with UUID (and any other key).
 */
public class TimedDataHolder<K> extends SimpleDataHolder<K, Double> {

    @Override
    public @Nullable Double getDefaultValue() {
        return null; // null = no score yet this period
    }
}
