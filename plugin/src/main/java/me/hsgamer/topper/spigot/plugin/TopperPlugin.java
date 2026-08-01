package me.hsgamer.topper.spigot.plugin;

import me.hsgamer.hscore.bukkit.config.BukkitConfig;
import me.hsgamer.hscore.bukkit.utils.MessageUtils;
import me.hsgamer.hscore.checker.spigotmc.SpigotVersionChecker;
import me.hsgamer.hscore.config.proxy.ConfigGenerator;
import me.hsgamer.hscore.database.Setting;
import me.hsgamer.hscore.database.client.sql.SqlClient;
import me.hsgamer.hscore.database.client.sql.java.JavaSqlClient;
import me.hsgamer.topper.spigot.plugin.base.Loadable;
import me.hsgamer.topper.spigot.plugin.command.GetTopListCommand;
import me.hsgamer.topper.spigot.plugin.command.ReloadCommand;
import me.hsgamer.topper.spigot.plugin.config.MainConfig;
import me.hsgamer.topper.spigot.plugin.config.MessageConfig;
import me.hsgamer.topper.spigot.plugin.hook.HookSystem;
import me.hsgamer.topper.spigot.plugin.listener.JoinListener;
import me.hsgamer.topper.spigot.plugin.manager.MetricsManager;
import me.hsgamer.topper.spigot.plugin.manager.PermissionCheckManager;
import me.hsgamer.topper.spigot.plugin.manager.ValueProviderManager;
import me.hsgamer.topper.spigot.plugin.template.SpigotTopTemplate;
import me.hsgamer.topper.spigot.template.storagesupplier.SpigotStorageSupplierTemplate;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.*;
import java.util.logging.Level;

public class TopperPlugin extends JavaPlugin {
    private final Map<Class<?>, Object> componentMap = new LinkedHashMap<>();
    private final List<Object> componentList = new ArrayList<>();

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> clazz) {
        Object obj = componentMap.get(clazz);
        if (obj == null) {
            for (Object component : componentList) {
                if (clazz.isInstance(component)) {
                    componentMap.put(clazz, component);
                    return (T) component;
                }
            }
            throw new IllegalStateException("Component not found: " + clazz.getName());
        }
        return (T) obj;
    }

    private List<Object> getComponents() {
        return Arrays.asList(
                ConfigGenerator.newInstance(MainConfig.class, new BukkitConfig(this)),
                ConfigGenerator.newInstance(MessageConfig.class, new BukkitConfig(this, "messages.yml")),

                new ValueProviderManager(),
                new PermissionCheckManager(),

                new HookSystem(this),

                new SpigotStorageSupplierTemplate() {
                    @Override
                    public SqlClient<?> getSqlClient(Setting setting) {
                        return new JavaSqlClient(setting);
                    }
                },
                new SpigotTopTemplate(this),

                new Permissions(this),
                new JoinListener(this),

                new MetricsManager(this)
        );
    }

    @Override
    public void onLoad() {
        componentList.addAll(getComponents());

        // Register commands
        try {
            GetTopListCommand getTopListCommand = new GetTopListCommand(this);
            ReloadCommand reloadCommand = new ReloadCommand(this);
            getCommandMap().register(getDescription().getName().toLowerCase(), getTopListCommand);
            getCommandMap().register(getDescription().getName().toLowerCase(), reloadCommand);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to register commands", e);
        }

        // Load phase
        for (Object component : componentList) {
            if (component instanceof Loadable) {
                ((Loadable) component).load();
            }
        }

        MessageUtils.setPrefix(get(MessageConfig.class)::getPrefix);
        get(SpigotTopTemplate.class).getNameProviderManager().setDefaultNameProvider(uuid -> Bukkit.getOfflinePlayer(uuid).getName());
    }

    @Override
    public void onEnable() {
        // Enable phase
        for (Object component : componentList) {
            if (component instanceof Loadable) {
                ((Loadable) component).enable();
            }
            if (component instanceof Listener) {
                Bukkit.getPluginManager().registerEvents((Listener) component, this);
            }
        }

        // Register permissions
        for (Object component : componentList) {
            if (component instanceof Permissions) {
                Permissions perms = (Permissions) component;
                for (Permission permission : perms.getPermissions()) {
                    try {
                        Bukkit.getPluginManager().addPermission(permission);
                    } catch (Exception ignored) {
                        // Permission may already be registered
                    }
                }
            }
        }

        if (getDescription().getVersion().contains("SNAPSHOT")) {
            getLogger().warning("You are using the development version");
            getLogger().warning("This is not ready for production");
            getLogger().warning("Use in your own risk");
        } else {
            new SpigotVersionChecker(101325).getVersion().whenComplete((output, throwable) -> {
                if (throwable != null) {
                    getLogger().log(Level.WARNING, "Failed to check spigot version", throwable);
                } else if (output != null) {
                    if (this.getDescription().getVersion().equalsIgnoreCase(output)) {
                        getLogger().info("You are using the latest version");
                    } else {
                        getLogger().warning("There is an available update");
                        getLogger().warning("New Version: " + output);
                    }
                }
            });
        }
    }

    @Override
    public void onDisable() {
        // Disable in reverse order
        List<Object> reversed = new ArrayList<>(componentList);
        Collections.reverse(reversed);
        for (Object component : reversed) {
            if (component instanceof Listener) {
                HandlerList.unregisterAll((Listener) component);
            }
            if (component instanceof Loadable) {
                ((Loadable) component).disable();
            }
        }
        componentList.clear();
        componentMap.clear();
    }

    private org.bukkit.command.CommandMap getCommandMap() throws Exception {
        Field commandMapField = Bukkit.getServer().getClass().getDeclaredField("commandMap");
        commandMapField.setAccessible(true);
        return (org.bukkit.command.CommandMap) commandMapField.get(Bukkit.getServer());
    }
}
