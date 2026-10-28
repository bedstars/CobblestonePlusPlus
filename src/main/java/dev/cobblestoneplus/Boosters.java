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

    public static void onTick(Minecraft mc) {
        if (mc.player == null) return;
        if (++tick % 5 != 0) return;
        if (!(Scr.get(mc) instanceof AbstractContainerScreen<?> gs)) return;
        for (Slot s : gs.getMenu().slots) {
            if (s.container == mc.player.getInventory()) continue;
            ItemStack st = s.getItem();
            if (st.isEmpty()) continue;
            ItemLore l = st.get(DataComponents.LORE);
            if (l == null) continue;
            List<String> lines = new ArrayList<>();
            for (Component c : l.lines()) lines.add(c.getString());
            if (parse(lines)) return;
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
