package dev.cobblestoneplus;

import java.util.Locale;

/**
 * The real worlds, in order. Anything else the server lists next to them (charms, seals, locked places...)
 * is NOT a world and is ignored everywhere.
 *
 *   1 Overworld   2 Frost Castle   3 Swamp   4 Honey Tree   5 Infernal Citadel   6 The Library   (+ Archipelago = fishing)
 *
 * "Which world am I in" is decided by whichever signal changed last:
 *   - the crop you are farming (action bar / multiplier panel) -> milestone index -> world
 *   - the world the Treasury item says is active ("Active in Honey Tree:")
 */
final class Worlds {
    private Worlds() {}

    static final String[] NAMES = {"Overworld", "Frost Castle", "Swamp", "Honey Tree", "Infernal Citadel", "The Library"};
    static final String FISHING = "Archipelago";

    private static int cropWorld, dimWorld;
    private static long cropAt, dimAt;
    private static String source = "unknown";

    static int count() {
        return Math.max(1, Math.min(NAMES.length, Cfg.I.worlds));
    }

    static String name(int w) {
        return w >= 1 && w <= NAMES.length ? NAMES[w - 1] : "World " + w;
    }

    private static String norm(String s) {
        String t = s.replace("(Locked)", "").trim().toLowerCase(Locale.ROOT);
        return t.startsWith("the ") ? t.substring(4) : t;
    }

    /** 1..6 for a real world's name, 0 for anything else. */
    static int byName(String s) {
        if (s == null) return 0;
        String t = norm(s);
        if (t.isEmpty()) return 0;
        for (int i = 0; i < NAMES.length; i++) if (norm(NAMES[i]).equals(t)) return i + 1;
        return 0;
    }

    static void noteCrop(int w) {
        if (w > 0 && w != cropWorld) {
            cropWorld = w;
            cropAt = System.currentTimeMillis();
        }
    }

    static void noteDim(String dim) {
        int w = byName(dim);
        if (w > 0 && w != dimWorld) {
            dimWorld = w;
            dimAt = System.currentTimeMillis();
        }
    }

    /** The world you are in right now (0 = not known yet). */
    static int current() {
        if (cropWorld == 0 && dimWorld == 0) return 0;
        if (cropWorld == 0) {
            source = "treasury item";
            return dimWorld;
        }
        if (dimWorld == 0) {
            source = "crop you farm";
            return cropWorld;
        }
        if (cropAt >= dimAt) {
            source = "crop you farm";
            return cropWorld;
        }
        source = "treasury item";
        return dimWorld;
    }

    static String describe() {
        int w = current();
        return w == 0 ? "not known yet (farm a crop or open the Treasury)" : "world " + w + " " + name(w) + "  (from the " + source + ")";
    }
}
