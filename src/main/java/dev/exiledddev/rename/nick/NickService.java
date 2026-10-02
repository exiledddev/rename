package dev.exiledddev.rename.nick;

import com.destroystokyo.paper.profile.PlayerProfile;
import dev.exiledddev.rename.store.Database;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jspecify.annotations.Nullable;

/**
 * Gives and removes nicknames.
 *
 * <p>A nickname replaces the player's profile name (and optionally their skin) for everyone on the
 * server, so it shows on the nametag, in the tab list, in chat, in death and kill messages, in
 * join/leave and advancement messages, and in vanilla selectors such as {@code @a[name=...]}.
 * Because the scoreboard knows players by name, their team entry and scores move to the nickname
 * and back, so TeamSplit teams and {@code @a[team=...]} keep working.
 *
 * <p>Active nicknames are kept in memory and in the database, so they survive the player leaving
 * and the server restarting until they're removed.
 */
public final class NickService {

    private static final int MAX_NAME_ATTEMPTS = 2000;
    /** Ticks between hiding a renamed player from everyone else and showing them again. */
    private static final long REFRESH_DELAY_TICKS = 2;

    private final Plugin plugin;
    private final Database database;
    private final SkinService skins;
    private final StyleSkins styleSkins;
    private final Map<UUID, Nick> active = new ConcurrentHashMap<>();
    private final Map<UUID, Database.PlayerRecord> players = new ConcurrentHashMap<>();

    public NickService(final Plugin plugin, final Database database, final SkinService skins, final StyleSkins styleSkins) {
        this.plugin = plugin;
        this.database = database;
        this.skins = skins;
        this.styleSkins = styleSkins;
    }

    public void load() {
        this.active.clear();
        this.active.putAll(this.database.loadActiveNicks());
        this.players.clear();
        this.players.putAll(this.database.loadPlayers());
    }

    // ---- Lookups --------------------------------------------------------------------------

    public @Nullable Nick nick(final UUID uuid) {
        return this.active.get(uuid);
    }

    public Collection<Nick> activeNicks() {
        return List.copyOf(this.active.values());
    }

    /** The player's real name, even while nicked. */
    public String realName(final Player player) {
        final Nick nick = this.active.get(player.getUniqueId());
        return nick != null ? nick.realName() : player.getName();
    }

    /** The active nickname with this name (ignoring case), if any. */
    public @Nullable Nick byNickname(final String nickname) {
        for (final Nick nick : this.active.values()) {
            if (nick.nickname().equalsIgnoreCase(nickname)) {
                return nick;
            }
        }
        return null;
    }

    public Database.@Nullable PlayerRecord playerByRealName(final String name) {
        for (final Database.PlayerRecord record : this.players.values()) {
            if (record.realName().equalsIgnoreCase(name)) {
                return record;
            }
        }
        return null;
    }

    public List<String> knownRealNames() {
        return this.players.values().stream().map(Database.PlayerRecord::realName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /** Real skins of everyone who has joined, for the random-skin fallback. */
    public List<Skin> serverSkins() {
        return this.players.values().stream().map(Database.PlayerRecord::skin).filter(Objects::nonNull).toList();
    }

    public Database database() {
        return this.database;
    }

    /**
     * Whether a nickname is free: not anyone's active nickname, not a known player's real name,
     * and not the name of anyone online.
     */
    public boolean isTaken(final String name, final Set<String> reserved) {
        final String lower = name.toLowerCase(Locale.ROOT);
        if (reserved.contains(lower) || this.byNickname(name) != null || this.playerByRealName(name) != null) {
            return true;
        }
        for (final Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A free nickname in the given style. {@code reserved} holds lowercase names already handed out
     * in the same batch; the new name is added to it.
     */
    public String generate(final NickStyle style, final Set<String> reserved) {
        for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
            final String name = style.generate(ThreadLocalRandom.current());
            if (!this.isTaken(name, reserved)) {
                reserved.add(name.toLowerCase(Locale.ROOT));
                return name;
            }
        }
        throw new IllegalStateException("Could not find a free " + style.id() + " nickname");
    }

    // ---- Nicking --------------------------------------------------------------------------

    /**
     * A planned nickname for one player, before skins are looked up.
     *
     * @param skin whether to change their skin: to the style's fixed skin if it has one, otherwise a random one
     */
    public record Request(Player player, String nickname, NickStyle style, boolean skin) {
    }

    /**
     * Gives each player a new random nickname in {@code style}.
     *
     * @return the nicknames, once any random skins have been found (completes on the main thread)
     */
    public CompletableFuture<List<Nick>> nickAll(final Collection<Player> targets, final NickStyle style, final boolean skin, final @Nullable UUID by) {
        final Set<String> reserved = new HashSet<>();
        final List<Request> requests = new ArrayList<>();
        for (final Player player : targets) {
            requests.add(new Request(player, this.generate(style, reserved), style, skin));
        }
        return this.apply(requests, by);
    }

    /**
     * Gives each nicked player a new nickname in the style they already have, and a new random
     * skin if they had one. Players without a nickname are skipped.
     */
    public CompletableFuture<List<Nick>> reroll(final Collection<Player> targets, final @Nullable UUID by) {
        final Set<String> reserved = new HashSet<>();
        final List<Request> requests = new ArrayList<>();
        for (final Player player : targets) {
            final Nick current = this.active.get(player.getUniqueId());
            if (current != null) {
                requests.add(new Request(player, this.generate(current.style(), reserved), current.style(), current.skin() != null));
            }
        }
        return this.apply(requests, by);
    }

    /**
     * Gives requests for styles with a fixed skin that skin, looks up random skins for the rest,
     * then applies every request on the main thread.
     */
    public CompletableFuture<List<Nick>> apply(final List<Request> requests, final @Nullable UUID by) {
        final List<Request> needSkins = requests.stream().filter(this::needsRandomSkin).toList();
        final CompletableFuture<List<@Nullable Skin>> skinsFuture = needSkins.isEmpty()
            ? CompletableFuture.completedFuture(List.of())
            : this.skins.randomSkins(needSkins.size());

        return skinsFuture.thenApply(found -> {
            final List<Nick> applied = new ArrayList<>();
            int skinIndex = 0;
            for (final Request request : requests) {
                final Skin skin;
                if (!request.skin()) {
                    skin = null;
                } else if (this.needsRandomSkin(request)) {
                    skin = found.get(skinIndex++);
                } else {
                    skin = this.styleSkins.skin(request.style());
                }
                if (request.player().isOnline()) {
                    applied.add(this.applyOne(request.player(), request.nickname(), request.style(), skin, by));
                }
            }
            return applied;
        });
    }

    private boolean needsRandomSkin(final Request request) {
        return request.skin() && this.styleSkins.skin(request.style()) == null;
    }

    /**
     * Whether a nickname in this style should change the player's skin when the command doesn't
     * say: always for styles with a fixed skin, otherwise the config default for random skins.
     */
    public boolean skinByDefault(final NickStyle style, final boolean randomByDefault) {
        return this.styleSkins.hasSkin(style) || randomByDefault;
    }

    public StyleSkins styleSkins() {
        return this.styleSkins;
    }

    private Nick applyOne(final Player player, final String nickname, final NickStyle style, final @Nullable Skin skin, final @Nullable UUID by) {
        this.remember(player);
        final Nick nick = new Nick(player.getUniqueId(), this.realName(player), nickname, style, skin, System.currentTimeMillis());
        this.active.put(player.getUniqueId(), nick);
        this.database.startNick(nick, by);
        this.setProfile(player, nickname, skin != null ? skin : this.realSkin(player));
        return nick;
    }

    // ---- Un-nicking -----------------------------------------------------------------------

    /** Gives an online player their real name and skin back. Returns false if they weren't nicked. */
    public boolean unnick(final Player player) {
        final Nick nick = this.active.remove(player.getUniqueId());
        if (nick == null) {
            return false;
        }
        this.database.endNick(player.getUniqueId(), System.currentTimeMillis());
        this.setProfile(player, nick.realName(), this.realSkin(player));
        return true;
    }

    /** Ends a nickname by player UUID, whether or not they're online. */
    public boolean unnick(final UUID uuid) {
        final Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return this.unnick(online);
        }
        final Nick nick = this.active.remove(uuid);
        if (nick == null) {
            return false;
        }
        this.database.endNick(uuid, System.currentTimeMillis());
        // They'll log in with their real profile; move their team entry and scores back now.
        moveScoreboardEntry(nick.nickname(), nick.realName());
        return true;
    }

    /** Ends every nickname, online and offline. */
    public int unnickAll() {
        int count = 0;
        for (final UUID uuid : List.copyOf(this.active.keySet())) {
            if (this.unnick(uuid)) {
                count++;
            }
        }
        return count;
    }

    // ---- Login and join -------------------------------------------------------------------

    /**
     * Called from async pre-login with the player's real Mojang profile. Remembers their real name
     * and skin, and returns the profile they should log in with: their nickname profile if they're
     * nicked, otherwise null (keep the real one).
     */
    public @Nullable PlayerProfile loginProfile(final PlayerProfile realProfile) {
        final UUID uuid = realProfile.getId();
        final String realName = realProfile.getName();
        if (uuid == null || realName == null) {
            return null;
        }
        final Skin realSkin = Skin.of(realProfile);
        this.players.put(uuid, new Database.PlayerRecord(uuid, realName, realSkin));
        this.database.savePlayer(uuid, realName, realSkin);

        final Nick nick = this.active.get(uuid);
        if (nick == null) {
            return null;
        }
        return profile(uuid, nick.nickname(), nick.skin() != null ? nick.skin() : realSkin);
    }

    /** Called on join: scrambles an obscured player's name in chat and tab. */
    public void decorate(final Player player) {
        final Nick nick = this.active.get(player.getUniqueId());
        if (nick != null && nick.style().scrambled()) {
            final Component scrambled = scrambled(nick.nickname());
            player.displayName(scrambled);
            player.playerListName(scrambled);
        } else {
            player.displayName(null); // defaults to the current profile name: the nickname or real name
            player.playerListName(null);
        }
    }

    /** Records a player who was already online when the plugin loaded (they skipped pre-login). */
    public void remember(final Player player) {
        if (this.active.containsKey(player.getUniqueId()) || this.players.containsKey(player.getUniqueId())) {
            return;
        }
        final Skin skin = Skin.of(player.getPlayerProfile());
        this.players.put(player.getUniqueId(), new Database.PlayerRecord(player.getUniqueId(), player.getName(), skin));
        this.database.savePlayer(player.getUniqueId(), player.getName(), skin);
    }

    // ---- Helpers --------------------------------------------------------------------------

    private @Nullable Skin realSkin(final Player player) {
        final Database.PlayerRecord record = this.players.get(player.getUniqueId());
        if (record != null && record.skin() != null) {
            return record.skin();
        }
        return this.active.containsKey(player.getUniqueId()) ? null : Skin.of(player.getPlayerProfile());
    }

    /** Swaps the player's profile (everyone sees the new name and skin) and moves their scoreboard entry. */
    private void setProfile(final Player player, final String name, final @Nullable Skin skin) {
        moveScoreboardEntry(player.getName(), name);
        player.setPlayerProfile(profile(player.getUniqueId(), name, skin));
        this.decorate(player);
        this.refreshForOthers(player);
    }

    /**
     * Makes everyone else's game pick up the new name and skin. setPlayerProfile refreshes the
     * player themselves, but other clients can keep showing the old tab entry, nametag and skin.
     * Hiding the player from each viewer and showing them again a couple of ticks later makes
     * those clients drop the old profile and receive the new one.
     */
    private void refreshForOthers(final Player player) {
        final List<Player> viewers = new ArrayList<>();
        for (final Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(player) && viewer.canSee(player)) {
                viewer.hidePlayer(this.plugin, player);
                viewers.add(viewer);
            }
        }
        if (viewers.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            for (final Player viewer : viewers) {
                if (viewer.isOnline() && player.isOnline()) {
                    viewer.showPlayer(this.plugin, player);
                }
            }
        }, REFRESH_DELAY_TICKS);
    }

    private static PlayerProfile profile(final UUID uuid, final String name, final @Nullable Skin skin) {
        final PlayerProfile profile = Bukkit.createProfileExact(uuid, name);
        if (skin != null) {
            profile.setProperty(skin.property());
        }
        return profile;
    }

    /**
     * The scoreboard tracks players by name, so a renamed player would silently drop off their
     * team. Moves their team membership and every score from the old name to the new one.
     */
    public static void moveScoreboardEntry(final String from, final String to) {
        if (from.equals(to)) {
            return;
        }
        final Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        final Team team = scoreboard.getEntryTeam(from);
        if (team != null) {
            team.removeEntry(from);
            team.addEntry(to);
        }
        for (final Objective objective : scoreboard.getObjectives()) {
            final Score score = objective.getScore(from);
            if (score.isScoreSet()) {
                objective.getScore(to).setScore(score.getScore());
                score.resetScore();
            }
        }
    }

    /** The nickname as scrambled (obfuscated) text. */
    public static Component scrambled(final String nickname) {
        return Component.text(nickname).decorate(TextDecoration.OBFUSCATED);
    }
}
