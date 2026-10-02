package dev.exiledddev.rename.nick;

import java.util.List;
import java.util.Random;

/**
 * Generic nicknames: up to three parts, picked at random, e.g. Gamer+Boy+9718, Great+Lucas,
 * Mace+God+YT. Which parts appear is random too, but they always keep their order (first, middle,
 * last). With these lists there are millions of possible names.
 */
public final class GenericNames {

    /** Mostly adjectives and prefixes. */
    static final List<String> FIRST = List.of(
        "Great", "Swift", "Silent", "Brave", "Lucky", "Happy", "Cosmic", "Mighty", "Sneaky", "Epic",
        "Royal", "Fuzzy", "Golden", "Shadow", "Crimson", "Frosty", "Turbo", "Mega", "Super", "Hyper",
        "Ultra", "Pixel", "Rapid", "Wild", "Clever", "Dizzy", "Fancy", "Jolly", "Lazy", "Lone",
        "Lunar", "Solar", "Mystic", "Noble", "Quick", "Rusty", "Salty", "Shiny", "Sleepy", "Spicy",
        "Stormy", "Sunny", "Tiny", "Witty", "Zany", "Blue", "Red", "Dark", "Iron", "Stone",
        "Ender", "Nether", "Diamond", "Emerald", "Copper", "Atomic", "Retro", "Neo", "Night", "Sky",
        "Bold", "Calm", "Cool", "Grumpy", "Speedy", "Snowy", "Rocket", "Mace", "Gamer", "Real",
        "The", "Mr", "Captain", "Sir", "Little", "Big", "Hidden", "Wandering", "Ancient", "Mossy"
    );

    /** Mostly nouns and names. */
    static final List<String> MIDDLE = List.of(
        "Boy", "Girl", "Gamer", "Lucas", "Fox", "Wolf", "Panda", "Tiger", "Dragon", "Knight",
        "Wizard", "Ninja", "Pirate", "Miner", "Builder", "Crafter", "Archer", "Ranger", "Hunter", "Creeper",
        "Slime", "Golem", "Bee", "Otter", "Duck", "Penguin", "Falcon", "Raven", "Bear", "Llama",
        "Axolotl", "Phoenix", "Viking", "Samurai", "Goblin", "Sheep", "Cookie", "Potato", "Taco", "Waffle",
        "Muffin", "Pickle", "Noodle", "Nugget", "Biscuit", "Blaze", "Ghast", "Warden", "Sniffer", "Turtle",
        "Frog", "Bunny", "Kitten", "Puppy", "Shark", "Whale", "Comet", "Storm", "Spark", "Flame",
        "Pilot", "Rider", "Chef", "Legend", "Hero", "God", "King", "Queen", "Lord", "Master",
        "Boss", "Ace", "Pro", "Max", "Leo", "Sam", "Finn", "Jack", "Mia", "Zoe"
    );

    /** Word endings; most of the time a number is used instead. */
    static final List<String> LAST = List.of(
        "YT", "TV", "MC", "XD", "HD", "Pro", "GG", "OP", "Live", "Plays", "Gaming", "X", "Z", "_", "Jr", "HQ"
    );

    /** How often the last part is a number (like 9718) rather than a word from {@link #LAST}. */
    private static final double NUMBER_CHANCE = 0.6;

    private GenericNames() {
    }

    public static String generate(final Random random) {
        while (true) {
            // Which of {first, middle, last} to use: bit 0 = first, 1 = middle, 2 = last.
            final int parts = pickParts(random);
            final StringBuilder name = new StringBuilder();
            if ((parts & 1) != 0) {
                name.append(pick(FIRST, random));
            }
            if ((parts & 2) != 0) {
                name.append(pick(MIDDLE, random));
            }
            if ((parts & 4) != 0) {
                name.append(random.nextDouble() < NUMBER_CHANCE ? String.valueOf(1 + random.nextInt(9999)) : pick(LAST, random));
            }
            if (name.length() >= NickStyle.MIN_LENGTH && name.length() <= NickStyle.MAX_LENGTH) {
                return name.toString();
            }
        }
    }

    /**
     * One part 10% of the time, two parts 45%, all three 45%. Single parts have the fewest
     * combinations, so keeping them rare keeps names varied.
     */
    private static int pickParts(final Random random) {
        final double roll = random.nextDouble();
        if (roll < 0.10) {
            return ONE_PART[random.nextInt(ONE_PART.length)];
        }
        if (roll < 0.55) {
            return TWO_PARTS[random.nextInt(TWO_PARTS.length)];
        }
        return 0b111;
    }

    private static final int[] ONE_PART = {0b001, 0b010, 0b100};
    private static final int[] TWO_PARTS = {0b011, 0b101, 0b110};

    private static String pick(final List<String> options, final Random random) {
        return options.get(random.nextInt(options.size()));
    }
}
