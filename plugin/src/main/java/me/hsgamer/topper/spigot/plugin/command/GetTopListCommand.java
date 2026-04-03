package me.hsgamer.topper.spigot.plugin.command;

import me.hsgamer.hscore.bukkit.utils.MessageUtils;
import me.hsgamer.topper.agent.snapshot.SnapshotAgent;
import me.hsgamer.topper.query.simple.SimpleQueryDisplay;
import me.hsgamer.topper.spigot.plugin.Permissions;
import me.hsgamer.topper.spigot.plugin.TopperPlugin;
import me.hsgamer.topper.spigot.plugin.config.MessageConfig;
import me.hsgamer.topper.spigot.plugin.template.SpigotTopDisplayLine;
import me.hsgamer.topper.spigot.plugin.template.SpigotTopHolderSettings;
import me.hsgamer.topper.spigot.plugin.template.SpigotTopTemplate;
import me.hsgamer.topper.spigot.plugin.timed.TimedAgent;
import me.hsgamer.topper.spigot.plugin.timed.TimePeriod;
import me.hsgamer.topper.template.snapshotdisplayline.SnapshotDisplayLine;
import me.hsgamer.topper.template.topplayernumber.holder.NumberTopHolder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static me.hsgamer.hscore.bukkit.utils.MessageUtils.sendMessage;

public class GetTopListCommand extends Command {
    private final TopperPlugin instance;

    public GetTopListCommand(TopperPlugin instance) {
        super("gettoplist", "Get Top List",
                "/gettop <holder> [from] [to] [alltime|weekly|monthly]",
                Arrays.asList("toplist", "gettop"));
        this.instance = instance;
        setPermission(Permissions.TOP.getName());
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!testPermission(sender)) return false;
        if (args.length < 1) {
            sendMessage(sender, "&c" + getUsage());
            return false;
        }

        Optional<NumberTopHolder> optional = instance.get(SpigotTopTemplate.class)
                .getTopManager().getHolder(args[0]);
        if (!optional.isPresent()) {
            MessageUtils.sendMessage(sender, instance.get(MessageConfig.class).getTopHolderNotFound());
            return false;
        }
        NumberTopHolder topHolder = optional.get();

        int fromIndex = 1;
        int toIndex   = 10;
        TimePeriod period = null; // null = alltime

        // Parse numeric args then optional period string
        List<String> numArgs    = new ArrayList<>();
        String       periodArg  = null;
        for (int i = 1; i < args.length; i++) {
            TimePeriod p = TimePeriod.fromString(args[i]);
            if (p != null) { periodArg = args[i]; period = p; }
            else            numArgs.add(args[i]);
        }

        try {
            if (numArgs.size() == 1) {
                toIndex = Integer.parseInt(numArgs.get(0));
            } else if (numArgs.size() >= 2) {
                fromIndex = Integer.parseInt(numArgs.get(0));
                toIndex   = Integer.parseInt(numArgs.get(1));
            }
        } catch (NumberFormatException e) {
            sendMessage(sender, instance.get(MessageConfig.class).getNumberRequired());
            return false;
        }

        if (fromIndex >= toIndex) {
            sendMessage(sender, instance.get(MessageConfig.class).getIllegalFromToIndex());
            return false;
        }

        // Build the display line source (alltime or timed)
        SnapshotDisplayLine<UUID, Double> displayLine = buildDisplayLine(topHolder, period);
        if (displayLine == null) {
            sendMessage(sender, "&cTimed period '" + periodArg + "' is not enabled for this holder.");
            return false;
        }

        final SnapshotDisplayLine<UUID, Double> dl = displayLine;
        List<String> topList = IntStream.rangeClosed(fromIndex, toIndex)
                .mapToObj(dl::display)
                .collect(Collectors.toList());

        if (topList.isEmpty()) {
            sendMessage(sender, instance.get(MessageConfig.class).getTopEmpty());
        } else {
            topList.forEach(s -> sendMessage(sender, s));
        }
        return true;
    }

    /**
     * Returns a SnapshotDisplayLine backed by the alltime or timed snapshot.
     * Returns null if the requested period is not enabled for this holder.
     */
    private SnapshotDisplayLine<UUID, Double> buildDisplayLine(NumberTopHolder topHolder, TimePeriod period) {
        if (period == null) {
            // alltime — original behaviour
            return new SpigotTopDisplayLine(topHolder);
        }

        Optional<TimedAgent<UUID>> optAgent = instance.get(SpigotTopTemplate.class)
                .getTimedAgent(topHolder.getName(), period);
        if (!optAgent.isPresent()) return null;

        TimedAgent<UUID> agent = optAgent.get();
        SimpleQueryDisplay<UUID, Double> display = topHolder.getValueDisplay();

        return new SnapshotDisplayLine<UUID, Double>() {
            @Override
            public SimpleQueryDisplay<UUID, Double> getDisplay() { return display; }

            @Override
            public SnapshotAgent<UUID, Double> getSnapshotAgent() {
                return agent.getSnapshotAgent();
            }

            @Override
            public String getDisplayLine() {
                return Optional.of(topHolder.getSettings())
                        .filter(SpigotTopHolderSettings.class::isInstance)
                        .map(SpigotTopHolderSettings.class::cast)
                        .map(SpigotTopHolderSettings::defaultLine)
                        .orElse("&7[&b{index}&7] &b{name} &7- &b{value}");
            }
        };
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (args.length == 1) {
            return instance.get(SpigotTopTemplate.class).getTopManager().getHolderNames().stream()
                    .filter(n -> args[0].isEmpty() || n.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        } else if (args.length == 2 || args.length == 3) {
            return Arrays.asList("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        } else if (args.length == 4) {
            return Arrays.asList("alltime", "weekly", "monthly");
        }
        return Collections.emptyList();
    }
}
