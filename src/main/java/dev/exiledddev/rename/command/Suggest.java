package dev.exiledddev.rename.command;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

final class Suggest {

    private Suggest() {
    }

    /** Suggests the options that start with what has been typed so far, ignoring case. */
    static CompletableFuture<Suggestions> matching(final SuggestionsBuilder builder, final Iterable<String> options) {
        final String typed = builder.getRemainingLowerCase();
        for (final String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(option);
            }
        }
        return builder.buildFuture();
    }
}
