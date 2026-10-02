package dev.exiledddev.rename.nick;

import java.util.Locale;
import java.util.Random;
import org.jspecify.annotations.Nullable;

/**
 * The four nickname styles. Every style produces a valid Minecraft username (3-16 characters of
 * letters, numbers and _), because the nickname becomes the player's in-game profile name.
 */
public enum NickStyle {
    /** Up to three readable parts: GamerBoy9718, GreatLucas, MaceGodYT. */
    GENERIC,
    /** Only 1s and 0s. */
    BINARY,
    /** Random letters, numbers and underscores, like a generated password. */
    UNSETTLING,
    /** Scrambled (obfuscated) text in chat, tab and messages; a 16-character jumble on the nametag. */
    OBSCURED;

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 16;

    private static final String BINARY_CHARS = "01";
    private static final String NAME_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_";

    public String id() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    public static @Nullable NickStyle parse(final String raw) {
        for (final NickStyle style : values()) {
            if (style.id().equalsIgnoreCase(raw.strip())) {
                return style;
            }
        }
        return null;
    }

    /** One candidate nickname. Callers retry until it's not already taken. */
    public String generate(final Random random) {
        return switch (this) {
            case GENERIC -> GenericNames.generate(random);
            case BINARY -> randomString(BINARY_CHARS, randomLength(random), random);
            case UNSETTLING -> randomString(NAME_CHARS, randomLength(random), random);
            case OBSCURED -> randomString(NAME_CHARS, MAX_LENGTH, random);
        };
    }

    /** Whether the name shows scrambled in chat, tab and messages. */
    public boolean scrambled() {
        return this == OBSCURED;
    }

    static int randomLength(final Random random) {
        return MIN_LENGTH + random.nextInt(MAX_LENGTH - MIN_LENGTH + 1);
    }

    private static String randomString(final String chars, final int length, final Random random) {
        final StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(chars.charAt(random.nextInt(chars.length())));
        }
        return builder.toString();
    }
}
