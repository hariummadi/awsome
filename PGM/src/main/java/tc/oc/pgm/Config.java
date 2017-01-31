package tc.oc.pgm;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import java.time.Duration;
import tc.oc.commons.bukkit.configuration.ConfigUtils;
import tc.oc.commons.core.util.TimeUtils;
import tc.oc.pgm.events.ConfigLoadEvent;

public class Config {
    public static Configuration getConfiguration() {
        PGM pgm = PGM.get();
        if(pgm != null) {
            return pgm.getConfig();
        } else {
            return new YamlConfiguration();
        }
    }

    public static int minimumPlayers() {
        return getConfiguration().getInt("minimum-players", 1);
    }

    public static class Broadcast {
        public static boolean title() {
            return getConfiguration().getBoolean("broadcast.title", true);
        }

        public static boolean periodic() {
            return getConfiguration().getBoolean("broadcast.periodic", false);
        }

        public static int /* seconds */ frequency() {
            int seconds = getConfiguration().getInt("broadcast.frequency", 600);
            if(seconds > 0) {
                return seconds;
            } else {
                return 600;
            }
        }
    }

    public static class ArrowRemoval {
        public static boolean enabled() {
            return getConfiguration().getBoolean("arrowremoval.enabled", true);
        }

        public static int /* seconds */ delay() {
            int seconds = getConfiguration().getInt("arrowremoval.delay", 10);
            if(seconds > 0) {
                return seconds;
            } else {
                return 10;
            }
        }
    }

    public static class Fishing {
        public static boolean disableTreasure() {
            return getConfiguration().getBoolean("fishing.disable-treasure", true);
        }
    }

    public static class Scoreboard {
        public static boolean showProximity() {
            return getConfiguration().getBoolean("scoreboard.show-proximity", false);
        }

        public static boolean preciseProgress() {
            return getConfiguration().getBoolean("scoreboard.precise-progress", false);
        }
    }

    public static class PlayerList implements Listener {
        private boolean enabled;
        private boolean playersSeeObservers;
        private @Nullable String datacenter;
        private @Nullable String server;

        @EventHandler
        public void onConfigLoad(ConfigLoadEvent event) throws InvalidConfigurationException {
            this.load(event.getConfig());
        }

        public void load(Configuration config) throws InvalidConfigurationException {
            this.enabled = config.getBoolean("player-list.enabled", true);
            this.playersSeeObservers = config.getBoolean("player-list.players-see-observers", true);
            this.datacenter = config.getString("player-list.datacenter", null);
            this.server = config.getString("player-list.server", null);
        }

        private static final PlayerList instance = new PlayerList();

        public static void register() {
            Bukkit.getPluginManager().registerEvents(instance, PGM.get());
        }

        public static boolean enabled() {
            return instance.enabled;
        }

        public static boolean playersSeeObservers() {
            return instance.playersSeeObservers;
        }

        public static @Nullable String datacenter() {
            return instance.datacenter;
        }

        public static @Nullable String server() {
            return instance.server;
        }
    }

    public static abstract class Wool {
        public static boolean autoRefillWoolChests() {
            return getConfiguration().getBoolean("wool.auto-refill", true);
        }
    }

    public static class PlayerReports {
        public static boolean enabled() {
            return getConfiguration().getBoolean("player-reports.enabled", false);
        }
    }

    public static class Stats {
        public static class Engagements {
            public static boolean enabled() {
                return getConfiguration().getBoolean("stats.engagements.enabled", false);
            }

            public static Duration maxContinuousAbsence() {
                return ConfigUtils.getDuration(getConfiguration(), "stats.engagements.max-continuous-absence", TimeUtils.INF_POSITIVE);
            }

            public static Duration maxCumulativeAbsence() {
                return ConfigUtils.getDuration(getConfiguration(), "stats.engagements.max-cumulative-absence", TimeUtils.INF_POSITIVE);
            }

            public static double minParticipationPercent() {
                return ConfigUtils.getPercentage(getConfiguration(), "stats.engagements.min-participation-percent", 0);
            }
        }
    }

    public static class Mutations {
        public static boolean enabled() {
            return getConfiguration().getBoolean("mutations.enabled", true);
        }
    }
}
