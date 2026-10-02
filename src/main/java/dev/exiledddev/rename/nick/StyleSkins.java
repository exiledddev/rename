package dev.exiledddev.rename.nick;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.exiledddev.rename.store.Database;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Fixed skins per nickname style (config section {@code style-skins}). Everyone nicknamed in a
 * style with a fixed skin gets that skin instead of a random one.
 *
 * <p>Minecraft only shows skins signed by Mojang, so each style's skin comes from one of:
 * <ul>
 *   <li>{@code value} + {@code signature}: a signed texture pasted in (e.g. from mineskin.org)</li>
 *   <li>{@code account}: a Minecraft account currently wearing the skin</li>
 *   <li>{@code namemc} or {@code url}: a skin image, signed through the MineSkin API
 *       ({@code skins.mineskin-api-key})</li>
 * </ul>
 * Results are cached in the database, so a skin is only looked up again when its source changes.
 */
public final class StyleSkins {

    /** What a style's skin is set to, and whether it has loaded yet. */
    public record Status(String source, @Nullable Skin skin, @Nullable String problem) {
    }

    private static final Pattern NAMEMC_ID = Pattern.compile("([0-9a-f]{16})");
    private static final String MINESKIN_GENERATE = "https://api.mineskin.org/v2/generate";

    private final Plugin plugin;
    private final Logger logger;
    private final Database database;
    private final Map<NickStyle, Status> statuses = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public StyleSkins(final Plugin plugin, final Database database) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.database = database;
    }

    /** The style's fixed skin, or null if it has none (or it hasn't loaded). */
    public @Nullable Skin skin(final NickStyle style) {
        final Status status = this.statuses.get(style);
        return status == null ? null : status.skin();
    }

    /** Whether the style is configured to use a fixed skin (even if it hasn't loaded yet). */
    public boolean hasSkin(final NickStyle style) {
        return this.statuses.containsKey(style);
    }

    public Map<NickStyle, Status> statuses() {
        final Map<NickStyle, Status> copy = new EnumMap<>(NickStyle.class);
        copy.putAll(this.statuses);
        return copy;
    }

    /** Reads the config and loads every style's skin; slow lookups run in the background. */
    public void load(final @Nullable ConfigurationSection section, final String mineskinKey) {
        this.statuses.clear();
        if (section == null) {
            return;
        }
        for (final NickStyle style : NickStyle.values()) {
            final ConfigurationSection entry = section.getConfigurationSection(style.id());
            if (entry != null) {
                this.loadOne(style, entry, mineskinKey);
            }
        }
    }

    private void loadOne(final NickStyle style, final ConfigurationSection entry, final String mineskinKey) {
        final String value = entry.getString("value", "").strip();
        final String signature = entry.getString("signature", "").strip();
        final String account = entry.getString("account", "").strip();
        final String namemc = entry.getString("namemc", "").strip();
        final String url = entry.getString("url", "").strip();

        if (!value.isEmpty()) {
            this.statuses.put(style, signature.isEmpty()
                ? new Status("pasted texture", null, "the signature is missing; Minecraft only shows signed skins")
                : new Status("pasted texture", new Skin(value, signature), null));
            return;
        }

        final String source;
        if (!account.isEmpty()) {
            source = "account:" + account;
        } else if (!namemc.isEmpty() || !url.isEmpty()) {
            final String imageUrl = url.isEmpty() ? namemcImage(namemc) : url;
            if (imageUrl == null) {
                this.statuses.put(style, new Status("namemc:" + namemc, null, "that isn't a NameMC skin link or id"));
                return;
            }
            source = "image:" + imageUrl;
        } else {
            return; // nothing configured for this style
        }

        // Reuse what we looked up last time if the source hasn't changed.
        final String cacheKey = "style-skin." + style.id();
        if (source.equals(this.database.setting(cacheKey + ".source"))) {
            final String cachedValue = this.database.setting(cacheKey + ".value");
            if (cachedValue != null) {
                this.statuses.put(style, new Status(source, new Skin(cachedValue, this.database.setting(cacheKey + ".signature")), null));
                return;
            }
        }

        if (source.startsWith("image:") && mineskinKey.isBlank()) {
            this.statuses.put(style, new Status(source, null,
                "set skins.mineskin-api-key (free at mineskin.org) or paste value + signature instead"));
            this.logger.warning("The " + style.id() + " skin needs skins.mineskin-api-key in config.yml (free at https://account.mineskin.org), "
                + "or paste its value + signature from mineskin.org.");
            return;
        }

        this.statuses.put(style, new Status(source, null, "still loading"));
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            Skin skin = null;
            String problem = null;
            try {
                skin = source.startsWith("account:")
                    ? accountSkin(account)
                    : this.mineskin(source.substring("image:".length()), mineskinKey, style);
                if (skin == null) {
                    problem = source.startsWith("account:") ? "that account has no skin or doesn't exist" : "MineSkin returned no skin";
                }
            } catch (final Exception e) {
                problem = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                this.logger.log(Level.WARNING, "Could not load the " + style.id() + " skin from " + source, e);
            }
            final Skin found = skin;
            final String failure = problem;
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                this.statuses.put(style, new Status(source, found, failure));
                if (found != null) {
                    this.database.setting(cacheKey + ".source", source);
                    this.database.setting(cacheKey + ".value", found.value());
                    this.database.setting(cacheKey + ".signature", found.signature());
                    this.logger.info("Loaded the " + style.id() + " skin.");
                } else {
                    this.logger.warning("Could not load the " + style.id() + " skin: " + failure);
                }
            });
        });
    }

    /** The raw skin image for a NameMC skin link or id, e.g. https://namemc.com/skin/3d6175ba0c5f01f5. */
    static @Nullable String namemcImage(final String linkOrId) {
        final Matcher matcher = NAMEMC_ID.matcher(linkOrId.toLowerCase());
        String id = null;
        while (matcher.find()) {
            id = matcher.group(1);
        }
        return id == null ? null : "https://s.namemc.com/i/" + id + ".png";
    }

    /** Blocking Mojang lookup; only call off the main thread. */
    private static @Nullable Skin accountSkin(final String account) {
        final PlayerProfile profile = Bukkit.createProfile(account);
        return profile.complete(true, true) ? Skin.of(profile) : null;
    }

    /** Asks MineSkin to sign a skin image. Blocking; only call off the main thread. */
    private @Nullable Skin mineskin(final String imageUrl, final String key, final NickStyle style) throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty("url", imageUrl);
        body.addProperty("name", "rename-" + style.id());
        body.addProperty("visibility", "unlisted");
        final HttpRequest request = HttpRequest.newBuilder(URI.create(MINESKIN_GENERATE))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "Rename/" + this.plugin.getPluginMeta().getVersion())
            .header("Authorization", "Bearer " + key.strip())
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        final HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("MineSkin answered HTTP " + response.statusCode() + ": " + shorten(response.body()));
        }
        return parseMineskin(response.body());
    }

    /** Reads the signed texture out of a MineSkin response (v2 layout, with the v1 layout as a fallback). */
    static @Nullable Skin parseMineskin(final String json) {
        final JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        final JsonObject data = path(root, "skin", "texture", "data");
        final JsonObject texture = data != null ? data : path(root, "data", "texture");
        if (texture == null || !texture.has("value")) {
            return null;
        }
        final JsonElement signature = texture.get("signature");
        return new Skin(texture.get("value").getAsString(), signature == null || signature.isJsonNull() ? null : signature.getAsString());
    }

    private static @Nullable JsonObject path(final JsonObject root, final String... keys) {
        JsonObject current = root;
        for (final String key : keys) {
            final JsonElement next = current.get(key);
            if (next == null || !next.isJsonObject()) {
                return null;
            }
            current = next.getAsJsonObject();
        }
        return current;
    }

    private static String shorten(final String text) {
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
