package me.hsgamer.topper.spigot.plugin.timed;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * Defines the supported timed leaderboard reset periods.
 */
public enum TimePeriod {

    WEEKLY {
        @Override
        public ZonedDateTime nextReset(ZonedDateTime now) {
            return now.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                    .truncatedTo(ChronoUnit.DAYS);
        }

        @Override
        public ZonedDateTime currentPeriodStart(ZonedDateTime now) {
            return now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .truncatedTo(ChronoUnit.DAYS);
        }

        @Override
        public String id() { return "weekly"; }
    },

    MONTHLY {
        @Override
        public ZonedDateTime nextReset(ZonedDateTime now) {
            return now.with(TemporalAdjusters.firstDayOfNextMonth())
                    .truncatedTo(ChronoUnit.DAYS);
        }

        @Override
        public ZonedDateTime currentPeriodStart(ZonedDateTime now) {
            return now.with(TemporalAdjusters.firstDayOfMonth())
                    .truncatedTo(ChronoUnit.DAYS);
        }

        @Override
        public String id() { return "monthly"; }
    };

    public abstract ZonedDateTime nextReset(ZonedDateTime now);
    public abstract ZonedDateTime currentPeriodStart(ZonedDateTime now);
    public abstract String id();

    public long millisUntilReset(ZonedDateTime now) {
        return java.time.Duration.between(now, nextReset(now)).toMillis();
    }

    public static TimePeriod fromString(String s) {
        if (s == null) return null;
        switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "weekly":  return WEEKLY;
            case "monthly": return MONTHLY;
            default:        return null;
        }
    }
}
