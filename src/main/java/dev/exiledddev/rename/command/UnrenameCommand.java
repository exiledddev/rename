package dev.exiledddev.rename.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.exiledddev.rename.Msg;
import dev.exiledddev.rename.Permissions;
import dev.exiledddev.rename.nick.Nick;
import dev.exiledddev.rename.nick.NickService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Team;

/**
 * {@code /unrename <targets> | all | team <team>}: real names (and skins) back.
 */
public final class UnrenameCommand {

    private final NickService nicks;

    public UnrenameCommand(final NickService nicks) {
        this.nicks = nicks;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("unrename")
            .requires(source -> source.getSender().hasPermission(Permissions.USE))
            .then(Commands.literal("all").executes(this::all))
            .then(Commands.literal("team")
                .then(Commands.argument("team", StringArgumentType.word())
                    .suggests((ctx, builder) -> Suggest.matching(builder,
                        Bukkit.getScoreboardManager().getMainScoreboard().getTeams().stream().map(Team::getName).toList()))
                    .executes(this::team)))
            .then(Commands.argument(Args.TARGETS, ArgumentTypes.players()).executes(this::targets))
            .build();
    }

    private int targets(final CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        final CommandSender sender = ctx.getSource().getSender();
        final List<Player> targets = Args.targets(ctx, Args.TARGETS);
        int count = 0;
        String last = "";
        for (final Player player : targets) {
            final Nick nick = this.nicks.nick(player.getUniqueId());
            if (this.nicks.unnick(player)) {
                count++;
                last = nick == null ? player.getName() : nick.nickname() + " is " + nick.realName() + " again";
            }
        }
        if (count == 0) {
            Msg.info(sender, "None of them had a nickname.");
        } else if (count == 1) {
            Msg.success(sender, "<what>.", Msg.text("what", last));
        } else {
            Msg.success(sender, "Gave <count> players their real names back.", Msg.text("count", count));
        }
        return count;
    }

    /** Every nickname, including players who are offline. */
    private int all(final CommandContext<CommandSourceStack> ctx) {
        final int count = this.nicks.unnickAll();
        Msg.success(ctx.getSource().getSender(), count == 0
            ? "Nobody was nicked."
            : "Removed all <count> nickname(s), including offline players'.", Msg.text("count", count));
        return count;
    }

    /** Everyone on a scoreboard team (TeamSplit teams included), online or offline. */
    private int team(final CommandContext<CommandSourceStack> ctx) {
        final CommandSender sender = ctx.getSource().getSender();
        final String name = StringArgumentType.getString(ctx, "team");
        final Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(name);
        if (team == null) {
            Msg.error(sender, "There's no team named <team>.", Msg.text("team", name));
            return 0;
        }
        int count = 0;
        for (final String entry : List.copyOf(team.getEntries())) {
            final Nick nick = this.nicks.byNickname(entry);
            if (nick != null && this.nicks.unnick(nick.uuid())) {
                count++;
            }
        }
        Msg.success(sender, count == 0
            ? "Nobody on <team> was nicked."
            : "Gave <count> player(s) on <team> their real names back.", Msg.text("team", name), Msg.text("count", count));
        return count;
    }
}
