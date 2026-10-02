package dev.exiledddev.rename.nick;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Rules every nickname follows: it has to be a valid Minecraft username. */
public final class NickNames {

    public static final Pattern VALID = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private NickNames() {
    }

    /** @return an error message, or {@code null} if the nickname is fine */
    public static @Nullable String validate(final String nickname) {
        if (!VALID.matcher(nickname).matches()) {
            return "Nicknames must be 3-16 letters, numbers or _ (Minecraft's username rules): " + nickname;
        }
        return null;
    }
}
