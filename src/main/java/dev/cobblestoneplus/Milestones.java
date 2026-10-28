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
        public double ratio = 1;        // learned: how much the menu count grows per +1 counted live (used only when ratio mode is on)
        public double since;            // +1s counted live since the menu was last read
        public double countRead = -1;   // the count the menu showed when it was last read
        public transient boolean est;   // count moved on by live tracking since the last menu read
        public transient long bumpAt;   // when it last went up (the panel flashes it)
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

    // ------------------------------------------------------------ live counting
    //
    // The menu is only read when you open it. In between the count moves on by itself, one at a time:
    //   - every block you break adds 1 to its crop (2019 broken -> break one -> 2020);
    //   - every mob you kill adds 1 to the mob with the same name ("Chicken" -> Chicken);
    //   - a "Stage N ... completed" line finishes that stage at once.
    // Stage % = count / stage target (13,999 / 25,000 = 55.996%), so the bars follow. Opening the menu again puts
    // the exact count back.
    //
    // The block you break is matched to a crop by its name. Crops that are not vanilla blocks cannot be matched,
    // so those go to the crop whose "+x Crop (total)" line showed on the action bar last, and are taken back
    // if no such line follows within 3s (a block that gave nothing). A gain line that has no break before it
    // (broken by an enchant, or the break event did not fire) counts as one block itself.

    private static final class Credit {
        Track t;
        long at;
        boolean guess;
    }

    private static final java.util.ArrayDeque<Credit> credits = new java.util.ArrayDeque<>();
    private static final java.util.Set<Integer> counted = new java.util.HashSet<>();
    private static final Map<Integer, Track> near = new java.util.HashMap<>();
    private static int lastSwing = -100;
    /** The crop / mob counted last, shown first on screen. */
    static Track lastBumped;

    private static Track byName(Map<String, Track> m, String name) {
        if (name == null || name.isEmpty()) return null;
        Track t = m.get(name);
        if (t != null) return t;
        for (Track c : m.values()) if (c.name.equalsIgnoreCase(name.trim())) return c;
        return null;
    }

    private static void add(Track t, double d) {
        if (!Cfg.I.milestoneLive || d == 0) return;
        if (t.count < 0) {
            Stage n = t.next();
            if (n == null) return;
            t.count = n.target * n.pct / 100.0;
        }
        // ratio mode: one counted block / kill moves the count by what the menu showed it is really worth
        double step = Cfg.I.milestoneRatio && t.ratio > 0 ? d * t.ratio : d;
        t.count = Math.max(0, t.count + step);
        t.since += d;
        t.est = true;
        if (d > 0) {
            t.bumpAt = System.currentTimeMillis();
            lastBumped = t;
        }
        recompute(t);
        dirty = true;
    }

    private static void recompute(Track t) {
        for (int i = 0; i < STAGES; i++) {
            Stage s = t.stages[i];
            if (s == null || s.target <= 0) continue;
            boolean was = s.done();
            s.pct = Math.min(100, t.count * 100.0 / s.target);
            if (!was && s.done()) Completion.bump(t.mob ? "Mob Milestone" : "Crop Milestone", 1);
        }
    }

    /** A block was broken (by you, client side). */
    public static void onBreak(String blockName) {
        if (!Cfg.I.milestoneLive) return;
        Track t = inText(blockName, crops);
        boolean guess = false;
        if (t == null) {
            t = byName(crops, lastCrop);
            guess = true;
        }
        if (t == null) return;
        add(t, 1);
        Credit c = new Credit();
        c.t = t;
        c.at = System.currentTimeMillis();
        c.guess = guess;
        credits.add(c);
        while (credits.size() > 64) credits.poll();
    }

    // Block breaks are seen without any Fabric event: the block you look at is watched, and when its state changes
    // while you are holding attack (broken, or replaced by the server), that is one block broken.
    private static java.lang.reflect.Field hitF;
    private static boolean hitLooked;
    private static net.minecraft.core.BlockPos watchPos;
    private static net.minecraft.world.level.block.state.BlockState watchState;
    private static int lastAttack = -100;

    private static Object hitResult(Minecraft mc) throws Exception {
        if (!hitLooked) {
            hitLooked = true;
            for (Class<?> c = Minecraft.class; c != null && hitF == null; c = c.getSuperclass()) {
                try {
                    hitF = c.getDeclaredField("hitResult");
                    hitF.setAccessible(true);
                } catch (NoSuchFieldException ignored) {
                }
            }
        }
        return hitF == null ? null : hitF.get(mc);
    }

    private static void scanBreaks(Minecraft mc) throws Exception {
        if (mc.options.keyAttack.isDown()) lastAttack = tick;
        if (watchPos != null) {
            net.minecraft.world.level.block.state.BlockState now = mc.level.getBlockState(watchPos);
            if (!now.equals(watchState)) {
                if (tick - lastAttack <= 20 && !watchState.isAir()) onBreak(watchState.getBlock().getName().getString());
                watchPos = null;
                watchState = null;
            }
        }
        Object hr = hitResult(mc);
        if (hr instanceof net.minecraft.world.phys.BlockHitResult bhr && bhr.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            net.minecraft.core.BlockPos p = bhr.getBlockPos();
            if (watchPos == null || !watchPos.equals(p)) {
                watchPos = p.immutable();
                watchState = mc.level.getBlockState(p);
            }
        }
    }

    /** A "+x Crop (total)" action bar line: it confirms (or corrects) the block that was just counted. */
    public static void addCount(String name, double gained) {
        Track x = byName(crops, name);
        if (x == null) return;
        Credit c = credits.poll();
        if (c == null) add(x, 1);
        else if (c.t != x) {
            add(c.t, -1);
            add(x, 1);
        }
    }

    private static void expire() {
        long now = System.currentTimeMillis();
        while (!credits.isEmpty() && now - credits.peek().at > 3000) {
            Credit c = credits.poll();
            if (c.guess) add(c.t, -1);
        }
    }

    private static final Pattern STAGE_UP = Pattern.compile(
            "(?i)\\bstage\\s+(\\d)\\b.*\\b(?:complete[d]?|reached|unlocked|finished|achieved)|\\b(?:complete[d]?|reached|unlocked|finished|achieved)\\b.*\\bstage\\s+(\\d)\\b");

    private static Track inText(String line, Map<String, Track> m) {
        Track best = null;
        if (line == null) return null;
        String low = line.toLowerCase(Locale.ROOT);
        for (Track t : m.values()) {
            String n = t.name.toLowerCase(Locale.ROOT);
            int i = low.indexOf(n);
            if (i < 0) continue;
            boolean l = i == 0 || !Character.isLetter(low.charAt(i - 1));
            boolean r = i + n.length() >= low.length() || !Character.isLetter(low.charAt(i + n.length()));
            if (l && r && (best == null || t.name.length() > best.name.length())) best = t;
        }
        return best;
    }

    /** Chat / title / boss bar / action bar line (not player chat). True when it was understood. */
    public static boolean onText(String line) {
        if (!Cfg.I.milestoneLive || line == null || line.indexOf('\u00bb') >= 0) return false;
        Matcher su = STAGE_UP.matcher(line);
        if (su.find()) {
            Track t = inText(line, crops);
            if (t == null) t = inText(line, mobs);
            if (t != null) {
                int n = Integer.parseInt(su.group(1) != null ? su.group(1) : su.group(2));
                if (n >= 1 && n <= STAGES && t.stages[n - 1] != null && t.stages[n - 1].target > 0) {
                    if (t.count < t.stages[n - 1].target) t.count = t.stages[n - 1].target;
                    t.est = true;
                    recompute(t);
                    dirty = true;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Mob kills: a living thing near you whose name is a mob milestone ("Chicken") that dies, or vanishes right
     * next to you, shortly after you swung, counts as one kill.
     */
    private static void scanKills(Minecraft mc) {
        if (mc.player.swinging) lastSwing = tick;
        if (!Cfg.I.milestoneLive || tick % 2 != 0 || mobs.isEmpty()) return;
        boolean recent = tick - lastSwing <= 30;
        java.util.Set<Integer> present = new java.util.HashSet<>();
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.LivingEntity le) || e == mc.player) continue;
            if (e instanceof net.minecraft.world.entity.player.Player) continue;
            int id = e.getId();
            present.add(id);
            if (e.distanceTo(mc.player) > 10) continue;
            if (le.isDeadOrDying()) {
                near.remove(id);
                if (counted.add(id) && recent) {
                    Track t = inText(e.getName().getString(), mobs);
                    if (t != null) add(t, 1);
                }
                continue;
            }
            if (!near.containsKey(id)) {
                Track t = inText(e.getName().getString(), mobs);
                if (t != null) near.put(id, t);
            }
        }
        // gone without ever showing as dead (the server removes it at once): killed if it was right next to you
        java.util.Iterator<Map.Entry<Integer, Track>> it = near.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Track> en = it.next();
            if (present.contains(en.getKey())) continue;
            it.remove();
            if (recent && counted.add(en.getKey())) add(en.getValue(), 1);
        }
        counted.retainAll(present);
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
        if (t != null && t.world() > 0) Worlds.noteCrop(t.world());
        if (t != null && t.world() > 0 && t.world() != lastWorld) {
            lastWorld = t.world();
            dirty = true;
        }
    }

    /** World shown on screen: the fixed one from settings, else the world of the crop you are farming. 0 = unknown. */
    public static int currentWorld() {
        if (Cfg.I.milestoneWorld > 0) return Cfg.I.milestoneWorld;
        int cw = Worlds.current();
        if (cw > 0) return cw;
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

    /** {crops left, mobs left, crops known, mobs known} for one world (left = not completely finished). */
    public static int[] summary(int world) {
        int[] r = new int[4];
        for (Track t : forWorld(false, world)) {
            r[2]++;
            if (!t.complete()) r[0]++;
        }
        for (Track t : forWorld(true, world)) {
            r[3]++;
            if (!t.complete()) r[1]++;
        }
        return r;
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
        expire();
        if (mc.level != null) {
            try {
                scanBreaks(mc);
                scanKills(mc);
            } catch (Throwable t) {
                if (tick % 600 == 0) CobblestonePlus.LOG.warn("Kill counting failed", t);
            }
        }
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
                        double nc = Nums.parse(c.group(1));
                        // learn the ratio (always, so it is ready when ratio mode is switched on)
                        if (t.since >= 1 && t.countRead >= 0 && nc > t.countRead) {
                            double r = (nc - t.countRead) / t.since;
                            if (r > 0.01 && r < 100) t.ratio = Math.abs(r - 1) < 0.02 ? 1 : (t.ratio * 0.5 + r * 0.5);
                        }
                        t.count = nc;
                        t.countRead = nc;
                        t.since = 0;
                        t.est = false;
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
