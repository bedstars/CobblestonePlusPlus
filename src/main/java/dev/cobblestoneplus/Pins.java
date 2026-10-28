package dev.cobblestoneplus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

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
 * Crafting pins.
 *
 *  - Recipes are learned from the crafting menus: lore like "1x Cat Ears [III] (0)", "14.80M Froglight (18.64M)"
 *    (item steps, "need (have)") and "Creaking slain 400/400" (progress steps).
 *  - What is pinned right now is read from the sidebar block "PINNED [1/7]".
 *  - The pinned lines are matched to a learned recipe so the HUD can show EVERY step, not just the
 *    few lines the scoreboard has room for. Live counts come from the scoreboard first, then your
 *    tracked backpack, then the last tooltip snapshot.
 *  A step is green once have >= need.
 */
public final class Pins {
    private Pins() {}

    public static final class Step {
        public String name;
        public double need;
        public double have;
        public boolean progress;

        public boolean met() {
            return have >= need;
        }
    }

    public static final class Recipe {
        public String name;
        public List<Step> steps = new ArrayList<>();
    }

    public static final class Live {
        public String title = "Pinned";
        public int done, total;
        public boolean full;           // true when matched to a learned recipe (all steps known)
        public List<Step> steps = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final Map<String, Recipe> recipes = new LinkedHashMap<>();
    public static Live live;
    private static int tick;
    private static boolean dirty;

    private static final String N = Nums.NUM;
    private static final Pattern HEADER = Pattern.compile("PINNED\\s*\\[(\\d+)\\s*/\\s*(\\d+)\\]");
    private static final Pattern ITEM = Pattern.compile("^[^\\dA-Za-z]*(" + N + ")x?\\s+(.+?)\\s*\\((" + N + ")\\)\\s*$");
    private static final Pattern PROG = Pattern.compile("^[^A-Za-z]*([A-Za-z][A-Za-z' ]*?)\\s+(" + N + ")\\s*/\\s*(" + N + ")\\s*$");

    // ------------------------------------------------------------ storage

    public static void load() {
        try {
            Path p = Cfg.dir().resolve("recipes.json");
            if (Files.exists(p)) {
                Map<String, Recipe> m = GSON.fromJson(Files.readString(p), new TypeToken<LinkedHashMap<String, Recipe>>() {}.getType());
                if (m != null) {
                    recipes.clear();
                    recipes.putAll(m);
                }
            }
        } catch (Throwable t) {
            CobblestonePlus.LOG.warn("Could not read recipes.json", t);
        }
    }

    public static void save() {
        try {
            Files.writeString(Cfg.dir().resolve("recipes.json"), GSON.toJson(recipes));
            dirty = false;
        } catch (Throwable t) {
            CobblestonePlus.LOG.warn("Could not save recipes.json", t);
        }
    }

    public static void forget() {
        recipes.clear();
        live = null;
        save();
    }

    // ------------------------------------------------------------ parsing

    static Step parseStep(String line) {
        Matcher m = ITEM.matcher(line);
        if (m.find()) {
            Step s = new Step();
            s.need = Nums.parse(m.group(1));
            s.name = m.group(2).trim();
            s.have = Nums.parse(m.group(3));
            return s;
        }
        Matcher p = PROG.matcher(line);
        if (p.find()) {
            Step s = new Step();
            s.name = p.group(1).trim();
            s.have = Nums.parse(p.group(2));
            s.need = Nums.parse(p.group(3));
            s.progress = true;
            return s;
        }
        return null;
    }

    private static String key(String s) {
        return s.toLowerCase(Locale.ROOT).trim();
    }

    // ------------------------------------------------------------ tick

    public static void onTick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            live = null;
            return;
        }
        tick++;
        if (tick % 5 == 0) scanMenu(mc);
        if (tick % 5 == 2) scanSidebar(mc);
        if (dirty && tick % 200 == 0) save();
    }

    private static void scanMenu(Minecraft mc) {
        if (!(Scr.get(mc) instanceof AbstractContainerScreen<?> gs)) return;
        for (Slot s : gs.getMenu().slots) {
            if (s.container == mc.player.getInventory()) continue;
            ItemStack st = s.getItem();
            if (st.isEmpty()) continue;
            ItemLore l = st.get(DataComponents.LORE);
            if (l == null) continue;
            boolean header = false;
            List<Step> steps = new ArrayList<>();
            for (Component c : l.lines()) {
                String t = c.getString().trim();
                if (t.startsWith("Recipe") || t.startsWith("Requirements")) {
                    header = true;
                    continue;
                }
                if (!header) continue;
                Step step = parseStep(t);
                if (step != null) steps.add(step);
            }
            if (!header || steps.isEmpty()) continue;
            String name = st.getHoverName().getString().trim();
            Recipe old = recipes.get(name);
            Recipe r = new Recipe();
            r.name = name;
            r.steps = steps;
            if (old == null || old.steps.size() != steps.size() || differs(old, r)) {
                recipes.put(name, r);
                dirty = true;
            } else {
                // refresh the have-snapshot without forcing a save every scan
                old.steps = steps;
            }
        }
    }

    private static boolean differs(Recipe a, Recipe b) {
        for (int i = 0; i < a.steps.size(); i++) {
            if (!key(a.steps.get(i).name).equals(key(b.steps.get(i).name)) || a.steps.get(i).need != b.steps.get(i).need) return true;
        }
        return false;
    }

    private static List<String> sidebar(Minecraft mc) {
        List<String> out = new ArrayList<>();
        Scoreboard sb = mc.level.getScoreboard();
        Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (obj == null) return out;
        List<PlayerScoreEntry> es = new ArrayList<>(sb.listPlayerScores(obj));
        es.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed());
        for (PlayerScoreEntry e : es) {
            if (e.owner().startsWith("#")) continue;
            Component c = e.display() != null ? e.display()
                    : PlayerTeam.formatNameForTeam(sb.getPlayersTeam(e.owner()), Component.literal(e.owner()));
            out.add(c.getString());
        }
        return out;
    }

    private static void scanSidebar(Minecraft mc) {
        List<String> lines;
        try {
            lines = sidebar(mc);
        } catch (Throwable t) {
            return;
        }
        int at = -1;
        int done = 0, total = 0;
        for (int i = 0; i < lines.size(); i++) {
            Matcher h = HEADER.matcher(lines.get(i));
            if (h.find()) {
                at = i;
                done = Integer.parseInt(h.group(1));
                total = Integer.parseInt(h.group(2));
                break;
            }
        }
        if (at < 0) {
            live = null;
            return;
        }
        List<Step> shown = new ArrayList<>();
        for (int i = at + 1; i < lines.size(); i++) {
            Step s = parseStep(lines.get(i));
            if (s == null) break;
            shown.add(s);
        }
        live = build(shown, done, total);
    }

    private static Live build(List<Step> shown, int done, int total) {
        Live lv = new Live();
        lv.done = done;
        lv.total = total;

        Recipe best = null;
        int bestHit = 0;
        for (Recipe r : recipes.values()) {
            int hit = 0;
            for (Step s : shown) {
                boolean ok = false;
                for (Step rs : r.steps) if (key(rs.name).equals(key(s.name))) ok = true;
                if (ok) hit++;
            }
            if (hit == shown.size() && hit > bestHit) {
                best = r;
                bestHit = hit;
            }
        }

        if (best == null) {
            lv.steps = shown;
            return lv;
        }
        lv.title = best.name;
        lv.full = true;
        for (Step rs : best.steps) {
            Step s = new Step();
            s.name = rs.name;
            s.need = rs.need;
            s.progress = rs.progress;
            s.have = rs.have; // snapshot
            Backpack.Entry be = null;
            for (Backpack.Entry e : Backpack.items.values()) if (key(e.name).equals(key(rs.name))) be = e;
            if (be != null && !rs.progress) s.have = be.amount;
            for (Step ls : shown) if (key(ls.name).equals(key(rs.name))) s.have = ls.have; // live scoreboard wins
            lv.steps.add(s);
        }
        return lv;
    }
}
