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
    public W milestones = new W(0.0, 0.32);
    public W completion = new W(0.5, 0.06);
    public boolean completionHud = true;
    public String focus = "";             // progression part being tracked; "" = global
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
    public boolean autoMenus = false;     // allow the mod to open /stats /treasures by itself (interrupts farming) - off by default
    public boolean boostersAuto = true;   // keep the multiplier in sync by asking the server for /stats when something changes
    public int boostersEverySecs = 90;    // and at least this often
    public boolean pinsHud = true;
    public boolean treasureHud = true;
    public String treasureMode = "completion"; // completion = 1 of each relic, max = every relic at max level
    public boolean shinyHud = true;
    public boolean shinyEcho = true;
    public boolean shinyRush = true;
    public int shinyRange = 160;
    public boolean shinyNeedRings = true; // a yellow block only counts as RUSH when the yellow rings are around it
    public int shinyRingMin = 5;          // how many ring pieces (display entities) must be around it
    public boolean shinyBlueEcho = true;  // a steady blue / cyan glow also counts as ECHO

    // ---- milestones ----
    public boolean milestonesHud = true;
    public int milestoneWorld = 0;        // 0 = follow the crop you are farming, 1..worlds = fixed
    public boolean milestoneMobs = true;
    public boolean milestoneDone = false; // also list finished crops / mobs
    public int milestoneRows = 10;        // max unfinished rows per section
    public int treasureWorld = 0;         // 0 = the world you are in, 1..worlds = fixed
    public boolean treasureOthers = true; // one line per other world
    public int treasureRows = 12;         // max missing / upgrade rows
    public String milestoneSource = "blocks"; // blocks = the block you break yourself; farmed = growth of the sidebar FARMED line
    public int breakRadius = 12;          // how far around you broken blocks are counted
    public boolean milestoneRatio = false; // live counting uses the ratio learned from the menu instead of a plain +1 (overrides it)
    public boolean milestoneLive = true;  // count crops / kills up on screen from the action bar and chat, between menu reads
    public boolean feedLog = false;       // write every line no reader understood to feed.log (to teach the mod the server wording)
    public boolean milestoneOthers = true; // one summary line per other world
    public int worlds = 6;                // Overworld, Frost Castle, Swamp, Honey Tree, Infernal Citadel, The Library
    public int worldSize = 8;             // crops / mobs per world

    // ---- look ----
    public int panelOpacity = 100;        // 0..100 background of the on-screen panels (0 = only the text)
    public int menuOpacity = 95;          // 0..100 background of the settings menu

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
        if (I.milestones == null) I.milestones = new W(0.0, 0.32);
        if (I.completion == null) I.completion = new W(0.5, 0.06);
        if (I.focus == null) I.focus = "";
        if (I.milestoneSource == null || (!I.milestoneSource.equals("blocks") && !I.milestoneSource.equals("farmed"))) I.milestoneSource = "blocks";
        if (I.breakRadius < 3 || I.breakRadius > 16) I.breakRadius = 12;
        if (I.worlds < 6) I.worlds = 6;
        I.panelOpacity = Math.max(0, Math.min(100, I.panelOpacity));
        I.menuOpacity = Math.max(0, Math.min(100, I.menuOpacity));
        if (I.boostersEverySecs < 5) I.boostersEverySecs = 90;
        if (I.treasureRows < 1) I.treasureRows = 12;
        if (I.worldSize < 1) I.worldSize = 8;
        if (I.milestoneRows < 1) I.milestoneRows = 10;
        if (I.shinyRingMin < 1) I.shinyRingMin = 5;
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
