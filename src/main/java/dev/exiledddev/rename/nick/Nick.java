package dev.exiledddev.rename.nick;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An active nickname.
 *
 * @param skin the skin given with the nickname, or null if the player keeps their own skin
 */
public record Nick(UUID uuid, String realName, String nickname, NickStyle style, @Nullable Skin skin, long nickedAt) {
}
