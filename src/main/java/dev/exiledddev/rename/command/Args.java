package dev.exiledddev.rename.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** Shared argument helpers. */
final class Args {

    static final String TARGETS = "targets";

    private Args() {
    }

    /**
     * Resolves a {@code <targets>} argument. It's Paper's vanilla player selector, so names, UUIDs and
     * every selector form work: {@code @a[team=red]}, {@code @r[limit=3]}, {@code @p[distance=..10]}...
     */
    static List<Player> targets(final CommandContext<CommandSourceStack> ctx, final String name) throws CommandSyntaxException {
        return ctx.getArgument(name, PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
    }

    /** The UUID of whoever ran the command, if it was a player (recorded in the history). */
    static @Nullable UUID by(final CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender() instanceof Player player ? player.getUniqueId() : null;
    }

    /** "5m ago", "2h ago", "3d ago". */
    static String ago(final long timestamp) {
        final Duration elapsed = Duration.ofMillis(Math.max(0, System.currentTimeMillis() - timestamp));
        if (elapsed.toMinutes() < 1) {
            return "just now";
        }
        if (elapsed.toHours() < 1) {
            return elapsed.toMinutes() + "m ago";
        }
        if (elapsed.toDays() < 1) {
            return elapsed.toHours() + "h ago";
        }
        return elapsed.toDays() + "d ago";
    }
}
