package dev.exiledddev.rename.listener;

import com.destroystokyo.paper.profile.PlayerProfile;
import dev.exiledddev.rename.AutoNick;
import dev.exiledddev.rename.Permissions;
import dev.exiledddev.rename.nick.Nick;
import dev.exiledddev.rename.nick.NickService;
import dev.exiledddev.rename.nick.NickStyle;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;

/**
 * Keeps nicknames on across logins, scrambles obscured nicknames in broadcast messages, and runs
 * auto-nick sessions.
 */
public final class NickListener implements Listener {

    private final NickService nicks;
    private final AutoNick autoNick;

    public NickListener(final NickService nicks, final AutoNick autoNick) {
        this.nicks = nicks;
        this.autoNick = autoNick;
    }

    /** Nicked players log in with their nickname profile straight away, so nobody sees the real name. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        final PlayerProfile profile = this.nicks.loginProfile(event.getPlayerProfile());
        if (profile != null) {
            event.setPlayerProfile(profile);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        this.nicks.remember(player);
        this.nicks.decorate(player);
        event.joinMessage(this.scramble(event.joinMessage()));

        final NickStyle style = this.autoNick.style();
        if (style != null && this.nicks.nick(player.getUniqueId()) == null && !player.hasPermission(Permissions.EXEMPT)) {
            this.nicks.nickAll(List.of(player), style, this.autoNick.skin(), null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(final PlayerQuitEvent event) {
        event.quitMessage(this.scramble(event.quitMessage()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(final PlayerDeathEvent event) {
        event.deathMessage(this.scramble(event.deathMessage()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAdvancement(final PlayerAdvancementDoneEvent event) {
        event.message(this.scramble(event.message()));
    }

    /**
     * Vanilla builds these messages from the profile name, so they already show the nickname;
     * this only swaps obscured nicknames for their scrambled version.
     */
    private @Nullable Component scramble(final @Nullable Component message) {
        if (message == null) {
            return null;
        }
        Component result = message;
        for (final Nick nick : this.nicks.activeNicks()) {
            if (nick.style().scrambled() && Bukkit.getPlayer(nick.uuid()) != null) {
                result = result.replaceText(builder -> builder.matchLiteral(nick.nickname()).replacement(NickService.scrambled(nick.nickname())));
            }
        }
        return result;
    }
}
