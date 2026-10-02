package dev.exiledddev.rename.nick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NickStyleTest {

    @ParameterizedTest
    @EnumSource(NickStyle.class)
    void everyStyleMakesValidMinecraftUsernames(final NickStyle style) {
        final Random random = new Random(1);
        for (int i = 0; i < 5000; i++) {
            final String name = style.generate(random);
            assertNull(NickNames.validate(name), style + " made " + name);
        }
    }

    @Test
    void binaryIsOnlyOnesAndZerosOfEveryLength() {
        final Random random = new Random(2);
        final Set<Integer> lengths = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            final String name = NickStyle.BINARY.generate(random);
            assertTrue(name.matches("[01]{3,16}"), name);
            lengths.add(name.length());
        }
        assertEquals(14, lengths.size(), "every length from 3 to 16 should come up");
    }

    @Test
    void obscuredUsesTheFullLength() {
        assertEquals(16, NickStyle.OBSCURED.generate(new Random(3)).length());
        assertTrue(NickStyle.OBSCURED.scrambled());
        assertFalse(NickStyle.GENERIC.scrambled());
    }

    @Test
    void unsettlingMixesCharacterKinds() {
        final Random random = new Random(4);
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        for (int i = 0; i < 200; i++) {
            final String name = NickStyle.UNSETTLING.generate(random);
            upper |= !name.equals(name.toLowerCase(Locale.ROOT));
            lower |= !name.equals(name.toUpperCase(Locale.ROOT));
            digit |= name.chars().anyMatch(Character::isDigit);
        }
        assertTrue(upper && lower && digit);
    }

    @Test
    void genericNamesAreVaried() {
        final Random random = new Random(5);
        final Set<String> names = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            names.add(GenericNames.generate(random));
        }
        assertTrue(names.size() > 1800, "only " + names.size() + " different names out of 2000");
    }

    @Test
    void genericHasAtLeastTenThousandCombinations() {
        final long numbersAndWords = 9999L + GenericNames.LAST.size();
        final long threeParts = (long) GenericNames.FIRST.size() * GenericNames.MIDDLE.size() * numbersAndWords;
        assertTrue(threeParts >= 10_000);
        assertTrue((long) GenericNames.FIRST.size() * GenericNames.MIDDLE.size() >= 1_000);
    }

    @Test
    void genericPartsAreCleanAndUnique() {
        final List<String> blocked = List.of("fuck", "shit", "bitch", "cunt", "dick", "cock", "pussy", "nigg", "fag", "slut", "whore", "rape", "nazi", "porn", "sex", "kill", "die", "ass", "hitler", "retard");
        Stream.of(GenericNames.FIRST, GenericNames.MIDDLE, GenericNames.LAST).forEach(parts -> {
            assertEquals(parts.size(), new HashSet<>(parts).size(), "duplicate part in " + parts);
            for (final String part : parts) {
                assertTrue(part.matches("[A-Za-z0-9_]{1,10}"), part);
                for (final String word : blocked) {
                    assertFalse(part.toLowerCase(Locale.ROOT).contains(word), part + " contains " + word);
                }
            }
        });
    }

    @Test
    void parsesStyles() {
        assertEquals(NickStyle.OBSCURED, NickStyle.parse(" Obscured "));
        assertNull(NickStyle.parse("fancy"));
    }

    @Test
    void validatesCustomNicknames() {
        assertNull(NickNames.validate("Great_Lucas7"));
        assertNotNull(NickNames.validate("ab"));
        assertNotNull(NickNames.validate("way_too_long_nickname"));
        assertNotNull(NickNames.validate("bad name"));
        assertNotNull(NickNames.validate("bad-name"));
    }
}
