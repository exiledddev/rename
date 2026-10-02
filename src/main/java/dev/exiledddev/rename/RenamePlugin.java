package dev.exiledddev.rename;

import dev.exiledddev.rename.command.RenameCommand;
import dev.exiledddev.rename.command.UnrenameCommand;
import dev.exiledddev.rename.listener.NickListener;
import dev.exiledddev.rename.nick.NickService;
import dev.exiledddev.rename.nick.SkinService;
import dev.exiledddev.rename.nick.StyleSkins;
import dev.exiledddev.rename.store.Database;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.File;
import java.sql.SQLException;
import java.util.logging.Level;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class RenamePlugin extends JavaPlugin {

    private Settings settings;
    private Database database;
    private NickService nicks;
    private StyleSkins styleSkins;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.settings = Settings.load(this.getConfig(), this.getLogger());

        try {
            this.database = this.openDatabase(this.getConfig());
        } catch (final SQLException e) {
            this.getLogger().log(Level.SEVERE, "Could not open the nickname database; Rename is disabled.", e);
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }

        final SkinService skins = new SkinService(this,
            () -> this.settings.skinAttempts(), () -> this.settings.skinFallback(), () -> this.nicks.serverSkins());
        this.styleSkins = new StyleSkins(this, this.database);
        this.loadStyleSkins();
        this.nicks = new NickService(this.database, skins, this.styleSkins);
        this.nicks.load();
        final AutoNick autoNick = new AutoNick(this.database);

        // Players already online (e.g. after /reload) never went through pre-login.
        for (final Player player : this.getServer().getOnlinePlayers()) {
            this.nicks.remember(player);
            this.nicks.decorate(player);
        }

        this.getServer().getPluginManager().registerEvents(new NickListener(this.nicks, autoNick), this);
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();
            commands.register(new RenameCommand(this, this.nicks, autoNick).build(), "Give players random nicknames and skins");
            commands.register(new UnrenameCommand(this.nicks).build(), "Give players their real names back");
        });
        this.getLogger().info(this.nicks.activeNicks().size() + " active nickname(s) loaded.");
    }

    @Override
    public void onDisable() {
        if (this.database != null) {
            this.database.close();
        }
    }

    private Database openDatabase(final FileConfiguration config) throws SQLException {
        if ("mysql".equalsIgnoreCase(config.getString("storage.type", "sqlite"))) {
            return Database.mysql(
                config.getString("storage.mysql.host", "localhost"),
                config.getInt("storage.mysql.port", 3306),
                config.getString("storage.mysql.database", "rename"),
                config.getString("storage.mysql.username", "root"),
                config.getString("storage.mysql.password", ""),
                this.getLogger());
        }
        return Database.sqlite(new File(this.getDataFolder(), "rename.db"), this.getLogger());
    }

    public Settings settings() {
        return this.settings;
    }

    public void reloadSettings() {
        this.reloadConfig();
        this.settings = Settings.load(this.getConfig(), this.getLogger());
        this.loadStyleSkins();
    }

    private void loadStyleSkins() {
        this.styleSkins.load(this.getConfig().getConfigurationSection("style-skins"), this.getConfig().getString("skins.mineskin-api-key", ""));
    }
}
