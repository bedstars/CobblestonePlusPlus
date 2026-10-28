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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Treasures. Learned from:
 *  - "Vault: <dimension>" menus: the Vault Seal item gives "Progress: 3/8" (relics found / relics in the vault)
 *    and whether it is forged; found relics are the other items (unfound ones are named "?").
 *  - The "The Treasury" item: Relics 11/48, Vault Seals 1/6 and the active Fortune / Sell Value / XP Gain boosts.
 * Open each vault once and it is remembered.
 */
public final class Treasure {
    private Treasure() {}

    public static final class Relic {
        public String name;
        public int level = -1; // -1 = unknown
        public int max = -1;
        public boolean found = true; // false = still "?" in the vault menu
        public String hint = "";     // what the vault menu says about an unfound relic
    }

    public static final class Vault {
        public String name;
        public int found, total;
        public boolean forged;
        public boolean locked;
        public int order = -1;         // position among the open vaults in The Treasury (0 = first) = world - 1
        public int left = -1;          // relics left until the seal is forged (-1 unknown)
        public double sealFortune;
        public Map<String, Double> bonuses = new LinkedHashMap<>();
        public List<Relic> relics = new ArrayList<>();

        /** Sum of relic levels / sum of max levels, or null when the tooltips did not show levels. */
        public int[] levels() {
            int cur = 0, mx = 0, n = 0, per = 3;
            for (Relic r : relics) {
                if (!r.found) continue;
                if (r.level < 0 || r.max <= 0) return null;
                cur += r.level;
                mx += r.max;
                per = r.max;
                n++;
            }
            if (n == 0) return null;
            // unfound relics count as 0 / max
            int missing = Math.max(0, total - n);
            return new int[]{cur, mx + missing * per};
        }
    }

    public static final class Summary {
        public int relicsFound, relicsTotal, sealsForged, sealsTotal;
        public String dim = "";
        public double fortune, sell, xp;
        public Map<String, Double> boosts = new LinkedHashMap<>();
        public boolean known;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final Map<String, Vault> vaults = new LinkedHashMap<>();
    public static Summary summary = new Summary();
    private static int tick;
    private static boolean dirty;

    private static final String X = "[x\\u00d7]";
    private static final Pattern PROGRESS = Pattern.compile("Progress:\\s*(\\d+)\\s*/\\s*(\\d+)");
    private static final Pattern FRAC = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");
    private static final Pattern S_RELICS = Pattern.compile("Relics:\\s*(\\d+)\\s*/\\s*(\\d+)");
    private static final Pattern S_SEALS = Pattern.compile("Vault Seals:\\s*(\\d+)\\s*/\\s*(\\d+)");
    private static final Pattern S_ACTIVE = Pattern.compile("Active in\\s+(.+?):");
    private static final Pattern S_FORT = Pattern.compile("Fortune:\\s*" + X + "\\s*([\\d.,]+)");
    private static final Pattern S_SELL = Pattern.compile("Sell Value:\\s*" + X + "\\s*([\\d.,]+)");
    private static final Pattern BOOST = Pattern.compile("([A-Za-z][A-Za-z ]*?):\\s*" + X + "\\s*([\\d.,]+)");
    private static final Pattern LEFT = Pattern.compile("(\\d+)\\s*relic\\(s\\)\\s*left");
    private static final Pattern S_XP = Pattern.compile("XP Gain:\\s*" + X + "\\s*([\\d.,]+)");

    // ------------------------------------------------------------ storage

    private static final class Save {
        Map<String, Vault> vaults = new LinkedHashMap<>();
        Summary summary = new Summary();
    }

    public static void load() {
        try {
            Path p = Cfg.dir().resolve("treasure.json");
            if (Files.exists(p)) {
                Save s = GSON.fromJson(Files.readString(p), new TypeToken<Save>() {}.getType());
                if (s != null) {
                    vaults.clear();
                    if (s.vaults != null) vaults.putAll(s.vaults);
                    vaults.values().removeIf(v -> v.locked || Worlds.byName(v.name) == 0);
                    if (s.summary != null) summary = s.summary;
                }
            }
        } catch (Throwable t) {
            CobblestonePlus.LOG.warn("Could not read treasure.json", t);
        }
    }

    public static void save() {
        try {
            Save s = new Save();
            s.vaults = vaults;
            s.summary = summary;
            Files.writeString(Cfg.dir().resolve("treasure.json"), GSON.toJson(s));
            dirty = false;
        } catch (Throwable t) {
            CobblestonePlus.LOG.warn("Could not save treasure.json", t);
        }
    }

    public static void forget() {
        vaults.clear();
        summary = new Summary();
        save();
    }

    // ------------------------------------------------------------ worlds

    /** The real worlds in order (Overworld, Frost Castle, Swamp, Honey Tree ...). Charms, seals and locked places are not worlds. */
    public static List<Vault> worldVaults() {
        List<Vault> out = new ArrayList<>();
        int max = Worlds.count();
        for (Vault v : vaults.values()) {
            int w = Worlds.byName(v.name);
            if (!v.locked && w > 0 && w <= max) out.add(v);
        }
        out.sort(java.util.Comparator.comparingInt((Vault v) -> Worlds.byName(v.name)));
        return out;
    }

    /** The world shown in detail (fixed in the settings, else the one you are in); 0 = not known yet. */
    public static int currentWorld() {
        return Cfg.I.treasureWorld > 0 ? Cfg.I.treasureWorld : Worlds.current();
    }

    /** Index into {@link #worldVaults()} (0-based) of the world shown in detail, or -1 (not known / not read yet). */
    public static int currentIndex(List<Vault> ws) {
        int w = currentWorld();
        if (w <= 0) return -1;
        for (int i = 0; i < ws.size(); i++) if (Worlds.byName(ws.get(i).name) == w) return i;
        return -1;
    }

    /** Asks the server for the treasury menu (the /treasures command); it is read and closed again. */
    public static boolean refresh(Minecraft mc) {
        if (mc.player == null) return false;
        Sync.ask("treasures", true);
        return true;
    }

    // ------------------------------------------------------------ chat

    /** Last treasure you found (announced in chat: name + level, then the stats it gives). */
    public static String lastFind = "";
    public static long lastFindAt;

    // The announcement arrives as several chat lines:
    //   You unearthed UNCOMMON Flowering Azalea Treasure while farming! (1/30.52k)      <- first time (level 1)
    //   Duplicate Flowering Azalea Treasure  Level 2/3                                    <- upgrade
    //   On all Honey Tree crops:
    //     +7% Fortune        +8% Sell Value
    //   Click to view your /treasures
    // Other players' finds read "<name> unearthed ..." and are not yours.
    private static final Pattern YOU_FOUND = Pattern.compile("(?i)\\byou\\s+unearthed\\s+(.*?)\\s+Treasure\\b");
    private static final Pattern DUP = Pattern.compile("(?i)duplicate\\s+(.*?)\\s+Treasure\\b.*?(\\d+)\\s*/\\s*(\\d+)");
    private static final Pattern ON_ALL = Pattern.compile("(?i)on all\\s+(.+?)\\s+crops");
    private static final Pattern GAIN = Pattern.compile("\\+\\s*([\\d.,]+)\\s*%\\s*([A-Za-z][A-Za-z ]*?)(?=\\s{2,}|\\s*\\+|\\s*$)");

    private static String pendWords;           // "UNCOMMON Flowering Azalea" (rarity still in front)
    private static int pendLvl, pendMax, pendWorld;
    private static boolean pendNew;
    private static long pendAt;
    private static final List<String> pendGains = new ArrayList<>();

    public static void onChat(String text) {
        if (text == null || text.isEmpty()) return;
        for (String raw : text.split("\\R")) line(raw.trim());
    }

    private static void line(String line) {
        if (line.isEmpty() || line.contains("\u00bb")) return;           // player chat
        Matcher y = YOU_FOUND.matcher(line);
        if (y.find()) {
            startPending(y.group(1).trim(), 1, 3, true);
            return;
        }
        if (line.contains("unearthed")) return;                          // somebody else's find
        Matcher d = DUP.matcher(line);
        if (d.find()) {
            startPending(d.group(1).trim(), Integer.parseInt(d.group(2)), Integer.parseInt(d.group(3)), false);
            return;
        }
        if (pendWords == null) return;
        Matcher o = ON_ALL.matcher(line);
        if (o.find()) {
            pendWorld = Worlds.byName(o.group(1));
        }
        Matcher g = GAIN.matcher(line);
        while (g.find()) pendGains.add("+" + g.group(1) + "% " + g.group(2).trim());
        if (line.contains("/treasures") || line.toLowerCase().contains("click to view")) finishPending();
    }

    private static void startPending(String words, int lvl, int max, boolean isNew) {
        if (pendWords != null) finishPending();
        pendWords = words;
        pendLvl = lvl;
        pendMax = max;
        pendNew = isNew;
        pendWorld = 0;
        pendGains.clear();
        pendAt = System.currentTimeMillis();
    }

    /** Drops rarity / symbols in front of the name until a known crop matches. */
    private static Milestones.Track cropOf(String words) {
        String[] w = words.split("\\s+");
        for (int i = 0; i < w.length; i++) {
            StringBuilder sb = new StringBuilder();
            for (int k = i; k < w.length; k++) sb.append(k > i ? " " : "").append(w[k]);
            Milestones.Track t = Milestones.find(sb.toString());
            if (t != null) return t;
        }
        return null;
    }

    private static void finishPending() {
        if (pendWords == null) return;
        String words = pendWords;
        pendWords = null;
        Milestones.Track t = cropOf(words);
        String crop = t != null ? t.name : words;
        if (t != null) {
            t.treasure = pendLvl;
            t.treasureMax = pendMax;
            Milestones.touch();
        }
        int world = pendWorld > 0 ? pendWorld : (t != null ? t.world() : 0);
        Vault v = world > 0 ? vaults.get(Worlds.name(world)) : null;
        if (v != null) applyFind(v, crop, pendLvl, pendMax, pendNew);
        lastFind = crop + " " + pendLvl + "/" + pendMax + (pendNew ? " NEW" : "") + (pendGains.isEmpty() ? "" : "  " + String.join("  ", pendGains));
        lastFindAt = System.currentTimeMillis();
        dirty = true;
        Sync.ask("treasures", false); // seals / boosts changed: re-read them
        Boosters.changed();           // and the multiplier
    }

    /** Updates the stored vault right away, so the missing list shrinks before the menu is read again. */
    private static void applyFind(Vault v, String crop, int lvl, int max, boolean isNew) {
        String c = crop.toLowerCase();
        Relic hit = null;
        for (Relic r : v.relics) if (r.found && r.name != null && r.name.toLowerCase().contains(c)) hit = r;
        if (hit != null) {
            hit.level = lvl;
            hit.max = max;
            return;
        }
        if (!isNew) return;
        v.found = v.total > 0 ? Math.min(v.total, v.found + 1) : v.found + 1;
        Relic gone = null;
        for (Relic r : v.relics) if (!r.found && r.hint != null && r.hint.toLowerCase().contains(c)) gone = r;
        if (gone != null) v.relics.remove(gone);
        Relic nr = new Relic();
        nr.name = crop;
        nr.level = lvl;
        nr.max = max;
        v.relics.add(nr);
    }

    // ------------------------------------------------------------ scanning

    private static List<String> lore(ItemStack st) {
        List<String> out = new ArrayList<>();
        ItemLore l = st.get(DataComponents.LORE);
        if (l == null) return out;
        for (Component c : l.lines()) out.add(c.getString());
        return out;
    }

    public static void onTick(Minecraft mc) {
        if (mc.player == null) return;
        tick++;
        if (pendWords != null && System.currentTimeMillis() - pendAt > 4000) finishPending();
        if (tick % 5 == 0) scanMenu(mc);
        if (tick % 40 == 0) scanInventory(mc);
        if (dirty && tick % 200 == 0) save();
    }

    private static void scanMenu(Minecraft mc) {
        if (!(Scr.get(mc) instanceof AbstractContainerScreen<?> gs)) return;
        String title = gs.getTitle().getString().trim();
        boolean isVault = title.startsWith("Vault:");
        Vault v = null;
        String vname = "";
        if (isVault) {
            vname = title.substring("Vault:".length()).trim();
            if (Worlds.byName(vname) == 0) isVault = false;       // not one of the real worlds
            else {
                v = new Vault();
                v.name = Worlds.name(Worlds.byName(vname));      // always the canonical spelling
                vname = v.name;
            }
        }
        int ord = 0;
        boolean sawHeads = false, sawTreasury = false;
        for (Slot s : gs.getMenu().slots) {
            if (s.container == mc.player.getInventory()) continue;
            ItemStack st = s.getItem();
            if (st.isEmpty()) continue;
            String name = st.getHoverName().getString().trim();
            List<String> lore = lore(st);

            if (name.equals("The Treasury")) {
                parseTreasury(lore);
                sawTreasury = true;
                continue;
            }
            if (!isVault) {
                Vault hv = parseVaultHead(name, lore);
                if (hv != null) {
                    hv.order = ord++;
                    sawHeads = true;
                }
                continue;
            }

            if (name.contains("Vault Seal")) {
                for (String line : lore) {
                    Matcher p = PROGRESS.matcher(line);
                    if (p.find()) {
                        v.found = Integer.parseInt(p.group(1));
                        v.total = Integer.parseInt(p.group(2));
                    }
                    Matcher f = S_FORT.matcher(line);
                    if (f.find()) v.sealFortune = num(f.group(1));
                    if (line.contains("Not forged")) v.forged = false;
                    else if (line.toLowerCase().contains("forged")) v.forged = true;
                }
                if (v.total > 0 && v.found >= v.total && !hasNotForged(lore)) v.forged = true;
                continue;
            }
            if (name.equalsIgnoreCase(vname)) continue;                            // dimension icon
            String n = name.toLowerCase();
            if (n.equals("back") || n.equals("go back") || n.startsWith("return") || n.startsWith("next") || n.startsWith("previous") || n.equals("close")) continue;

            Relic r = new Relic();
            if (name.startsWith("?")) {                                            // unfound relic: keep what the menu says about it
                r.name = "?";
                r.found = false;
                StringBuilder hint = new StringBuilder();
                for (String line : lore) {
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    if (hint.length() > 0) hint.append(" ");
                    hint.append(t);
                    if (hint.length() > 60) break;
                }
                r.hint = hint.toString();
                v.relics.add(r);
                continue;
            }
            r.name = name;
            for (String line : lore) {
                Matcher m = FRAC.matcher(line);
                if (m.find()) {
                    int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2));
                    if (b > 0 && b <= 20 && a <= b) {
                        r.level = a;
                        r.max = b;
                        break;
                    }
                }
            }
            v.relics.add(r);
        }
        if (v != null && (v.total > 0 || !v.relics.isEmpty())) {
            int foundRelics = 0;
            for (Relic r : v.relics) if (r.found) foundRelics++;
            if (v.total == 0) v.total = v.relics.size();
            if (v.found == 0) v.found = foundRelics;
            Vault old = vaults.get(vname);
            if (old != null) {
                v.bonuses = old.bonuses;
                v.locked = false;
                v.order = old.order;
                v.left = old.left;
                if (v.sealFortune == 0) v.sealFortune = old.sealFortune;
            }
            vaults.put(vname, v);
            dirty = true;
        }
        // asked the server for the treasury: once it was read, close it again
        if ((sawHeads || sawTreasury) && !isVault) Sync.done("treasures", gs);
    }

    /**
     * The vault heads in "The Treasury" menu:
     *   Overworld / Vault / Relics: 8/8 / Bonuses: | Fortune: x3.436 ... / Vault Seal: Forged (x1.35 Overworld Fortune)
     *   Swamp ... Vault Seal: 3 relic(s) left (x1.35 Swamp Fortune)
     *   The Library (Locked) / Unlock in /warp   <- a place that does not exist (yet): never kept
     * Returns the vault, or null for anything else / locked places.
     */
    private static Vault parseVaultHead(String name, List<String> lore) {
        boolean vaultLine = false, locked = name.contains("(Locked)");
        for (String l : lore) {
            String t = l.trim();
            if (t.equals("Vault")) vaultLine = true;
            if (t.startsWith("Unlock in")) locked = true;
        }
        if (!vaultLine && !locked) return null;
        String vname = name.replace("(Locked)", "").trim();
        if (vname.isEmpty()) return null;
        int wn = Worlds.byName(vname);
        if (wn == 0) {                                   // a charm / reward / unknown place: never a world
            if (vaults.remove(vname) != null) dirty = true;
            return null;
        }
        vname = Worlds.name(wn);
        if (locked) {
            if (vaults.remove(vname) != null) dirty = true;
            return null;
        }
        Vault v = vaults.get(vname);
        if (v == null) {
            v = new Vault();
            v.name = vname;
            vaults.put(vname, v);
        }
        v.locked = false;
        Map<String, Double> bonuses = new LinkedHashMap<>();
        for (String line : lore) {
            Matcher r = S_RELICS.matcher(line);
            if (r.find()) {
                v.found = Integer.parseInt(r.group(1));
                v.total = Integer.parseInt(r.group(2));
                continue;
            }
            if (line.contains("Vault Seal")) {
                v.forged = line.contains("Forged") && !line.contains("left");
                Matcher lf = LEFT.matcher(line);
                v.left = lf.find() ? Integer.parseInt(lf.group(1)) : 0;
                Matcher mult = Pattern.compile(X + "\\s*([\\d.,]+)").matcher(line);
                if (mult.find()) v.sealFortune = num(mult.group(1));
                continue;
            }
            Matcher b = BOOST.matcher(line);
            if (b.find()) bonuses.put(b.group(1).trim(), num(b.group(2)));
        }
        v.bonuses = bonuses;
        dirty = true;
        return v;
    }

    private static boolean hasNotForged(List<String> lore) {
        for (String l : lore) if (l.contains("Not forged")) return true;
        return false;
    }

    private static void scanInventory(Minecraft mc) {
        int n = mc.player.getInventory().getContainerSize();
        for (int i = 0; i < n; i++) {
            ItemStack st = mc.player.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            if (st.getHoverName().getString().trim().equals("The Treasury")) {
                parseTreasury(lore(st));
                return;
            }
        }
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void parseTreasury(List<String> lore) {
        Summary s = new Summary();
        boolean active = false;
        for (String line : lore) {
            Matcher m = S_RELICS.matcher(line);
            if (m.find()) {
                s.relicsFound = Integer.parseInt(m.group(1));
                s.relicsTotal = Integer.parseInt(m.group(2));
                s.known = true;
            }
            m = S_SEALS.matcher(line);
            if (m.find()) {
                s.sealsForged = Integer.parseInt(m.group(1));
                s.sealsTotal = Integer.parseInt(m.group(2));
            }
            m = S_ACTIVE.matcher(line);
            if (m.find()) {
                s.dim = m.group(1).trim();
                active = true;
            }
            m = S_FORT.matcher(line);
            if (m.find()) s.fortune = num(m.group(1));
            m = S_SELL.matcher(line);
            if (m.find()) s.sell = num(m.group(1));
            m = S_XP.matcher(line);
            if (m.find()) s.xp = num(m.group(1));
            if (active) {
                Matcher b = BOOST.matcher(line);
                if (b.find()) s.boosts.put(b.group(1).trim(), num(b.group(2)));
            }
        }
        if (!s.known) return;
        Worlds.noteDim(s.dim);
        if (s.relicsFound != summary.relicsFound || s.fortune != summary.fortune || s.sealsForged != summary.sealsForged || !s.dim.equals(summary.dim) || !s.boosts.equals(summary.boosts)) dirty = true;
        summary = s;
    }
}
