package dev.exiledddev.rename.nick;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.jspecify.annotations.Nullable;

/**
 * A skin as Minecraft sends it: the "textures" profile property, signed by Mojang. Clients only
 * show skins with a valid signature, so skins always come from real Mojang profiles.
 */
public record Skin(String value, @Nullable String signature) {

    public static final String PROPERTY = "textures";

    public static @Nullable Skin of(final PlayerProfile profile) {
        for (final ProfileProperty property : profile.getProperties()) {
            if (PROPERTY.equals(property.getName())) {
                return new Skin(property.getValue(), property.getSignature());
            }
        }
        return null;
    }

    public ProfileProperty property() {
        return new ProfileProperty(PROPERTY, this.value, this.signature);
    }
}
