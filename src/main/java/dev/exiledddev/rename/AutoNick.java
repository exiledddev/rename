package dev.exiledddev.rename;

import dev.exiledddev.rename.nick.NickStyle;
import dev.exiledddev.rename.store.Database;
import org.jspecify.annotations.Nullable;

/**
 * The auto-nick session: while it's on, players who join without a nickname get one automatically.
 * Saved in the database so it survives restarts.
 */
public final class AutoNick {

    private static final String STYLE = "auto-nick.style";
    private static final String SKIN = "auto-nick.skin";

    private final Database database;
    private @Nullable NickStyle style;
    private boolean skin;

    public AutoNick(final Database database) {
        this.database = database;
        final String saved = database.setting(STYLE);
        this.style = saved == null ? null : NickStyle.parse(saved);
        this.skin = "true".equals(database.setting(SKIN));
    }

    /** The session's style, or null when auto-nick is off. */
    public @Nullable NickStyle style() {
        return this.style;
    }

    public boolean skin() {
        return this.skin;
    }

    public void start(final NickStyle newStyle, final boolean randomSkin) {
        this.style = newStyle;
        this.skin = randomSkin;
        this.database.setting(STYLE, newStyle.id());
        this.database.setting(SKIN, String.valueOf(randomSkin));
    }

    public void stop() {
        this.style = null;
        this.database.setting(STYLE, null);
        this.database.setting(SKIN, null);
    }
}
