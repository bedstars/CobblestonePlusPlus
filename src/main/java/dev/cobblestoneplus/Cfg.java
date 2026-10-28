package dev.cobblestoneplus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Persistent settings (config/cobblestoneplus/config.json). */
public final class Cfg {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static Cfg I = new Cfg();

    /** Where a panel sits (0..1 across the free space, so 1 = flush right/bottom) and its own scale. */
    public static final class W {
        public double fx, fy;
        public float scale = 1f;

        public W() {}

        W(double fx, double fy) {
            this.fx = fx;
            this.fy = fy;
        }
    }

    // ---- layout ----
    public W backpack = new W(0.0, 0.15);
    public W boosters = new W(1.0, 0.10);
    public W pins = new W(0.0, 0.55);
    public W treasure = new W(1.0, 0.45);
    public W shiny = new W(0.5, 0.0);
    public double textScale = 1.0;   // global text size multiplier
    public boolean autoShrink = true; // shrink panels when the game window / GUI area gets smaller
    public int refWidth = 640;        // GUI width (in GUI units) at which panels are 100% size

    // ---- backpack HUD ----
    public boolean backpackHud = true;
    public String backpackMode = "recent"; // recent = items gained lately, all = whole backpack
    public int backpackSecs = 8;
    public int backpackMax = 8;
    public boolean backpackValue = true;

    // ---- other panels ----
    public boolean boostersHud = true;
    public boolean pinsHud = true;
    public boolean treasureHud = true;
    public String treasureMode = "completion"; // completion = 1 of each relic, max = every relic at max level
    public boolean shinyHud = true;
    public boolean shinyEcho = true;
    public boolean shinyRush = true;
    public int shinyRange = 160;

    public static Path dir() {
        try {
            Path d = FabricLoader.getInstance().getConfigDir().resolve("cobblestoneplus");
            Files.createDirectories(d);
            return d;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void load() {
        try {
            Path p = dir().resolve("config.json");
            if (Files.exists(p)) {
                Cfg c = GSON.fromJson(Files.readString(p), Cfg.class);
                if (c != null) I = c;
            }
        } catch (Throwable t) {
            CobblestonePlus.LOG.warn("Could not read config, using defaults", t);
            I = new Cfg();
        }
        if (I.backpackMode == null) I.backpackMode = "recent";
        if (I.treasureMode == null) I.treasureMode = "completion";
        if (I.backpack == null) I.backpack = new W(0.0, 0.15);
        if (I.boosters == null) I.boosters = new W(1.0, 0.10);
        if (I.pins == null) I.pins = new W(0.0, 0.55);
        if (I.treasure == null) I.treasure = new W(1.0, 0.45);
        if (I.shiny == null) I.shiny = new W(0.5, 0.0);
        if (I.textScale < 0.3) I.textScale = 1.0;
        if (I.refWidth < 160) I.refWidth = 640;
    }

    public static void save() {
        try {
            Files.writeString(dir().resolve("config.json"), GSON.toJson(I));
        } catch (Throwable t) {
            CobblestonePlus.LOG.warn("Could not save config", t);
        }
    }
}
