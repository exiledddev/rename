package dev.exiledddev.rename;

import java.util.Collection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

/**
 * Chat message helpers. Messages are MiniMessage strings; user-provided text always goes in through
 * placeholders so it can't inject formatting.
 */
public final class Msg {

    public static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Component PREFIX = MINI_MESSAGE.deserialize("<dark_gray>[</dark_gray><gold>Rename</gold><dark_gray>]</dark_gray> ");

    private Msg() {
    }

    public static void info(final CommandSender to, final String message, final TagResolver... resolvers) {
        send(to, "<gray>" + message, resolvers);
    }

    public static void success(final CommandSender to, final String message, final TagResolver... resolvers) {
        send(to, "<green>" + message, resolvers);
    }

    public static void error(final CommandSender to, final String message, final TagResolver... resolvers) {
        send(to, "<red>" + message, resolvers);
    }

    /** Sends a plain error, such as an exception message, without parsing it as MiniMessage. */
    public static void errorText(final CommandSender to, final String text) {
        error(to, "<text>", text("text", text));
    }

    /** Sends a line without the [Rename] prefix, for lists. */
    public static void line(final CommandSender to, final String message, final TagResolver... resolvers) {
        to.sendMessage(MINI_MESSAGE.deserialize(message, resolvers));
    }

    private static void send(final CommandSender to, final String message, final TagResolver... resolvers) {
        to.sendMessage(PREFIX.append(MINI_MESSAGE.deserialize(message, resolvers)));
    }

    /** {@code <key>} becomes the given text, unformatted. */
    public static TagResolver text(final String key, final Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    /** {@code <key>} becomes the given component. */
    public static TagResolver component(final String key, final Component value) {
        return Placeholder.component(key, value);
    }

    /** Joins names as "a, b, c". */
    public static String join(final Collection<String> names) {
        return String.join(", ", names);
    }
}
