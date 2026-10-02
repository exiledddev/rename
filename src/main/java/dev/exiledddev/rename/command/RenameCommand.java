package dev.exiledddev.rename.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.exiledddev.rename.AutoNick;
import dev.exiledddev.rename.Msg;
import dev.exiledddev.rename.Permissions;
import dev.exiledddev.rename.RenamePlugin;
import dev.exiledddev.rename.nick.Nick;
import dev.exiledddev.rename.nick.NickNames;
import dev.exiledddev.rename.nick.NickService;
import dev.exiledddev.rename.nick.NickStyle;
import dev.exiledddev.rename.nick.StyleSkins;
import dev.exiledddev.rename.store.Database;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * {@code /rename}: give players random (or chosen) nicknames, and look up who's who.
 */
public final class RenameCommand {

    private static final String SKIN_FLAG = "--skin";
    private static final String NO_SKIN_FLAG = "--noskin";

    /** Usage line, text a click puts in the chat box, and what it does. */
    private record HelpEntry(String usage, String suggestion, String description) {
    }

    private static final List<HelpEntry> HELP = List.of(
        new HelpEntry("/rename <targets> [style] [--skin|--noskin]", "/rename ", "random nicknames (styles: generic, binary, unsettling, obscured)"),
        new HelpEntry("/rename set <player> <nickname> [--skin]", "/rename set ", "a specific nickname"),
        new HelpEntry("/rename reroll <targets>", "/rename reroll ", "new nicknames in the same style"),
        new HelpEntry("/unrename <targets>|all|team <team>", "/unrename ", "real names back"),
        new HelpEntry("/rename list", "/rename list", "who is nicked as what"),
        new HelpEntry("/rename whois <nickname>", "/rename whois ", "the real player behind a nickname"),
        new HelpEntry("/rename history <player>", "/rename history ", "a player's past nicknames"),
        new HelpEntry("/rename auto <style> [--skin] | off", "/rename auto ", "nickname everyone who joins"),
        new HelpEntry("/rename skins", "/rename skins", "which styles have a fixed skin"),
        new HelpEntry("/rename reload", "/rename reload", "reload config.yml")
    );

    private final RenamePlugin plugin;
    private final NickService nicks;
    private final AutoNick autoNick;

    public RenameCommand(final RenamePlugin plugin, final NickService nicks, final AutoNick autoNick) {
        this.plugin = plugin;
        this.nicks = nicks;
        this.autoNick = autoNick;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("rename")
            .requires(source -> source.getSender().hasPermission(Permissions.USE))
            .executes(this::help)
            .then(Commands.literal("help").executes(this::help))
            .then(this.withStyles(Commands.argument(Args.TARGETS, ArgumentTypes.players()),
                (ctx, style, skin) -> this.rename(ctx, style, skin)))
            .then(Commands.literal("set")
                .then(Commands.argument("player", ArgumentTypes.player())
                    .then(Commands.argument("nickname", StringArgumentType.word())
                        .executes(ctx -> this.set(ctx, false))
                        .then(Commands.literal(SKIN_FLAG).executes(ctx -> this.set(ctx, true))))))
            .then(Commands.literal("reroll")
                .then(Commands.argument(Args.TARGETS, ArgumentTypes.players()).executes(this::reroll)))
            .then(Commands.literal("list").executes(this::list))
            .then(Commands.literal("whois")
                .then(Commands.argument("nickname", StringArgumentType.word())
                    .suggests((ctx, builder) -> Suggest.matching(builder, this.nicks.activeNicks().stream().map(Nick::nickname).toList()))
                    .executes(this::whois)))
            .then(Commands.literal("history")
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests((ctx, builder) -> Suggest.matching(builder, this.nicks.knownRealNames()))
                    .executes(this::history)))
            .then(this.withStyles(Commands.literal("auto"), (ctx, style, skin) -> this.auto(ctx, style, skin))
                .then(Commands.literal("off").executes(ctx -> this.autoOff(ctx))))
            .then(Commands.literal("skins").executes(this::skins))
            .then(Commands.literal("reload").executes(ctx -> {
                this.plugin.reloadSettings();
                Msg.success(ctx.getSource().getSender(), "Reloaded config.yml. Style skins are reloading; check them with /rename skins.");
                return Command.SINGLE_SUCCESS;
            }))
            .build();
    }

    @FunctionalInterface
    private interface StyledAction {
        int run(CommandContext<CommandSourceStack> ctx, @Nullable NickStyle style, @Nullable Boolean skin) throws CommandSyntaxException;
    }

    /**
     * Adds {@code [style] [--skin|--noskin]} after a node. A missing style or flag is null, meaning
     * "use the config default".
     */
    private <T extends ArgumentBuilder<CommandSourceStack, T>> T withStyles(final T node, final StyledAction action) {
        node.executes(ctx -> action.run(ctx, null, null));
        node.then(Commands.literal(SKIN_FLAG).executes(ctx -> action.run(ctx, null, true)));
        node.then(Commands.literal(NO_SKIN_FLAG).executes(ctx -> action.run(ctx, null, false)));
        for (final NickStyle style : NickStyle.values()) {
            node.then(Commands.literal(style.id())
                .executes(ctx -> action.run(ctx, style, null))
                .then(Commands.literal(SKIN_FLAG).executes(ctx -> action.run(ctx, style, true)))
                .then(Commands.literal(NO_SKIN_FLAG).executes(ctx -> action.run(ctx, style, false))));
        }
        return node;
    }

    // ---- Subcommands ----------------------------------------------------------------------

    private int help(final CommandContext<CommandSourceStack> ctx) {
        final CommandSender sender = ctx.getSource().getSender();
        Msg.info(sender, "Commands <dark_gray>(click one to type it)</dark_gray>:");
        for (final HelpEntry entry : HELP) {
            sender.sendMessage(Component.text()
                .append(Component.text(" " + entry.usage(), NamedTextColor.GOLD)
                    .clickEvent(ClickEvent.suggestCommand(entry.suggestion()))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to type " + entry.suggestion().strip()))))
                .append(Component.text(" - " + entry.description(), NamedTextColor.GRAY)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int rename(final CommandContext<CommandSourceStack> ctx, final @Nullable NickStyle requestedStyle, final @Nullable Boolean requestedSkin) throws CommandSyntaxException {
        final CommandSender sender = ctx.getSource().getSender();
        final List<Player> targets = Args.targets(ctx, Args.TARGETS);
        final NickStyle style = requestedStyle != null ? requestedStyle : this.plugin.settings().defaultStyle();
        final boolean skin = requestedSkin != null ? requestedSkin : this.nicks.skinByDefault(style, this.plugin.settings().skinByDefault());
        if (skin && this.nicks.styleSkins().skin(style) == null) {
            if (this.nicks.styleSkins().hasSkin(style)) {
                Msg.info(sender, "The <style> skin isn't loaded (see /rename skins), so they get random skins instead.", Msg.text("style", style.id()));
            }
            Msg.info(sender, "Looking up <count> random skin(s)...", Msg.text("count", targets.size()));
        }
        this.report(sender, this.nicks.nickAll(targets, style, skin, Args.by(ctx)), style.id());
        return targets.size();
    }

    private int set(final CommandContext<CommandSourceStack> ctx, final boolean skin) throws CommandSyntaxException {
        final CommandSender sender = ctx.getSource().getSender();
        final List<Player> found = ctx.getArgument("player", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
        final Player target = found.getFirst();
        final String nickname = StringArgumentType.getString(ctx, "nickname");
        final String error = NickNames.validate(nickname);
        if (error != null) {
            Msg.errorText(sender, error);
            return 0;
        }
        final Nick current = this.nicks.nick(target.getUniqueId());
        final boolean ownName = current != null ? current.nickname().equalsIgnoreCase(nickname) : target.getName().equalsIgnoreCase(nickname);
        if (!ownName && this.nicks.isTaken(nickname, new HashSet<>())) {
            Msg.error(sender, "<nickname> is already a nickname or a player's real name.", Msg.text("nickname", nickname));
            return 0;
        }
        if (skin) {
            Msg.info(sender, "Looking up a random skin...");
        }
        final NickStyle style = current != null && current.style() != NickStyle.GENERIC ? current.style() : NickStyle.GENERIC;
        this.report(sender, this.nicks.apply(List.of(new NickService.Request(target, nickname, style, skin)), Args.by(ctx)), "custom");
        return Command.SINGLE_SUCCESS;
    }

    private int reroll(final CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        final CommandSender sender = ctx.getSource().getSender();
        final List<Player> targets = Args.targets(ctx, Args.TARGETS);
        final long nicked = targets.stream().filter(player -> this.nicks.nick(player.getUniqueId()) != null).count();
        if (nicked == 0) {
            Msg.error(sender, "None of them have a nickname to reroll. Use /rename <targets> first.");
            return 0;
        }
        this.report(sender, this.nicks.reroll(targets, Args.by(ctx)), "same style");
        return (int) nicked;
    }

    private int list(final CommandContext<CommandSourceStack> ctx) {
        final CommandSender sender = ctx.getSource().getSender();
        final List<Nick> active = this.nicks.activeNicks().stream()
            .sorted(Comparator.comparing(Nick::nickname, String.CASE_INSENSITIVE_ORDER))
            .toList();
        if (active.isEmpty()) {
            Msg.info(sender, "Nobody is nicked right now.");
        } else {
            Msg.info(sender, "<count> nicked player(s):", Msg.text("count", active.size()));
            for (final Nick nick : active) {
                this.sendNickLine(sender, nick);
            }
        }
        final NickStyle autoStyle = this.autoNick.style();
        if (autoStyle != null) {
            Msg.line(sender, " <gray>Auto-nick is on: players who join get a <style> nickname<skin>.",
                Msg.text("style", autoStyle.id()), Msg.text("skin", this.autoNick.skin() ? " and a random skin" : ""));
        }
        return active.size();
    }

    private int whois(final CommandContext<CommandSourceStack> ctx) {
        final CommandSender sender = ctx.getSource().getSender();
        final String name = StringArgumentType.getString(ctx, "nickname");

        final Nick active = this.nicks.byNickname(name);
        if (active != null) {
            Msg.info(sender, "<nick> is <white><real></white> <dark_gray>(<style>, since <since>, <online>)",
                Msg.component("nick", this.nickName(active)), Msg.text("real", active.realName()), Msg.text("style", active.style().id()),
                Msg.text("since", Args.ago(active.nickedAt())), Msg.text("online", Bukkit.getPlayer(active.uuid()) != null ? "online" : "offline"));
            return Command.SINGLE_SUCCESS;
        }

        final Database.PlayerRecord real = this.nicks.playerByRealName(name);
        if (real != null) {
            final Nick nick = this.nicks.nick(real.uuid());
            if (nick != null) {
                Msg.info(sender, "<white><real></white> is currently nicked as <nick>.", Msg.text("real", real.realName()), Msg.component("nick", this.nickName(nick)));
            } else {
                Msg.info(sender, "<white><real></white> is a real name and isn't nicked right now.", Msg.text("real", real.realName()));
            }
            return Command.SINGLE_SUCCESS;
        }

        final List<Database.HistoryEntry> past = this.nicks.database().findNickname(name, 5);
        if (past.isEmpty()) {
            Msg.error(sender, "Nobody has had the nickname <name>.", Msg.text("name", name));
            return 0;
        }
        for (final Database.HistoryEntry entry : past) {
            Msg.info(sender, "<nick> was <white><real></white> <dark_gray>(<style>, <when>)",
                Msg.text("nick", entry.nickname()), Msg.text("real", entry.realName()), Msg.text("style", entry.style().id()),
                Msg.text("when", Args.ago(entry.nickedAt()) + (entry.endedAt() != null ? ", ended " + Args.ago(entry.endedAt()) : "")));
        }
        return past.size();
    }

    private int history(final CommandContext<CommandSourceStack> ctx) {
        final CommandSender sender = ctx.getSource().getSender();
        final String name = StringArgumentType.getString(ctx, "player");
        Database.PlayerRecord record = this.nicks.playerByRealName(name);
        if (record == null) {
            final Nick nick = this.nicks.byNickname(name);
            record = nick == null ? null : this.nicks.playerByRealName(nick.realName());
        }
        if (record == null) {
            Msg.error(sender, "No player called <name> has joined while Rename was installed.", Msg.text("name", name));
            return 0;
        }
        final List<Database.HistoryEntry> entries = this.nicks.database().history(record.uuid(), 15);
        if (entries.isEmpty()) {
            Msg.info(sender, "<white><real></white> has never been nicked.", Msg.text("real", record.realName()));
            return 0;
        }
        Msg.info(sender, "Nicknames of <white><real></white>, newest first:", Msg.text("real", record.realName()));
        for (final Database.HistoryEntry entry : entries) {
            Msg.line(sender, " <gold><nick></gold> <dark_gray>(<style>, <when>)",
                Msg.text("nick", entry.nickname()), Msg.text("style", entry.style().id()),
                Msg.text("when", Args.ago(entry.nickedAt()) + (entry.endedAt() == null ? ", current" : ", ended " + Args.ago(entry.endedAt()))));
        }
        return entries.size();
    }

    private int auto(final CommandContext<CommandSourceStack> ctx, final @Nullable NickStyle requestedStyle, final @Nullable Boolean requestedSkin) {
        final NickStyle style = requestedStyle != null ? requestedStyle : this.plugin.settings().defaultStyle();
        final boolean skin = requestedSkin != null ? requestedSkin : this.nicks.skinByDefault(style, this.plugin.settings().skinByDefault());
        this.autoNick.start(style, skin);
        Msg.success(ctx.getSource().getSender(), "Auto-nick is on: players who join without a nickname get a <style> nickname<skin>. "
                + "Players with rename.exempt are skipped. Stop with /rename auto off.",
            Msg.text("style", style.id()), Msg.text("skin", skin ? (this.nicks.styleSkins().hasSkin(style) ? " and the " + style.id() + " skin" : " and a random skin") : ""));
        return Command.SINGLE_SUCCESS;
    }

    /** Which styles have a fixed skin, and whether it loaded. */
    private int skins(final CommandContext<CommandSourceStack> ctx) {
        final CommandSender sender = ctx.getSource().getSender();
        Msg.info(sender, "Style skins <dark_gray>(style-skins in config.yml)</dark_gray>:");
        for (final NickStyle style : NickStyle.values()) {
            final StyleSkins.Status status = this.nicks.styleSkins().statuses().get(style);
            if (status == null) {
                Msg.line(sender, " <gold><style></gold> <gray>random skin with --skin", Msg.text("style", style.id()));
            } else if (status.skin() != null) {
                Msg.line(sender, " <gold><style></gold> <green>fixed skin loaded <dark_gray>(<source>)", Msg.text("style", style.id()), Msg.text("source", status.source()));
            } else {
                Msg.line(sender, " <gold><style></gold> <red>fixed skin not loaded: <problem>", Msg.text("style", style.id()),
                    Msg.text("problem", status.problem() == null ? "unknown" : status.problem()));
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private int autoOff(final CommandContext<CommandSourceStack> ctx) {
        this.autoNick.stop();
        Msg.success(ctx.getSource().getSender(), "Auto-nick is off. Existing nicknames stay until you /unrename them.");
        return Command.SINGLE_SUCCESS;
    }

    // ---- Helpers --------------------------------------------------------------------------

    /** Reports the result once random skins (if any) have been found and the names applied. */
    private void report(final CommandSender sender, final CompletableFuture<List<Nick>> future, final String what) {
        future.whenComplete((applied, error) -> {
            if (error != null) {
                this.plugin.getLogger().log(java.util.logging.Level.SEVERE, "Renaming failed", error);
                Msg.error(sender, "Renaming failed, see the console.");
                return;
            }
            if (applied.isEmpty()) {
                Msg.error(sender, "Nobody was renamed (they may have logged off).");
            } else if (applied.size() == 1) {
                final Nick nick = applied.getFirst();
                Msg.success(sender, "<real> is now <nick><skin>.", Msg.text("real", nick.realName()),
                    Msg.component("nick", this.nickName(nick)), Msg.text("skin", nick.skin() != null ? " with a new skin" : ""));
            } else {
                final long skins = applied.stream().filter(nick -> nick.skin() != null).count();
                Msg.success(sender, "Renamed <count> players (<what>)<skins>. See who's who with /rename list.",
                    Msg.text("count", applied.size()), Msg.text("what", what),
                    Msg.text("skins", skins > 0 ? ", " + skins + " with new skins" : ""));
            }
        });
    }

    private void sendNickLine(final CommandSender sender, final Nick nick) {
        final boolean online = Bukkit.getPlayer(nick.uuid()) != null;
        Msg.line(sender, " <nick> <dark_gray>→</dark_gray> <white><real></white> <dark_gray>(<details>)",
            Msg.component("nick", this.nickName(nick)), Msg.text("real", nick.realName()),
            Msg.text("details", nick.style().id() + (nick.skin() != null ? ", new skin" : "") + ", " + (online ? "online" : "offline") + ", " + Args.ago(nick.nickedAt())));
    }

    /** The nickname in gold, with a hover showing it unscrambled for obscured names. */
    private Component nickName(final Nick nick) {
        final Component name = Component.text(nick.nickname(), NamedTextColor.GOLD);
        return nick.style().scrambled()
            ? NickService.scrambled(nick.nickname()).color(NamedTextColor.GOLD).hoverEvent(HoverEvent.showText(name))
            : name;
    }
}
