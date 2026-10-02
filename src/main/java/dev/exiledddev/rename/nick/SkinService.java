package dev.exiledddev.rename.nick;

import com.destroystokyo.paper.profile.PlayerProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Finds random skins. It looks up random real Minecraft accounts (three-character names are almost
 * all taken, so random ones usually exist) and uses their Mojang-signed skins. If that fails, for
 * example when Mojang is down or rate-limiting, it falls back to the skins of players who have
 * joined this server.
 */
public final class SkinService {

    private static final String ACCOUNT_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789_";

    private final Plugin plugin;
    private final Logger logger;
    private final Supplier<Integer> attempts;
    private final Supplier<Boolean> fallback;
    private final Supplier<List<Skin>> serverSkins;

    /**
     * @param attempts    how many random accounts to try per skin before falling back
     * @param fallback    whether to fall back to skins of players who joined this server
     * @param serverSkins the real skins of players who joined this server
     */
    public SkinService(final Plugin plugin, final Supplier<Integer> attempts, final Supplier<Boolean> fallback, final Supplier<List<Skin>> serverSkins) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.attempts = attempts;
        this.fallback = fallback;
        this.serverSkins = serverSkins;
    }

    /**
     * Finds {@code count} different random skins off the main thread. The future completes on the
     * main thread; entries are null when no skin could be found (that player keeps their own).
     */
    public CompletableFuture<List<@Nullable Skin>> randomSkins(final int count) {
        final List<Skin> pool = new ArrayList<>(this.serverSkins.get());
        final int tries = Math.max(0, this.attempts.get());
        final boolean useFallback = this.fallback.get();
        final CompletableFuture<List<@Nullable Skin>> result = new CompletableFuture<>();

        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            final List<@Nullable Skin> skins = new ArrayList<>(count);
            final Set<String> used = new HashSet<>();
            for (int i = 0; i < count; i++) {
                Skin skin = this.randomAccountSkin(tries, used);
                if (skin == null && useFallback) {
                    skin = pickUnused(pool, used);
                }
                if (skin != null) {
                    used.add(skin.value());
                }
                skins.add(skin);
            }
            Bukkit.getScheduler().runTask(this.plugin, () -> result.complete(skins));
        });
        return result;
    }

    /** Blocking Mojang lookups; only call off the main thread. */
    private @Nullable Skin randomAccountSkin(final int tries, final Set<String> used) {
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < tries; attempt++) {
            final StringBuilder name = new StringBuilder(3);
            for (int i = 0; i < 3; i++) {
                name.append(ACCOUNT_CHARS.charAt(random.nextInt(ACCOUNT_CHARS.length())));
            }
            try {
                final PlayerProfile profile = Bukkit.createProfile(name.toString());
                if (profile.complete(true, true)) {
                    final Skin skin = Skin.of(profile);
                    if (skin != null && skin.signature() != null && !used.contains(skin.value())) {
                        return skin;
                    }
                }
            } catch (final RuntimeException e) {
                this.logger.log(Level.FINE, "Skin lookup for " + name + " failed", e);
            }
        }
        return null;
    }

    private static @Nullable Skin pickUnused(final List<Skin> pool, final Set<String> used) {
        final List<Skin> unused = pool.stream().filter(skin -> !used.contains(skin.value())).toList();
        if (unused.isEmpty()) {
            return pool.isEmpty() ? null : pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        }
        return unused.get(ThreadLocalRandom.current().nextInt(unused.size()));
    }
}
