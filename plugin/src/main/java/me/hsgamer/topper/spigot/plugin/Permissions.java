package me.hsgamer.topper.spigot.plugin;

import me.hsgamer.topper.spigot.plugin.base.Loadable;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.Arrays;
import java.util.List;

public final class Permissions implements Loadable {
    public static final Permission TOP = new Permission("topper.top", PermissionDefault.OP);
    public static final Permission RELOAD = new Permission("topper.reload", PermissionDefault.OP);

    private final TopperPlugin plugin;

    public Permissions(TopperPlugin plugin) {
        this.plugin = plugin;
    }

    public List<Permission> getPermissions() {
        return Arrays.asList(TOP, RELOAD);
    }
}
