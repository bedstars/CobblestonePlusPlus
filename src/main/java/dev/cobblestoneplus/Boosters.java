package dev.cobblestoneplus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multiplier breakdown. The server's "Boosters" item in /stats has lore like
 *   Total: x2,902.69
 *   Fortune Cookie: x1.75 (x1.75 XP)
 *   Rank Boost: x1.2
 *   Farming: Crying Obsidian
 * We read it whenever a container menu containing it is open and keep the last result.
 */
public final class Boosters {
    private Boosters() {}

    public static final class Row {
        public String name;
        public double mult = 1;
        public double xp = 0; // 0 = this booster has no separate XP value
    }

    public static final class Data {
        public double total;
        public String crop = "";
        public List<Row> rows = new ArrayList<>();
        public long updated;
        public transient boolean est;   // true while the numbers are a local estimate waiting for the server's /stats
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static Data data = new Data();
    private static int tick;

    private static final String X = "[x\\u00d7]";
    private static final Pattern TOTAL = Pattern.compile("Total:\\s*" + X + "\\s*([\\d.,]+)");
    private static final Pattern ROW = Pattern.compile(
            "([A-Za-z][A-Za-z' ]*?):\\s*" + X + "\\s*([\\d.,]+)(?:\\s*\\(\\s*" + X + "\\s*([\\d.,]+)\\s*XP\\s*\\))?");
    private static final Pattern CROP = Pattern.compile("^\\s*[A-Za-z]+:\\s*([A-Za-z][A-Za-z' ]*)\\s*$");

    public static void load() {
        try {
            Path p = Cfg.dir().resolve("boosters.json");
            if (Files.exists(p)) {
                Data d = GSON.fromJson(Files.readString(p), Data.class);
                if (d != null && d.rows != null) data = d;
            }
        } catch (Throwable t) {
            CobblestonePlus.LOG.warn("Could not read boosters.json", t);
        }
    }

    private static void save() {
        try {
            Files.writeString(Cfg.dir().resolve("boosters.json"), GSON.toJson(data));
        } catch (Throwable t) {
            CobblestonePlus.LOG.warn("Could not save boosters.json", t);
        }
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------ live updates
    //
    // Whenever something that changes the multiplier happens (armor on / off, tool held / not held, a shop or upgrade
    // menu closed, a purchase / booster message in chat, another crop, a pet change ...) two things happen:
    //   1. the panel is corrected AT ONCE with a local estimate (armor off -> Armor Fortune x1, tool away -> Tool Boost x1,
    //      and back to the remembered values when you put them on again), shown with "updating";
    //   2. the server is asked for /stats a moment later, and asked again (up to 2 more times) when the answer still
    //      equals the old total, because the server sometimes recalculates a second or two late.

    private static String armorSig, handSig;
    private static long changedAt = -1, lastAsk, retryAt = -1;
    private static boolean wasMenu, expect;
    private static int retries, estTick;
    private static double serverTotal;
    // remembered while the item was on / held, to restore the estimate when it comes back
    private static double rememberArmor, rememberTool;
    private static int fullPieces;
    private static String toolName = "";
    private static final Pattern BUYING = Pattern.compile("(?i)\\b(purchased|bought|upgraded|upgrade|equipped|unequipped|unlocked|enchanted|crafted|forged|reforged|hatched|claimed|expired|activated)\\b");
    private static final Pattern SHOP_MULT = Pattern.compile("Multiplier:\\s*([\\d.,]+)\\s*x");

    /** Asks the server for /stats (read + closed automatically) when auto-update is on. */
    public static void request() {
        if (Cfg.I.boostersAuto) Sync.ask("stats", false);
    }

    /** Manual update from the menu / command: works even with a screen open. */
    public static void refresh() {
        Sync.ask("stats", true);
    }

    /** Something happened that may change the multiplier: refresh soon and keep asking until the total moves. */
    static void changed() {
        changedAt = tick;
        expect = true;
        retries = 0;
        retryAt = -1;
        data.est = true;
        estTick = tick;
    }

    public static void onChat(String t) {
        if (t == null || t.contains("\u00bb")) return; // player chat
        if (BUYING.matcher(t).find()) changed();
    }

    private static String item(ItemStack st) {
        return st.isEmpty() ? "-" : st.getHoverName().getString() + "x" + st.getCount();
    }

    private static String armorSignature(Minecraft mc) {
        StringBuilder sb = new StringBuilder();
        for (EquipmentSlot es : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            sb.append('|').append(item(mc.player.getItemBySlot(es)));
        }
        return sb.toString();
    }

    private static String handSignature(Minecraft mc) {
        return item(mc.player.getMainHandItem()) + '|' + item(mc.player.getOffhandItem());
    }

    private static int armorPieces(Minecraft mc) {
        int n = 0;
        for (EquipmentSlot es : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (!mc.player.getItemBySlot(es).isEmpty()) n++;
        }
        return n;
    }

    private static String mainHandName(Minecraft mc) {
        ItemStack st = mc.player.getMainHandItem();
        return st.isEmpty() ? "-" : st.getHoverName().getString();
    }

    private static Row row(String part) {
        for (Row r : data.rows) if (r.name.toLowerCase().contains(part)) return r;
        return null;
    }

    /** Sets one row to a new value and moves the total with it. */
    private static void scale(Row r, double nv) {
        double old = r.mult <= 0 ? 1 : r.mult;
        data.total = data.total / old * nv;
        r.mult = nv;
        data.est = true;
        estTick = tick;
    }

    /** Instant local correction for armor / tool changes (the server confirms it a moment later). */
    private static void estimate(Minecraft mc, boolean armorChanged, boolean handChanged) {
        if (data.rows.isEmpty()) return;
        if (armorChanged) {
            Row r = row("armor");
            if (r != null) {
                int pieces = armorPieces(mc);
                if (pieces == 0 && r.mult > 1) {
                    rememberArmor = r.mult;
                    scale(r, 1);
                } else if (pieces >= Math.max(1, fullPieces) && rememberArmor > 1 && r.mult <= 1) {
                    scale(r, rememberArmor);
                }
            }
        }
        if (handChanged) {
            Row r = row("tool");
            if (r != null && !toolName.isEmpty()) {
                boolean holding = toolName.equals(mainHandName(mc));
                if (holding && r.mult <= 1 && rememberTool > 1) scale(r, rememberTool);
                else if (!holding && r.mult > 1) {
                    rememberTool = r.mult;
                    scale(r, 1);
                }
            }
        }
    }

    /** What we know while the player wears / holds things: used to restore the estimate later. */
    private static void remember(Minecraft mc) {
        Row a = row("armor");
        if (a != null && a.mult > 1) {
            rememberArmor = a.mult;
            fullPieces = Math.max(fullPieces, armorPieces(mc));
        }
        Row t = row("tool");
        if (t != null && t.mult > 1) {
            rememberTool = t.mult;
            String h = mainHandName(mc);
            if (!h.equals("-")) toolName = h;
        }
    }

    /** Armor on / off, tool held / not held, an upgrade bought (a menu was closed): ask for the new multiplier. */
    private static void watch(Minecraft mc) {
        String a = armorSignature(mc), h = handSignature(mc);
        if (armorSig == null) {
            armorSig = a;
            handSig = h;
            lastAsk = tick;
            request();
            return;
        }
        if (data.est && tick - estTick > 300) data.est = false; // no answer for 15s: stop saying "updating"
        boolean ac = !a.equals(armorSig), hc = !h.equals(handSig);
        if (ac || hc) {
            armorSig = a;
            handSig = h;
            estimate(mc, ac, hc);
            changed();
        }
        boolean menu = Scr.get(mc) instanceof AbstractContainerScreen<?>;
        if (wasMenu && !menu && !Sync.busy()) changed(); // closed a shop / inventory / pet menu: something may have changed
        wasMenu = menu;
        if (changedAt >= 0 && tick - changedAt >= 6) {
            changedAt = -1;
            lastAsk = tick;
            request();
        } else if (retryAt >= 0 && tick >= retryAt) {
            retryAt = -1;
            lastAsk = tick;
            request();
        } else if (tick - lastAsk >= Math.max(5, Cfg.I.boostersEverySecs) * 20L) {
            lastAsk = tick;
            request();
        }
    }

    /** While a Gem Shop is open the crop's own upgrade shows "Multiplier: 1.8x": follow it live while you buy. */
    private static void liveShop(Minecraft mc, AbstractContainerScreen<?> gs) {
        if (data.crop.isEmpty() || !gs.getTitle().getString().contains("Gem Shop")) return;
        Row r = row("gem");
        if (r == null) return;
        String crop = data.crop.toLowerCase();
        for (Slot s : gs.getMenu().slots) {
            if (s.container == mc.player.getInventory()) continue;
            ItemStack st = s.getItem();
            if (st.isEmpty() || !st.getHoverName().getString().toLowerCase().startsWith(crop)) continue;
            ItemLore l = st.get(DataComponents.LORE);
            if (l == null) continue;
            for (Component c : l.lines()) {
                Matcher m = SHOP_MULT.matcher(c.getString());
                if (m.find()) {
                    double v = num(m.group(1));
                    if (v > 0 && Math.abs(v - r.mult) > 0.004) {
                        scale(r, v);
                        expect = true;
                        changedAt = tick;
                    }
                    return;
                }
            }
        }
    }

    public static void onTick(Minecraft mc) {
        if (mc.player == null) {
            armorSig = null;
            handSig = null;
            return;
        }
        tick++;
        if (Cfg.I.boostersAuto && tick % 5 == 1) watch(mc);
        if (tick % 5 != 0) return;
        if (!(Scr.get(mc) instanceof AbstractContainerScreen<?> gs)) return;
        liveShop(mc, gs);
        for (Slot s : gs.getMenu().slots) {
            if (s.container == mc.player.getInventory()) continue;
            ItemStack st = s.getItem();
            if (st.isEmpty()) continue;
            ItemLore l = st.get(DataComponents.LORE);
            if (l == null) continue;
            List<String> lines = new ArrayList<>();
            for (Component c : l.lines()) lines.add(c.getString());
            if (parse(lines)) {
                remember(mc);
                Sync.done("stats", gs);
                return;
            }
        }
    }

    /** Returns true if the lore was a Boosters list. */
    static boolean parse(List<String> lines) {
        double total = 0;
        boolean found = false;
        String crop = "";
        List<Row> rows = new ArrayList<>();
        for (String line : lines) {
            Matcher t = TOTAL.matcher(line);
            if (t.find()) {
                total = num(t.group(1));
                found = true;
                continue;
            }
            Matcher r = ROW.matcher(line);
            if (r.find()) {
                Row row = new Row();
                row.name = r.group(1).trim();
                row.mult = num(r.group(2));
                if (r.group(3) != null) row.xp = num(r.group(3));
                rows.add(row);
                continue;
            }
            Matcher c = CROP.matcher(line);
            if (c.find() && line.trim().startsWith("Farming")) crop = c.group(1).trim();
        }
        if (!found || rows.isEmpty()) return false;
        Data d = new Data();
        d.total = total;
        d.crop = crop;
        d.rows = rows;
        d.updated = System.currentTimeMillis();
        boolean changed = data.total != d.total || data.rows.size() != d.rows.size() || !data.crop.equals(d.crop);
        data = d;
        if (changed) save();
        Milestones.Track ct = Milestones.find(d.crop);
        if (ct != null) Worlds.noteCrop(ct.world());
        // the server answered: if something was expected to change but the total is still the old one, ask again shortly
        if (expect) {
            boolean same = Math.abs(d.total - serverTotal) < 1e-9 * Math.max(1, serverTotal);
            if (same && retries < 2) {
                retries++;
                retryAt = tick + 40;
                data.est = true;
            } else {
                expect = false;
                retries = 0;
            }
        }
        serverTotal = d.total;
        return true;
    }
}
