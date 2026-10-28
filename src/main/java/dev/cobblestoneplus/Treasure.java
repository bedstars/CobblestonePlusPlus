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
    }

    public static final class Vault {
        public String name;
        public int found, total;
        public boolean forged;
        public boolean locked;
        public int left = -1;          // relics left until the seal is forged (-1 unknown)
        public double sealFortune;
        public Map<String, Double> bonuses = new LinkedHashMap<>();
        public List<Relic> relics = new ArrayList<>();

        /** Sum of relic levels / sum of max levels, or null when the tooltips did not show levels. */
        public int[] levels() {
            int cur = 0, mx = 0;
            for (Relic r : relics) {
                if (r.level < 0 || r.max <= 0) return null;
                cur += r.level;
                mx += r.max;
            }
            if (relics.isEmpty()) return null;
            // unfound relics count as 0 / 3 (max-level assumed from the found ones)
            int per = relics.get(0).max;
            int missing = Math.max(0, total - relics.size());
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
            v = new Vault();
            v.name = vname;
        }
        for (Slot s : gs.getMenu().slots) {
            if (s.container == mc.player.getInventory()) continue;
            ItemStack st = s.getItem();
            if (st.isEmpty()) continue;
            String name = st.getHoverName().getString().trim();
            List<String> lore = lore(st);

            if (name.equals("The Treasury")) {
                parseTreasury(lore);
                continue;
            }
            if (parseVaultHead(name, lore)) continue;
            if (!isVault) continue;

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
            if (name.startsWith("?") || name.isEmpty()) continue;                 // unfound relic
            if (name.equalsIgnoreCase(vname)) continue;                            // dimension icon
            String n = name.toLowerCase();
            if (n.equals("back") || n.equals("go back") || n.startsWith("return") || n.startsWith("next") || n.startsWith("previous") || n.equals("close")) continue;

            Relic r = new Relic();
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
            if (v.total == 0) v.total = v.relics.size();
            if (v.found == 0) v.found = v.relics.size();
            Vault old = vaults.get(vname);
            if (old != null) {
                v.bonuses = old.bonuses;
                v.locked = old.locked;
                if (v.sealFortune == 0) v.sealFortune = old.sealFortune;
            }
            vaults.put(vname, v);
            dirty = true;
        }
    }

    /**
     * The vault heads in "The Treasury" menu:
     *   Overworld / Vault / Relics: 8/8 / Bonuses: | Fortune: x3.436 ... / Vault Seal: Forged (x1.35 Overworld Fortune)
     *   Swamp ... Vault Seal: 3 relic(s) left (x1.35 Swamp Fortune)
     *   The Library (Locked) / Unlock in /warp
     */
    private static boolean parseVaultHead(String name, List<String> lore) {
        boolean vaultLine = false, locked = name.contains("(Locked)");
        for (String l : lore) {
            String t = l.trim();
            if (t.equals("Vault")) vaultLine = true;
            if (t.startsWith("Unlock in")) locked = true;
        }
        if (!vaultLine && !locked) return false;
        String vname = name.replace("(Locked)", "").trim();
        if (vname.isEmpty()) return false;
        Vault v = vaults.get(vname);
        if (v == null) {
            v = new Vault();
            v.name = vname;
            vaults.put(vname, v);
        }
        v.locked = locked;
        if (locked) {
            v.found = 0;
            if (v.total == 0) v.total = 8;
            v.forged = false;
            dirty = true;
            return true;
        }
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
        return true;
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
        if (s.relicsFound != summary.relicsFound || s.fortune != summary.fortune || s.sealsForged != summary.sealsForged || !s.dim.equals(summary.dim) || !s.boosts.equals(summary.boosts)) dirty = true;
        summary = s;
    }
}
