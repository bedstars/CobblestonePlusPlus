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
        Map<String, String> blocks = new LinkedHashMap<>();
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
                    learnedNames.clear();
                    if (s.blocks != null) learnedNames.putAll(s.blocks);
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
            s.blocks = learnedNames;
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
    // The menu is only read when you open it. In between the count moves on by itself, like vanilla would:
    //   - BLOCKS (default): every crop block that breaks within reach of you adds 1 to its crop, whether you or an
    //     enchant broke it (the server counts them all). Source "mine": only the block under your crosshair.
    //   - MOBS: a mob with a milestone name ("Chicken") that dies next to you counts 1.
    //   - A "Stage N ... completed" line finishes that stage at once.
    // Stage % = count / stage target (13,999 / 25,000 = 55.996%), so the bars follow. Opening the menu again puts
    // the exact count back. The block is matched to its crop by name; a block with a name that is not a crop
    // (a custom looking crop) that you break counts for the crop of the last "+x Crop (total)" action bar line,
    // if that showed within 4s.

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

    private static String norm(String s) {
        String n = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return n.length() > 3 && n.endsWith("s") ? n.substring(0, n.length() - 1) : n;
    }

    /** Finds the crop a block / drop name belongs to: same name, then a name inside the other, then plurals. */
    private static Track matchCrop(String name) {
        if (name == null || name.isEmpty()) return null;
        String nn = norm(name);
        for (Track t : crops.values()) if (norm(t.name).equals(nn)) return t;
        Track t = inText(name, crops);
        if (t != null) return t;
        Track best = null;
        for (Track c : crops.values()) {
            String cn = norm(c.name);
            if (cn.length() < 4 || nn.length() < 4) continue;
            if ((nn.contains(cn) || cn.contains(nn)) && (best == null || cn.length() > norm(best.name).length())) best = c;
        }
        return best;
    }

    private static final java.util.ArrayDeque<String> trail = new java.util.ArrayDeque<>();

    private static void ev(String s) {
        trail.add(s);
        while (trail.size() > 12) trail.poll();
        Feed.note("live", s);
    }

    /** For /cpp milestones debug. */
    public static List<String> debug() {
        List<String> out = new ArrayList<>();
        out.add("live counting: " + (Cfg.I.milestoneLive ? "on" : "OFF") + ", source: " + Cfg.I.milestoneSource
                + ", ratio mode: " + (Cfg.I.milestoneRatio ? "on" : "off") + ", crops known: " + crops.size() + ", mobs known: " + mobs.size()
                + ", blocks watched: " + watch.size());
        out.add("last counted: " + (lastBumped == null ? "nothing yet" : lastBumped.name + " = " + Nums.exact(lastBumped.count)));
        out.add("last events (newest last):");
        if (trail.isEmpty()) out.add("  none yet");
        for (String s : trail) out.add("  " + s);
        return out;
    }

    private static void add(Track t, double d) {
        add(t, d, false);
    }

    /** raw: the amount is exact (the server's own FARMED growth), never scaled by the learned ratio. */
    private static void add(Track t, double d, boolean raw) {
        if (!Cfg.I.milestoneLive || d == 0) return;
        if (t.count < 0) {
            Stage n = t.next();
            if (n == null) return;
            t.count = n.target * n.pct / 100.0;
        }
        // ratio mode: one counted block / kill moves the count by what the menu showed it is really worth
        double step = !raw && Cfg.I.milestoneRatio && t.ratio > 0 ? d * t.ratio : d;
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

    // ---- blocks: only the block you are breaking yourself
    //
    // The block under your crosshair is watched. When it turns into air (or another block), that is one block
    // you broke (the attack key is not checked: macros / auto clickers do not press it). Blocks an enchant breaks around it are never looked at, so they are
    // not counted. (Opening /milestones puts the server's exact count back; ratio mode learns the difference.)

    private static final class Watch {
        net.minecraft.world.level.block.state.BlockState state;
        long since;
    }

    private static final Map<Long, Watch> watch = new java.util.HashMap<>();
    /** Position -> crop block seen there, for every crop block within reach of you ("blocks" source). */
    private static final Map<Long, net.minecraft.world.level.block.Block> around = new java.util.HashMap<>();
    private static final int DOWN = 8, UP = 3;
    private static final Map<net.minecraft.world.level.block.Block, Track> blockTrack = new java.util.HashMap<>();
    private static final java.util.Set<net.minecraft.world.level.block.Block> noMatch = new java.util.HashSet<>();
    private static final Map<Track, Integer> hits = new java.util.LinkedHashMap<>();
    /** Block types learned while farming: the cooldown block a broken block turns into, and the crop-like blocks. */
    private static final java.util.Set<net.minecraft.world.level.block.Block> cooldownBlocks = new java.util.HashSet<>();
    private static final java.util.Set<net.minecraft.world.level.block.Block> cropLike = new java.util.HashSet<>();
    private static net.minecraft.world.level.block.Block lastTarget;
    private static java.lang.reflect.Field hitF;
    private static boolean hitLooked;
    private static Object lastLevel;
    private static int cacheSize = -1;
    private static Track lastGain;
    private static long lastGainAt;
    private static String aimText = "no data yet", lastErr;
    private static int sessionBlocks;

    /** One short line for the bottom of the milestones panel: what the live counter sees (screenshot it when it is stuck). */
    public static String status() {
        if (!Cfg.I.milestoneLive) return "live counting off";
        if ("farmed".equals(Cfg.I.milestoneSource)) {
            if (crops.isEmpty()) return "open /milestones once";
            net.minecraft.world.level.block.Block tb = dominantAim(null);
            Track tt = tb == null ? null : trackOf(tb);
            String f = "farmed | aim: " + (tb == null ? "none" : tb.getName().getString()) + " -> " + (tt == null ? "?" : tt.name)
                    + " | " + farmedNote;
            return f.length() > 72 ? f.substring(0, 72) : f;
        }
        if (!"blocks".equals(Cfg.I.milestoneSource) && !"mine".equals(Cfg.I.milestoneSource)) return "source: " + Cfg.I.milestoneSource;
        if (lastErr != null) return "error: " + lastErr;
        if (crops.isEmpty()) return "open /milestones once";
        if (hitLooked && hitF == null) return "cannot read the block you look at";
        String s = ("mine".equals(Cfg.I.milestoneSource) ? "mine" : "all") + " | aim: " + aimText + " | counted " + sessionBlocks;
        return s.length() > 72 ? s.substring(0, 72) : s;
    }

    /** Block -> crop, learned from the "Warp to Crop" menu (its items are the crop blocks, whatever the texture pack shows). */
    private static final Map<net.minecraft.world.level.block.Block, Track> learned = new java.util.HashMap<>();
    /** Block name -> crop name, learned while mining (saved): a crop that looks like another block. */
    private static final Map<String, String> learnedNames = new LinkedHashMap<>();

    private static void learnWarp(Minecraft mc) {
        if (tick % 10 != 0 || crops.isEmpty()) return;
        if (!(Scr.get(mc) instanceof AbstractContainerScreen<?> gs)) return;
        if (!gs.getTitle().getString().toLowerCase(Locale.ROOT).contains("warp to crop")) return;
        for (Slot s : gs.getMenu().slots) {
            if (s.container == mc.player.getInventory()) continue;
            ItemStack st = s.getItem();
            if (st.isEmpty()) continue;
            Track t = matchCrop(st.getHoverName().getString());
            net.minecraft.world.level.block.Block b = net.minecraft.world.level.block.Block.byItem(st.getItem());
            if (t != null && b != net.minecraft.world.level.block.Blocks.AIR && learned.get(b) != t) {
                learned.put(b, t);
                noMatch.remove(b);
                blockTrack.remove(b);
            }
        }
    }

    /**
     * Block -> crop for crops whose block has no crop name (a texture pack / pattern block): the block that makes
     * up most of the floor around you while a "+x Crop" line is showing is that crop's block.
     */
    private static final Map<net.minecraft.world.level.block.Block, Track> freqMap = new java.util.HashMap<>();
    private static final Map<net.minecraft.world.level.block.Block, Integer> tally = new java.util.HashMap<>();
    private static int tallyTotal;

    private static Track trackOf(net.minecraft.world.level.block.Block b) {
        Track l = learned.get(b);
        if (l != null) return l;
        String ln = learnedNames.get(b.getName().getString());
        if (ln != null) {
            Track lt = find(ln);
            if (lt != null) return lt;
        }
        Track t = blockTrack.get(b);
        if (t != null) return t;
        t = freqMap.get(b);
        if (t != null) return t;
        if (noMatch.contains(b)) return null;
        t = matchCrop(b.getName().getString());
        if (t == null) noMatch.add(b);
        else blockTrack.put(b, t);
        return t;
    }

    private static Object hitResult(Minecraft mc) throws Exception {
        if (!hitLooked) {
            hitLooked = true;
            for (Class<?> c = Minecraft.class; c != null && hitF == null; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (net.minecraft.world.phys.HitResult.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        hitF = f;
                        break;
                    }
                }
            }
            if (hitF == null) ev("cannot read what you are looking at on this Minecraft version (no HitResult field)");
        }
        return hitF == null ? null : hitF.get(mc);
    }

    /**
     * Every block of a crop within reach of you: when one turns into something else (the cooldown block, or air)
     * that is one block broken, whoever broke it (you or an enchant), because the server's milestone counts them
     * all. Blocks that come back (the cooldown block turning into the crop again) are not counted.
     */
    private static void scanAround(Minecraft mc) {
        int r = Math.max(3, Math.min(16, Cfg.I.breakRadius));
        net.minecraft.core.BlockPos base = mc.player.blockPosition();
        int bx = base.getX(), by = base.getY(), bz = base.getZ();
        net.minecraft.core.BlockPos.MutableBlockPos mp = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int x = bx - r; x <= bx + r; x++) {
            for (int z = bz - r; z <= bz + r; z++) {
                for (int y = by - DOWN; y <= by + UP; y++) {
                    mp.set(x, y, z);
                    long key = mp.asLong();
                    if (!mc.level.hasChunkAt(mp)) {
                        around.remove(key);
                        continue;
                    }
                    net.minecraft.world.level.block.state.BlockState cst = mc.level.getBlockState(mp);
                    net.minecraft.world.level.block.Block cur = cst.getBlock();
                    if (y == by - 1 && !cst.isAir()) { // the floor you stand on
                        tally.merge(cur, 1, Integer::sum);
                        tallyTotal++;
                    }
                    net.minecraft.world.level.block.Block old = around.get(key);
                    Track tc = trackOf(cur);
                    if (old != null && old != cur) {
                        Track to = trackOf(old);
                        if (to != null && tc != to) hits.merge(to, 1, Integer::sum);
                    }
                    if (tc != null) {
                        if (old != cur) around.put(key, cur);
                    } else if (old != null) {
                        around.remove(key);
                    }
                }
            }
        }
        if (tick % 20 == 0) {
            if (lastGain != null && System.currentTimeMillis() - lastGainAt < 4000 && tallyTotal > 0) {
                net.minecraft.world.level.block.Block top = null;
                int best = 0;
                for (Map.Entry<net.minecraft.world.level.block.Block, Integer> e : tally.entrySet()) {
                    if (e.getValue() > best) {
                        best = e.getValue();
                        top = e.getKey();
                    }
                }
                if (top != null && best * 2 >= tallyTotal && (trackOf(top) == null || freqMap.containsKey(top)) && freqMap.get(top) != lastGain) {
                    freqMap.put(top, lastGain);
                    ev("learned: '" + top.getName().getString() + "' blocks are " + lastGain.name + " blocks");
                }
            }
            tally.clear();
            tallyTotal = 0;
            around.keySet().removeIf(k -> Math.abs(net.minecraft.core.BlockPos.getX(k) - bx) > r + 2
                    || Math.abs(net.minecraft.core.BlockPos.getZ(k) - bz) > r + 2
                    || net.minecraft.core.BlockPos.getY(k) < by - DOWN - 2
                    || net.minecraft.core.BlockPos.getY(k) > by + UP + 2);
        }
    }

    private static void scanBlocks(Minecraft mc) throws Exception {
        String src = Cfg.I.milestoneSource;
        if (!Cfg.I.milestoneLive || !("blocks".equals(src) || "mine".equals(src)) || crops.isEmpty()) return;
        boolean mine = "mine".equals(src); // only the block under your crosshair; otherwise every crop block that breaks near you
        learnWarp(mc);
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            watch.clear();
            around.clear();
            freqMap.clear();
            cooldownBlocks.clear();
            cropLike.clear();
        }
        if (crops.size() != cacheSize) { // crops learned / forgotten: names have to be matched again
            cacheSize = crops.size();
            blockTrack.clear();
            noMatch.clear();
            freqMap.clear();
        }
        long now = System.currentTimeMillis();
        boolean gainRecent = lastGain != null && now - lastGainAt < 4000;
        hits.clear();

        // 1) what happened to the blocks you were looking at
        java.util.Iterator<Map.Entry<Long, Watch>> it = watch.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Watch> en = it.next();
            Watch w = en.getValue();
            net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.of(en.getKey());
            if (now - w.since > 1500 || !mc.level.hasChunkAt(pos)) {
                it.remove();
                continue;
            }
            net.minecraft.world.level.block.state.BlockState cur = mc.level.getBlockState(pos);
            if (cur.getBlock() == w.state.getBlock()) continue;
            it.remove();
            net.minecraft.world.level.block.Block ob = w.state.getBlock(), cb = cur.getBlock();
            Track t = trackOf(ob);
            if (t != null) {
                // a block named like a crop turned into something else (in "blocks" mode the scan around you counts it)
                if (mine && (cur.isAir() || trackOf(cb) != t)) hits.merge(t, 1, Integer::sum);
            } else if (gainRecent && trackOf(cb) == null && (cropLike.contains(ob) || !cooldownBlocks.contains(ob))) {
                // the crops look like another block (a pattern block, say): a block you hit changed into the
                // cooldown block while a "+x Crop" line is showing. The block it turns into is remembered as the
                // cooldown block, so the cooldown block going back to the crop is not counted as a break.
                hits.merge(lastGain, 1, Integer::sum);
                cropLike.add(ob);
                cooldownBlocks.add(cb);
                cooldownBlocks.remove(ob);
            }
        }

        // 2) the block you are looking at now
        Object hr = hitResult(mc);
        if (hr == null) aimText = "no crosshair data";
        else if (!(hr instanceof net.minecraft.world.phys.BlockHitResult b0) || b0.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) aimText = "no block";
        if (hr instanceof net.minecraft.world.phys.BlockHitResult bhr && bhr.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            net.minecraft.core.BlockPos p = bhr.getBlockPos();
            long key = p.asLong();
            if (!watch.containsKey(key)) {
                net.minecraft.world.level.block.state.BlockState st = mc.level.getBlockState(p);
                if (!st.isAir()) {
                    if (st.getBlock() != lastTarget) {
                        lastTarget = st.getBlock();
                        Track tt = trackOf(lastTarget);
                        aimText = lastTarget.getName().getString() + " -> " + (tt != null ? tt.name
                                : cooldownBlocks.contains(lastTarget) ? "cooldown" : "no crop match");
                        ev("looking at '" + lastTarget.getName().getString() + "' -> " + (tt != null ? "crop " + tt.name
                                : cooldownBlocks.contains(lastTarget) ? "cooldown block" : "no crop name match (counts through the gain line)"));
                    }
                    Watch w = new Watch();
                    w.state = st;
                    w.since = now;
                    watch.put(key, w);
                }
            }
        }

        if (!mine) scanAround(mc);
        for (Map.Entry<Track, Integer> e : hits.entrySet()) {
            add(e.getKey(), e.getValue());
            sessionBlocks += e.getValue();
            ev("you broke: +" + e.getValue() + " " + e.getKey().name);
        }
    }

    /** A "+x Crop (total)" action bar line: tells which crop you are farming (and counts only when the source is "gain"). */
    public static void addCount(String name, double gained) {
        Track x = matchCrop(name);
        if (x == null) {
            ev("gain line '" + name + "' -> matches no crop milestone");
            return;
        }
        lastGain = x;
        lastGainAt = System.currentTimeMillis();
    }

    // ---- source "farmed" (default): the sidebar "FARMED ... 742.74k" line says HOW MANY, the block you mine says WHICH
    //
    // The server's FARMED line grows by exactly the blocks it counted (yours and your enchants'), so it is the
    // accurate amount. The crop is the one you are mining: the block under your crosshair (most of the last 3s).
    // A block that does not look like a crop by name (a pattern / concrete block) is learned: the "+x Crop"
    // action bar line that shows while that block is what you mine says which crop it is (saved for next time).

    private static final Pattern FARMED = Pattern.compile("(?i)\\bFARMED\\b\\D{0,8}?(" + Nums.NUM + ")");
    private static double farmedPrev = -1;
    private static double sessionFarmed;
    private static final java.util.ArrayDeque<net.minecraft.world.level.block.Block> aimWin = new java.util.ArrayDeque<>();
    private static String farmedNote = "waiting for FARMED";

    private static void trackAim(Minecraft mc) throws Exception {
        Object hr = hitResult(mc);
        if (hr instanceof net.minecraft.world.phys.BlockHitResult b && b.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            net.minecraft.world.level.block.state.BlockState st = mc.level.getBlockState(b.getBlockPos());
            if (!st.isAir()) {
                aimWin.addLast(st.getBlock());
                while (aimWin.size() > 60) aimWin.pollFirst();
            }
        } else if (!aimWin.isEmpty() && tick % 4 == 0) {
            aimWin.pollFirst(); // looking away: the old aim fades out
        }
    }

    /** The block you aimed at most in the last ~3s, and its share of that window (0..1). */
    private static net.minecraft.world.level.block.Block dominantAim(double[] share) {
        if (aimWin.isEmpty()) return null;
        Map<net.minecraft.world.level.block.Block, Integer> c = new java.util.HashMap<>();
        net.minecraft.world.level.block.Block top = null;
        int best = 0;
        for (net.minecraft.world.level.block.Block b : aimWin) {
            int n = c.merge(b, 1, Integer::sum);
            if (n > best) {
                best = n;
                top = b;
            }
        }
        if (share != null && share.length > 0) share[0] = best / (double) aimWin.size();
        return top;
    }

    /** Which crop is being mined right now (null = cannot tell). */
    private static Track cropNow(String[] how) {
        double[] share = new double[1];
        net.minecraft.world.level.block.Block top = dominantAim(share);
        long now = System.currentTimeMillis();
        boolean gain = lastGain != null && now - lastGainAt < 4000;
        if (top != null) {
            String bn = top.getName().getString();
            Track t = trackOf(top);
            if (t != null) {
                how[0] = "aim " + bn;
                return t;
            }
            if (gain && share[0] >= 0.5 && !cooldownBlocks.contains(top)) {
                learnedNames.put(bn, lastGain.name);
                dirty = true;
                ev("learned: '" + bn + "' is " + lastGain.name + " (from the +x gain line)");
                how[0] = "aim " + bn + " (learned)";
                return lastGain;
            }
            if (gain) {
                how[0] = "gain line";
                return lastGain;
            }
        } else if (gain) {
            how[0] = "gain line";
            return lastGain;
        }
        Track t = byName(crops, lastCrop);
        if (t == null) t = matchCrop(Boosters.data.crop);
        how[0] = "last crop";
        return t;
    }

    private static void scanFarmed(Minecraft mc) throws Exception {
        if (!Cfg.I.milestoneLive || !"farmed".equals(Cfg.I.milestoneSource) || mc.level == null) return;
        trackAim(mc);
        if (tick % 2 != 0) return;
        double v = -1;
        for (String line : Pins.sidebar(mc)) {
            Matcher m = FARMED.matcher(line);
            if (m.find()) {
                v = Nums.parse(m.group(1));
                break;
            }
        }
        if (v < 0) return;
        if (farmedPrev < 0 || v < farmedPrev) {
            farmedPrev = v;
            return;
        }
        if (v == farmedPrev) return;
        double d = v - farmedPrev;
        farmedPrev = v;
        if (crops.isEmpty()) {
            farmedNote = "open /milestones once";
            return;
        }
        String[] how = new String[1];
        Track t = cropNow(how);
        if (t == null) {
            farmedNote = "+" + Nums.exact(d) + " farmed, crop unknown";
            ev("FARMED +" + Nums.exact(d) + " but no crop could be told (aim a crop block)");
            return;
        }
        add(t, d, true);
        sessionFarmed += d;
        farmedNote = "+" + Nums.exact(d) + " " + t.name;
        ev("FARMED +" + Nums.exact(d) + " -> " + t.name + " (" + how[0] + ")");
    }

    // ---- text

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

    // ---- mobs

    /**
     * Mob kills (enchant kills count too): a living thing within 16 blocks whose name is a mob milestone
     * ("Chicken") that dies, or one within 10 blocks that vanishes, shortly after you swung, counts as one kill.
     * A mob respawns after 3s as a new entity, so it is counted again when you kill it again.
     */
    private static void scanKills(Minecraft mc) {
        if (mc.player.swinging) lastSwing = tick;
        if (!Cfg.I.milestoneLive || tick % 2 != 0 || mobs.isEmpty()) return;
        boolean recent = tick - lastSwing <= 60; // enchant kills come from your swing too
        java.util.Set<Integer> present = new java.util.HashSet<>();
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.LivingEntity le) || e == mc.player) continue;
            if (e instanceof net.minecraft.world.entity.player.Player) continue;
            int id = e.getId();
            present.add(id);
            double dist = e.distanceTo(mc.player);
            if (dist > 16) continue; // enchants reach further than your arm
            if (le.isDeadOrDying()) {
                near.remove(id);
                if (counted.add(id) && recent) {
                    Track t = inText(e.getName().getString(), mobs);
                    if (t != null) {
                        add(t, 1);
                        ev("kill: +1 " + t.name);
                    }
                }
                continue;
            }
            if (dist > 10) continue;
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
            if (recent && counted.add(en.getKey())) {
                add(en.getValue(), 1);
                ev("kill: +1 " + en.getValue().name);
            }
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
        if (t == null) t = matchCrop(name);
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
        if (mc.level != null) {
            try {
                scanBlocks(mc);
                lastErr = null;
            } catch (Throwable t) {
                String m = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
                lastErr = m.length() > 50 ? m.substring(0, 50) : m;
                if (tick % 600 == 0) CobblestonePlus.LOG.warn("Block counting failed", t);
            }
            try {
                scanFarmed(mc);
            } catch (Throwable t) {
                String m = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
                farmedNote = "error: " + (m.length() > 40 ? m.substring(0, 40) : m);
                if (tick % 600 == 0) CobblestonePlus.LOG.warn("FARMED counting failed", t);
            }
            try {
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
