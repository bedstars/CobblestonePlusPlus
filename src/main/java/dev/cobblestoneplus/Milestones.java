package dev.cobblestoneplus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Milestones, learned from the "Farming Milestones" and "Mob Milestones" menus.
 *
 * Menu layout (6-row chest): column 0 = list icon / arrows, columns 1..7 = seven crops (or mobs) per page,
 * row 0 = the head item ("Wheat Milestones": "13,999 Farmed", "0/10 Idols", "3/3 Treasure Level"),
 * rows 1..5 = stages 1..5 ("Wheat Stage 4": "25,000 (55.996%)", "Reward: 5x Wheat XP").
 *
 * Every crop / mob gets a global index  (page - 1) * 7 + column.  A world holds {@link Cfg#worldSize} of them
 * (8 by default): world 1 = wheat..pineapple / cow..horse, world 2 = froglight..quartz / frog..iron golem,
 * world 3 = clay..verdant froglight / turtle..witch, world 4 = hay bale..honeycomb / llama..honey bear.
 * Open each page of /milestones once and it is remembered.
 */
public final class Milestones {
    private Milestones() {}

    public static final int STAGES = 5, PER_PAGE = 7;

    public static final class Stage {
        public double target;
        public double pct;
        public String reward = "";

        public boolean done() {
            return pct >= 99.99;
        }
    }

    public static final class Track {
        public String name;
        public boolean mob;
        public int idx = -1;
        public double count = -1;
        public int idols = -1, idolsMax = -1, treasure = -1, treasureMax = -1;
        public String bonus = "";
        public Stage[] stages = new Stage[STAGES];

        public int world() {
            if (idx < 0) return 0;
            int size = Math.max(1, Cfg.I.worldSize);
            return Math.min(Math.max(1, Cfg.I.worlds), idx / size + 1);
        }

        public int stagesDone() {
            int n = 0;
            for (Stage s : stages) if (s != null && s.done()) n++;
            return n;
        }

        public boolean allStagesKnown() {
            for (Stage s : stages) if (s == null) return false;
            return true;
        }

        /** First stage that is not finished yet, or null. */
        public Stage next() {
            for (Stage s : stages) if (s != null && !s.done()) return s;
            return null;
        }

        /** Current count towards the next stage (exact when the head item was read, else derived from the percentage). */
        public double cur() {
            if (count >= 0) return count;
            Stage n = next();
            return n == null ? -1 : n.target * n.pct / 100.0;
        }

        public boolean idolsLeft() {
            return idols >= 0 && idolsMax > 0 && idols < idolsMax;
        }

        public boolean treasureLeft() {
            return treasure >= 0 && treasureMax > 0 && treasure < treasureMax;
        }

        public boolean complete() {
            return allStagesKnown() && stagesDone() == STAGES && !idolsLeft() && !treasureLeft();
        }
    }

    private static final class Save {
        Map<String, Track> crops = new LinkedHashMap<>();
        Map<String, Track> mobs = new LinkedHashMap<>();
        int lastWorld;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final Map<String, Track> crops = new LinkedHashMap<>();
    public static final Map<String, Track> mobs = new LinkedHashMap<>();
    private static int lastWorld;
    private static int tick;
    private static boolean dirty;

    private static final Pattern TITLE = Pattern.compile("^(Farming|Mob)\\s+Milestones\\s*\\(Page\\s+(\\d+)\\)");
    private static final Pattern STAGE_NAME = Pattern.compile("^(.+?)\\s+Stage\\s+(\\d+)$");
    private static final Pattern STAGE_LINE = Pattern.compile("^\\s*(" + Nums.NUM + ")\\s*(?:[A-Za-z]+\\s*)?\\((\\d+(?:\\.\\d+)?)%\\)");
    private static final Pattern COUNT = Pattern.compile("^\\s*(" + Nums.NUM + ")\\s+(?:Farmed|Slain|Kills|Killed|Mined|Harvested)");
    private static final Pattern IDOLS = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)\\s+Idols?");
    private static final Pattern TREASURE = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)\\s+Treasure");

    // ------------------------------------------------------------ storage

    public static void load() {
        try {
            Path p = Cfg.dir().resolve("milestones.json");
            if (Files.exists(p)) {
                Save s = GSON.fromJson(Files.readString(p), Save.class);
                if (s != null) {
                    crops.clear();
                    mobs.clear();
                    if (s.crops != null) crops.putAll(s.crops);
                    if (s.mobs != null) mobs.putAll(s.mobs);
                    lastWorld = s.lastWorld;
                    fix(crops);
                    fix(mobs);
                }
            }
        } catch (Throwable t) {
            CobblestonePlus.LOG.warn("Could not read milestones.json", t);
        }
    }

    private static void fix(Map<String, Track> m) {
        for (Track t : m.values()) {
            if (t.stages == null || t.stages.length != STAGES) {
                Stage[] ns = new Stage[STAGES];
                if (t.stages != null) System.arraycopy(t.stages, 0, ns, 0, Math.min(t.stages.length, STAGES));
                t.stages = ns;
            }
        }
    }

    public static void save() {
        try {
            Save s = new Save();
            s.crops = crops;
            s.mobs = mobs;
            s.lastWorld = lastWorld;
            Files.writeString(Cfg.dir().resolve("milestones.json"), GSON.toJson(s));
            dirty = false;
        } catch (Throwable t) {
            CobblestonePlus.LOG.warn("Could not save milestones.json", t);
        }
    }

    public static void forget() {
        crops.clear();
        mobs.clear();
        lastWorld = 0;
        save();
    }

    // ------------------------------------------------------------ queries

    public static Track find(String name) {
        if (name == null || name.isEmpty()) return null;
        Track t = crops.get(name);
        if (t != null) return t;
        for (Track c : crops.values()) if (c.name.equalsIgnoreCase(name.trim())) return c;
        return null;
    }

    /** Called with the name of every crop / drop that shows up on the action bar: farming a crop tells us the world. */
    private static String lastCrop = "";

    public static void touch() {
        dirty = true;
    }

    public static void onGain(String name) {
        Track t = find(name);
        if (t != null && !t.name.equals(lastCrop)) {
            boolean first = lastCrop.isEmpty();
            lastCrop = t.name;
            if (!first) Boosters.request(); // another crop = another multiplier
        }
        if (t != null && t.world() > 0 && t.world() != lastWorld) {
            lastWorld = t.world();
            dirty = true;
        }
    }

    /** World shown on screen: the fixed one from settings, else the world of the crop you are farming. 0 = unknown. */
    public static int currentWorld() {
        if (Cfg.I.milestoneWorld > 0) return Cfg.I.milestoneWorld;
        if (lastWorld > 0) return lastWorld;
        Track t = find(Boosters.data.crop);
        return t == null ? 0 : t.world();
    }

    public static int lowestWorld() {
        int best = 0;
        for (Map<String, Track> m : List.of(crops, mobs)) {
            for (Track t : m.values()) {
                int w = t.world();
                if (w > 0 && (best == 0 || w < best)) best = w;
            }
        }
        return best;
    }

    public static List<Track> forWorld(boolean mob, int world) {
        List<Track> out = new ArrayList<>();
        for (Track t : (mob ? mobs : crops).values()) if (t.world() == world) out.add(t);
        out.sort(Comparator.comparingInt((Track t) -> t.idx));
        return out;
    }

    // ------------------------------------------------------------ scanning

    public static void onTick(Minecraft mc) {
        if (mc.player == null) return;
        tick++;
        if (tick % 5 == 0) scanMenu(mc);
        if (dirty && tick % 200 == 0) save();
    }

    private static List<String> lore(ItemStack st) {
        List<String> out = new ArrayList<>();
        ItemLore l = st.get(DataComponents.LORE);
        if (l == null) return out;
        for (Component c : l.lines()) out.add(c.getString());
        return out;
    }

    private static Track track(boolean mob, String name, int idx) {
        Map<String, Track> m = mob ? mobs : crops;
        Track t = m.get(name);
        if (t == null) {
            t = new Track();
            t.name = name;
            t.mob = mob;
            m.put(name, t);
            dirty = true;
        }
        if (t.idx != idx) {
            t.idx = idx;
            dirty = true;
        }
        return t;
    }

    private static void scanMenu(Minecraft mc) {
        if (!(Scr.get(mc) instanceof AbstractContainerScreen<?> gs)) return;
        Matcher tm = TITLE.matcher(gs.getTitle().getString().trim());
        if (!tm.find()) return;
        boolean mob = tm.group(1).equals("Mob");
        int page = Integer.parseInt(tm.group(2));
        List<Slot> slots = gs.getMenu().slots;
        for (int i = 0; i < slots.size() && i < 54; i++) {
            Slot s = slots.get(i);
            if (s.container == mc.player.getInventory()) break;
            int row = i / 9, col = i % 9;
            if (col < 1 || col > PER_PAGE) continue;
            ItemStack st = s.getItem();
            if (st.isEmpty()) continue;
            String name = st.getHoverName().getString().trim();
            List<String> lore = lore(st);
            int idx = (page - 1) * PER_PAGE + (col - 1);

            if (row == 0) {
                if (!name.endsWith("Milestones")) continue;
                String base = name.substring(0, name.length() - "Milestones".length()).trim();
                if (base.isEmpty()) continue;
                Track t = track(mob, base, idx);
                for (String raw : lore) {
                    String line = raw.trim();
                    Matcher c = COUNT.matcher(line);
                    if (c.find()) {
                        t.count = Nums.parse(c.group(1));
                        continue;
                    }
                    Matcher id = IDOLS.matcher(line);
                    if (id.find()) {
                        t.idols = Integer.parseInt(id.group(1));
                        t.idolsMax = Integer.parseInt(id.group(2));
                        continue;
                    }
                    Matcher tr = TREASURE.matcher(line);
                    if (tr.find()) {
                        t.treasure = Integer.parseInt(tr.group(1));
                        t.treasureMax = Integer.parseInt(tr.group(2));
                        continue;
                    }
                    if (line.startsWith("Drop Bonus")) t.bonus = line.replaceFirst("^Drop Bonus:?\\s*", "");
                }
                dirty = true;
            } else {
                Matcher sn = STAGE_NAME.matcher(name);
                if (!sn.matches()) continue;
                int n = Integer.parseInt(sn.group(2));
                if (n < 1 || n > STAGES) continue;
                Track t = track(mob, sn.group(1).trim(), idx);
                Stage stg = new Stage();
                boolean got = false;
                for (String raw : lore) {
                    String line = raw.trim();
                    if (line.startsWith("Reward")) {
                        stg.reward = line.replaceFirst("^Reward:?\\s*", "");
                        continue;
                    }
                    Matcher m = STAGE_LINE.matcher(line);
                    if (!got && m.find()) {
                        stg.target = Nums.parse(m.group(1));
                        try {
                            stg.pct = Double.parseDouble(m.group(2).toLowerCase(Locale.ROOT));
                        } catch (NumberFormatException e) {
                            stg.pct = 0;
                        }
                        got = true;
                    }
                }
                if (got) {
                    t.stages[n - 1] = stg;
                    dirty = true;
                }
            }
        }
    }
}
