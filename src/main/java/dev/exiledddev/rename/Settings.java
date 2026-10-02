package dev.exiledddev.rename;

import dev.exiledddev.rename.nick.NickStyle;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * A snapshot of config.yml (storage settings are read once, at startup).
 */
public record Settings(NickStyle defaultStyle, boolean skinByDefault, int skinAttempts, boolean skinFallback) {

    public static Settings load(final FileConfiguration config, final Logger logger) {
        final String rawStyle = config.getString("default-style", "generic");
        NickStyle style = NickStyle.parse(rawStyle);
        if (style == null) {
            logger.warning("Unknown default-style '" + rawStyle + "' in config.yml, using generic.");
            style = NickStyle.GENERIC;
        }
        return new Settings(
            style,
            config.getBoolean("skins.random-by-default", false),
            Math.clamp(config.getInt("skins.random-account-attempts", 6), 0, 20),
            config.getBoolean("skins.fallback-to-server-skins", true)
        );
    }
}
