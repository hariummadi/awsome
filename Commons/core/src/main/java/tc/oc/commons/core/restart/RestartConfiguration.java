package tc.oc.commons.core.restart;

import javax.annotation.Nullable;
import javax.inject.Inject;

import java.time.Duration;
import tc.oc.commons.core.configuration.ConfigUtils;
import tc.oc.commons.core.exception.ExceptionHandler;
import tc.oc.minecraft.api.configuration.Configuration;
import tc.oc.minecraft.api.configuration.ConfigurationSection;

import static com.google.common.base.Preconditions.checkNotNull;

public class RestartConfiguration {

    private final ConfigurationSection config;
    private final ExceptionHandler<Throwable> exceptionHandler;

    @Inject RestartConfiguration(Configuration config, ExceptionHandler<Throwable> exceptionHandler) {
        this.config = checkNotNull(config.getSection("restart"));
        this.exceptionHandler = exceptionHandler;
    }

    public java.time.Duration interval() {
        return exceptionHandler.flatGet(() -> config.duration("interval"))
                               .orElse(java.time.Duration.ofMinutes(1));
    }

    public @Nullable Duration uptimeLimit() {
        return ConfigUtils.getDuration(config, "uptime");
    }

    public long memoryLimit() {
        return config.getLong("memory", 0) * 1024 * 1024; // Megabytes
    }

    /**
     * Maximum time a restart can be deferred after it is requested
     */
    public @Nullable Duration deferTimeout() {
        return ConfigUtils.getDuration(config, "defer-timeout", null);
    }

    /**
     * Maximum time restart can be delayed after new player connections have been blocked (Bungee only)
     */
    public @Nullable Duration emptyTimeout() {
        return ConfigUtils.getDuration(config, "empty-timeout", null);
    }

    /**
     * Maximum number of players that can be disconnected in order to restart the server.
     * This takes priority over empty-timeout.
     */
    public int kickLimit() {
        return config.getInt("kick-limit", Integer.MAX_VALUE);
    }
}
