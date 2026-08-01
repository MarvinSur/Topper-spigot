package me.hsgamer.topper.spigot.plugin.base;

/**
 * Replacement for minelib's Loadable interface.
 * Provides a simple lifecycle: load -> enable -> disable.
 */
public interface Loadable {
    default void load() {
    }

    default void enable() {
    }

    default void disable() {
    }
}
