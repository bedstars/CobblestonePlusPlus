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
 * Tracks what is in your /backpack.
 *
 *  - Action bar: "+2.90k Crying Obsidian (17.22M)" -> gain + new total, instantly, every block you break.
 *  - /backpack menus: item names "Pineapple (2.53M)" + lore "Sell All (127.70M)" -> exact totals and sell price.
 *  - The Backpack bundle item's tooltip: "Pumpkin » 8.89B (336.22B)" -> everything, no menu needed.
 */
public final class Backpack {
    private Backpack() {}

    public static final class Entry {
        public String name;
        public double amount;
        public double unit;            // sell value per single item
        public String cur = "coins";   // coins | gems
        public String cat = "Crops";   // Crops | Fish | Mob Drops
        public transient double session;
        public transient double lastGain;
        public transient long last;

        public double value() {
            return amount * unit;
        }

        public double gainValue() {
            return lastGain * unit;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final Map<String, Entry> items = new LinkedHashMap<>();
    public static long sessionStart = System.currentTimeMillis();
    private static boolean dirty;
    private static int tick;

    private static final Pattern GAIN = Pattern.compile(
            "\\+(" + Nums.NUM + ")\\s+[^A-Za-z+]*?([A-Za-z][A-Za-z' ]*?)\\s+\\((" + Nums.NUM + ")\\)");
    private static final Pattern MENU_NAME = Pattern.compile("^(.+?)\\s+\\((" + Nums.NUM + ")\\)\\s*$");
    private static final Pattern SELL = Pattern.compile("Sell All\\s+\\(\\D*?(" + Nums.NUM + ")\\)");
    private static final Pattern BUNDLE_LINE = Pattern.compile(
            "^\\s*[^A-Za-z]*?([A-Za-z][A-Za-z' ]*?)\\s*»\\s*(" + Nums.NUM + ")\\s*\\(\\D*?(" + Nums.NUM + ")\\)");

    // ------------------------------------------------------------ storage

    public static Entry get(String name) {
        return items.computeIfAbsent(name, n -> {
            Entry e = new Entry();
            e.name = n;
            return e;
        });
    }

    public static void load() {
        try {
            Path p = Cfg.dir().resolve("backpack.json");
            if (Files.exists(p)) {
                Map<String, Entry> m = GSON.fromJson(Files.readString(p), new TypeToken<LinkedHashMap<String, Entry>>() {}.getType());
                if (m != null) {
                    items.clear();
                    items.putAll(m);
                }
            }
        } catch (Throwable t) {
            CobblestonePlus.LOG.warn("Could not read backpack.json", t);
        }
    }

    public static void save() {
        try {
            Files.writeString(Cfg.dir().resolve("backpack.json"), GSON.toJson(items));
            dirty = false;
        } catch (Throwable t) {
            CobblestonePlus.LOG.warn("Could not save backpack.json", t);
        }
    }

    public static void resetSession() {
        sessionStart = System.currentTimeMillis();
        for (Entry e : items.values()) {
            e.session = 0;
            e.lastGain = 0;
            e.last = 0;
        }
    }

    public static void wipe() {
        items.clear();
        resetSession();
        save();
    }

    // ------------------------------------------------------------ action bar

    /** Called for every incoming action bar message. */
    public static void onActionBar(String text) {
        if (text == null || text.indexOf('+') < 0) return;
        Matcher m = GAIN.matcher(text);
        long now = System.currentTimeMillis();
        while (m.find()) {
            String name = m.group(2).trim();
            if (name.equalsIgnoreCase("xp")) continue;
            double gained = Nums.parse(m.group(1));
            double total = Nums.parse(m.group(3));
            if (gained <= 0) continue;
            Milestones.onGain(name);
            Entry e = get(name);
            e.amount = total;
            e.session += gained;
            e.lastGain = gained;
            e.last = now;
            dirty = true;
        }
    }

    // ------------------------------------------------------------ scanning

    public static void onTick(Minecraft mc) {
        if (mc.player == null) return;
        tick++;
        if (tick % 5 == 0) scanMenu(mc);
        if (tick % 10 == 0) scanBundle(mc);
        if (dirty && tick % 200 == 0) save();
    }

    private static List<String> lore(ItemStack st) {
        List<String> out = new ArrayList<>();
        ItemLore l = st.get(DataComponents.LORE);
        if (l == null) return out;
        for (Component c : l.lines()) out.add(c.getString());
        return out;
    }

    private static void scanMenu(Minecraft mc) {
        if (!(Scr.get(mc) instanceof AbstractContainerScreen<?> gs)) return;
        String title = gs.getTitle().getString().trim();
        if (!title.startsWith("Backpack:")) return;
        String cat = title.substring("Backpack:".length()).trim();
        String cur = cat.equalsIgnoreCase("Mob Drops") ? "gems" : "coins";
        for (Slot s : gs.getMenu().slots) {
            if (s.container == mc.player.getInventory()) continue;
            ItemStack st = s.getItem();
            if (st.isEmpty()) continue;
            Matcher nm = MENU_NAME.matcher(st.getHoverName().getString());
            if (!nm.matches()) continue;
            double amount = Nums.parse(nm.group(2));
            double value = 0;
            for (String line : lore(st)) {
                Matcher sm = SELL.matcher(line);
                if (sm.find()) {
                    value = Nums.parse(sm.group(1));
                    break;
                }
            }
            Entry e = get(nm.group(1).trim());
            e.amount = amount;
            e.cat = cat;
            e.cur = cur;
            if (amount > 0 && value > 0) e.unit = value / amount;
            dirty = true;
        }
    }

    private static void scanBundle(Minecraft mc) {
        int n = mc.player.getInventory().getContainerSize();
        for (int i = 0; i < n; i++) {
            ItemStack st = mc.player.getInventory().getItem(i);
            if (st.isEmpty() || !st.getHoverName().getString().trim().equalsIgnoreCase("Backpack")) continue;
            boolean any = false;
            for (String line : lore(st)) {
                Matcher m = BUNDLE_LINE.matcher(line);
                if (!m.find()) continue;
                double amount = Nums.parse(m.group(2));
                double value = Nums.parse(m.group(3));
                Entry e = get(m.group(1).trim());
                e.amount = amount;
                if (amount > 0 && value > 0) e.unit = value / amount;
                any = true;
            }
            if (any) dirty = true;
            return;
        }
    }
}
