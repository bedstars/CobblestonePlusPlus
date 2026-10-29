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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Game completion. Everything is read PASSIVELY - nothing is ever opened for you:
 *  - the "Completion 27.5%" item of /stats whenever you have it open (lore = every category with cur/max),
 *    also when that item sits in your inventory;
 *  - your own chat lines ("[27.5%] Name >> hi"), where the server shows the percentage before your name;
 *  - the Boss Index title ("Boss Index (2/6)") when you open it.
 */
public final class Completion {
    private Completion() {}

    public static final class Cat {
        public String name;
        public double cur, max;

        public double pct() {
            return max <= 0 ? 0 : Math.min(100, cur * 100.0 / max);
        }

        public boolean done() {
            return max > 0 && cur >= max;
        }
    }

    public static final class Data {
        public double percent = -1;
        public List<Cat> cats = new ArrayList<>();
        public long updated;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static Data data = new Data();
    private static int tick;

    private static final Pattern PCT = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*%");
    private static final Pattern CAT = Pattern.compile("^[^A-Za-z]*([A-Za-z][A-Za-z' ]*?)\\s*[:\\u00bb>\\-]*\\s*(" + Nums.NUM + ")\\s*/\\s*(" + Nums.NUM + ")\\b.*$");
    private static final Pattern PREFIX = Pattern.compile("\\[(\\d+(?:\\.\\d+)?)\\s*%\\]");
    private static final Pattern BOSS = Pattern.compile("^Boss Index\\s*\\((\\d+)\\s*/\\s*(\\d+)\\)");

    public static void load() {
        try {
            Path p = Cfg.dir().resolve("completion.json");
            if (Files.exists(p)) {
                Data d = GSON.fromJson(Files.readString(p), Data.class);
                if (d != null && d.cats != null) data = d;
            }
        } catch (Throwable t) {
            CobblestonePlus.LOG.warn("Could not read completion.json", t);
        }
    }

    private static void save() {
        try {
            Files.writeString(Cfg.dir().resolve("completion.json"), GSON.toJson(data));
        } catch (Throwable t) {
            CobblestonePlus.LOG.warn("Could not save completion.json", t);
        }
    }

    public static Cat find(String name) {
        if (name == null || name.isEmpty()) return null;
        for (Cat c : data.cats) if (c.name.equalsIgnoreCase(name.trim())) return c;
        return null;
    }

    public static boolean known() {
        return data.percent >= 0 || !data.cats.isEmpty();
    }

    /** Where / how to progress a category (from what the server's menus show). */
    public static String hint(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith("crops disc")) return "Farm every crop once - each world adds new crops.";
        if (n.startsWith("crop milestone")) return "/milestones > Farming: 5 stages per crop, finish them all.";
        if (n.startsWith("crop idol")) return "Idols are secret boss drops: kill the bosses.";
        if (n.startsWith("treasure")) return "Farm crops for treasures; open each world's vault in the Treasury.";
        if (n.startsWith("gem shop")) return "Spend gems in the Gem Shop: every block has its own upgrades.";
        if (n.startsWith("worlds")) return "Unlock the next world from the warp menu (see its requirements).";
        if (n.startsWith("reforge")) return "Reforge each world's tool in that world's forge.";
        if (n.startsWith("archipelago")) return "The fishing world - locked until its warp requirements are met.";
        if (n.startsWith("mob milestone")) return "/milestones > Mob: kill each world's mobs through 5 stages.";
        if (n.startsWith("bosses")) return "One boss per world. Open the Boss Index to see what you indexed.";
        if (n.startsWith("enchants unlocked")) return "Unlock enchants at the enchant area (sword and tool are separate).";
        if (n.startsWith("enchant levels")) return "Level up your enchants at the enchant area.";
        if (n.startsWith("recipes")) return "Honey Tree / Swamp / ... Crafting menus: meet the pinned requirements.";
        if (n.startsWith("gear")) return "Craft gear in each world's crafting menu.";
        if (n.startsWith("omnitool")) return "Awaken the omnitool.";
        if (n.startsWith("fish")) return "Fishing is in the Archipelago world.";
        if (n.startsWith("shrine")) return "Upgrade the shrine.";
        if (n.startsWith("pearl")) return "Upgrade with pearls.";
        if (n.startsWith("afk upgrade")) return "AFK bubble upgrades (farming / fighting / fishing tabs).";
        if (n.startsWith("afk milestone")) return "Stay inside an AFK bubble: 10 time milestones, +2% each.";
        if (n.startsWith("pets discovered")) return "Hatch pet eggs: 5 pets per world + 1 craftable mythic pet.";
        if (n.startsWith("pet slot")) return "3 unlockable pet slots - bought with crops in the Pets menu.";
        if (n.startsWith("achievement")) return "/achievements";
        if (n.startsWith("charm")) return "/charms - unlocked through treasure seals and rewards.";
        return "";
    }

    // ------------------------------------------------------------ reading

    private static List<String> lore(ItemStack st) {
        List<String> out = new ArrayList<>();
        ItemLore l = st.get(DataComponents.LORE);
        if (l == null) return out;
        for (Component c : l.lines()) out.add(c.getString());
        return out;
    }

    /** True when the stack is the Completion item; its data is stored. */
    static boolean parseItem(ItemStack st) {
        if (st.isEmpty()) return false;
        String hover = st.getHoverName().getString().trim();
        if (!hover.toLowerCase(Locale.ROOT).contains("completion")) return false;
        List<String> lines = lore(st);
        double pct = -1;
        Matcher pm = PCT.matcher(hover);
        if (pm.find()) pct = Double.parseDouble(pm.group(1).replace(',', '.'));
        else {
            for (String raw : lines) {
                Matcher q = PCT.matcher(raw);
                if (q.find()) {
                    pct = Double.parseDouble(q.group(1).replace(',', '.'));
                    break;
                }
            }
        }
        List<Cat> cats = new ArrayList<>();
        for (String raw : lines) {
            Matcher m = CAT.matcher(raw.trim());
            if (!m.find()) continue;
            Cat c = new Cat();
            c.name = m.group(1).trim();
            c.cur = Nums.parse(m.group(2));
            c.max = Nums.parse(m.group(3));
            if (c.max > 0) cats.add(c);
        }
        if (cats.isEmpty()) return false;
        Data d = new Data();
        if (pct < 0) {
            double c = 0, m = 0;
            for (Cat k : cats) {
                c += k.cur;
                m += k.max;
            }
            pct = data.percent >= 0 ? data.percent : (m > 0 ? Math.round(c * 1000.0 / m) / 10.0 : -1);
        }
        d.percent = pct;
        d.cats = cats;
        d.updated = System.currentTimeMillis();
        boolean changed = d.percent != data.percent || d.cats.size() != data.cats.size();
        for (int i = 0; !changed && i < cats.size(); i++) changed = cats.get(i).cur != data.cats.get(i).cur;
        data = d;
        if (changed) save();
        return true;
    }

    public static void onTick(Minecraft mc) {
        if (mc.player == null) return;
        tick++;
        if (tick % 10 == 0 && Scr.get(mc) instanceof AbstractContainerScreen<?> gs) {
            Matcher b = BOSS.matcher(gs.getTitle().getString().trim());
            if (b.find()) setCat("Bosses Defeated", Double.parseDouble(b.group(1)), Double.parseDouble(b.group(2)));
            for (Slot s : gs.getMenu().slots) {
                if (parseItem(s.getItem())) break;
            }
        }
        if (tick % 100 == 50) {
            int n = mc.player.getInventory().getContainerSize();
            for (int i = 0; i < n; i++) if (parseItem(mc.player.getInventory().getItem(i))) break;
        }
    }

    /** A stage / item was finished while playing: move the matching category on by hand (the next /stats read corrects it). */
    static void bump(String prefix, double delta) {
        String p = prefix.toLowerCase(Locale.ROOT);
        for (Cat c : data.cats) {
            if (!c.name.toLowerCase(Locale.ROOT).startsWith(p)) continue;
            if (c.max > 0 && ((long) c.max) % 5 == 0 && c.cur + delta <= c.max) {
                c.cur += delta;
                data.updated = System.currentTimeMillis();
                save();
            }
            return;
        }
    }

    private static void setCat(String name, double cur, double max) {
        Cat c = find(name);
        if (c == null) {
            if (!known()) return;
            c = new Cat();
            c.name = name;
            data.cats.add(c);
        }
        if (c.cur != cur || c.max != max) {
            c.cur = cur;
            c.max = max;
            save();
        }
    }

    /** Your own chat lines carry the percentage before your name. */
    public static void onChat(String t) {
        Minecraft mc = Minecraft.getInstance();
        if (t == null || mc.player == null) return;
        String me = mc.player.getName().getString();
        int at = t.indexOf(me);
        if (at < 0) return;
        Matcher m = PREFIX.matcher(t);
        if (m.find() && m.start() < at) {
            double p = Double.parseDouble(m.group(1));
            if (p != data.percent) {
                data.percent = p;
                data.updated = System.currentTimeMillis();
                save();
            }
        }
    }
}
