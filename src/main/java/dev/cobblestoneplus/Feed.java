package dev.cobblestoneplus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the server shows you without a menu, in one place: chat, action bar, title, subtitle, boss bars
 * (and the sidebar, for the log only). Every line goes to the passive readers (milestones ...).
 * With "/cpp feed on" every line NO reader understood is written to config/cobblestoneplus/feed.log, so the
 * readers can be taught the server's real wording.
 */
final class Feed {
    private Feed() {}

    private static final String[] last = new String[3];
    private static Set<String> lastBoss = new HashSet<>();
    private static String lastSide = "", lastLogged = "";
    private static int tick;
    private static boolean broken;
    private static Field guiF;
    private static Method bossM;
    private static Field eventsF;
    private static boolean looked;

    static Path path() {
        return Cfg.dir().resolve("feed.log");
    }

    static void chat(String t) {
        handle("chat", t);
    }

    static void overlay(String t) {
        handle("actionbar", t);
    }

    private static void handle(String chan, String text) {
        if (text == null || text.isEmpty()) return;
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            boolean used = Milestones.onText(line);
            if (!used && Cfg.I.feedLog) log(chan, line);
        }
    }

    /** A line for the log from inside the mod (what the live counters decided). */
    static void note(String chan, String line) {
        if (Cfg.I.feedLog) log(chan, line);
    }

    private static String lastMenu = "";

    /** With the log on, every menu you open is written out once: title, item names and tooltip lines. */
    private static void dumpMenu(Minecraft mc) {
        if (!(Scr.get(mc) instanceof AbstractContainerScreen<?> gs)) {
            lastMenu = "";
            return;
        }
        StringBuilder sb = new StringBuilder("[menu] " + gs.getTitle().getString() + "\n");
        int n = 0;
        for (Slot s : gs.getMenu().slots) {
            if (s.container == mc.player.getInventory()) continue;
            ItemStack st = s.getItem();
            if (st.isEmpty()) continue;
            if (++n > 60) break;
            sb.append("  ").append(st.getHoverName().getString()).append('\n');
            ItemLore l = st.get(DataComponents.LORE);
            if (l != null) for (Component c : l.lines()) sb.append("      | ").append(c.getString()).append('\n');
        }
        String sig = sb.toString();
        if (sig.equals(lastMenu)) return;
        lastMenu = sig;
        try {
            Path p = path();
            if (Files.exists(p) && Files.size(p) > 400_000) return;
            Files.writeString(p, sig, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }

    private static void log(String chan, String line) {
        if (chan.equals("actionbar") && line.startsWith("+")) return; // plain gains are read by the backpack
        String entry = "[" + chan + "] " + line;
        if (entry.equals(lastLogged)) return;
        lastLogged = entry;
        try {
            Path p = path();
            if (Files.exists(p) && Files.size(p) > 400_000) return;
            Files.writeString(p, entry + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ title / subtitle / boss bars / sidebar

    private static Field field(Class<?> c, String name) {
        for (; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String comp(Object gui, String name) throws Exception {
        Field f = field(gui.getClass(), name);
        if (f == null) return "";
        Object v = f.get(gui);
        return v instanceof Component c ? c.getString() : "";
    }

    private static void changed(int i, String chan, String text) {
        if (text.isEmpty()) {
            last[i] = "";
            return;
        }
        if (text.equals(last[i])) return;
        last[i] = text;
        handle(chan, text);
    }

    @SuppressWarnings("unchecked")
    private static void bossbars(Object gui) throws Exception {
        if (!looked) {
            looked = true;
            for (Method m : gui.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getName().equals("getBossOverlay")) bossM = m;
            }
        }
        if (bossM == null) return;
        Object overlay = bossM.invoke(gui);
        if (overlay == null) return;
        if (eventsF == null) {
            for (Class<?> c = overlay.getClass(); c != null && eventsF == null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Map.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        eventsF = f;
                        break;
                    }
                }
            }
        }
        if (eventsF == null) return;
        Set<String> now = new HashSet<>();
        for (Object ev : ((Map<Object, Object>) eventsF.get(overlay)).values()) {
            Method gn = null;
            for (Method m : ev.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getName().equals("getName")) gn = m;
            }
            if (gn == null) continue;
            Object n = gn.invoke(ev);
            if (n instanceof Component c) now.add(c.getString());
        }
        for (String s : now) if (!lastBoss.contains(s)) handle("bossbar", s);
        lastBoss = now;
    }

    static void onTick(Minecraft mc) {
        if (mc.player == null || mc.level == null || broken) return;
        if (++tick % 4 != 0) return;
        try {
            if (guiF == null) guiF = field(Minecraft.class, "gui");
            Object gui = guiF == null ? null : guiF.get(mc);
            if (gui != null) {
                changed(0, "title", comp(gui, "title"));
                changed(1, "subtitle", comp(gui, "subtitle"));
                bossbars(gui);
            }
            if (Cfg.I.feedLog && tick % 20 == 0) {
                try {
                    dumpMenu(mc);
                } catch (Throwable ignored) {
                }
                List<String> side = Pins.sidebar(mc);
                String joined = String.join("\n", side);
                if (!joined.equals(lastSide)) {
                    lastSide = joined;
                    for (String s : side) log("sidebar", s.trim());
                }
            }
        } catch (Throwable t) {
            broken = true;
            CobblestonePlus.LOG.warn("Title / boss bar reading is not available on this version", t);
        }
    }
}
