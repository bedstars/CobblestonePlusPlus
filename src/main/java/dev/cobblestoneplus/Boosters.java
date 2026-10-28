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

    private static String sig;
    private static long changedAt = -1, lastAsk;
    private static boolean wasMenu;
    private static final Pattern BUYING = Pattern.compile("(?i)\\b(purchased|bought|upgraded|upgrade|equipped|unlocked|enchanted|crafted|forged)\\b");

    /** Asks the server for /stats (read + closed automatically) when auto-update is on. */
    public static void request() {
        if (Cfg.I.boostersAuto) Sync.ask("stats", false);
    }

    /** Manual update from the menu / command: works even with a screen open. */
    public static void refresh() {
        Sync.ask("stats", true);
    }

    public static void onChat(String t) {
        if (t == null || t.contains("\u00bb")) return; // player chat
        if (BUYING.matcher(t).find()) request();
    }

    private static String item(ItemStack st) {
        return st.isEmpty() ? "-" : st.getHoverName().getString() + "x" + st.getCount();
    }

    private static String signature(Minecraft mc) {
        StringBuilder sb = new StringBuilder();
        sb.append(item(mc.player.getMainHandItem())).append('|').append(item(mc.player.getOffhandItem()));
        for (EquipmentSlot es : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            sb.append('|').append(item(mc.player.getItemBySlot(es)));
        }
        return sb.toString();
    }

    /** Armor on / off, tool held / not held, an upgrade bought (a menu was closed): ask for the new multiplier. */
    private static void watch(Minecraft mc) {
        String s = signature(mc);
        if (sig == null) {
            sig = s;
            lastAsk = tick;
            request();
            return;
        }
        if (!s.equals(sig)) {
            sig = s;
            changedAt = tick;
        }
        boolean menu = Scr.get(mc) instanceof AbstractContainerScreen<?>;
        if (wasMenu && !menu && !Sync.busy()) changedAt = tick; // closed a shop / inventory: something may have changed
        wasMenu = menu;
        if (changedAt >= 0 && tick - changedAt >= 10) {
            changedAt = -1;
            lastAsk = tick;
            request();
        } else if (tick - lastAsk >= Math.max(5, Cfg.I.boostersEverySecs) * 20L) {
            lastAsk = tick;
            request();
        }
    }

    public static void onTick(Minecraft mc) {
        if (mc.player == null) {
            sig = null;
            return;
        }
        tick++;
        if (Cfg.I.boostersAuto && tick % 5 == 1) watch(mc);
        if (tick % 5 != 0) return;
        if (!(Scr.get(mc) instanceof AbstractContainerScreen<?> gs)) return;
        for (Slot s : gs.getMenu().slots) {
            if (s.container == mc.player.getInventory()) continue;
            ItemStack st = s.getItem();
            if (st.isEmpty()) continue;
            ItemLore l = st.get(DataComponents.LORE);
            if (l == null) continue;
            List<String> lines = new ArrayList<>();
            for (Component c : l.lines()) lines.add(c.getString());
            if (parse(lines)) {
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
        return true;
    }
}
