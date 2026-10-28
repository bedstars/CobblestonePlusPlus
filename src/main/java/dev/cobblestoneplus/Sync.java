package dev.cobblestoneplus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Asks the server for a menu (/stats, /treasures), lets the readers parse it and closes it again.
 * Requests are queued and only sent when no screen is open (unless forced, e.g. from a button), at most one
 * every 3 seconds. Nothing is sent that you could not type yourself.
 */
final class Sync {
    private Sync() {}

    private static final ArrayDeque<String> queue = new ArrayDeque<>();
    private static final Set<String> forced = new HashSet<>();
    private static String active;
    private static int tick, sentAt = -1000, quietUntil;

    static void ask(String cmd, boolean force) {
        if (force) forced.add(cmd);
        if (!queue.contains(cmd) && !cmd.equals(active)) queue.add(cmd);
    }

    /** True while one of our own menus is open / was just closed (so closing it must not trigger another refresh). */
    static boolean busy() {
        return active != null || tick < quietUntil;
    }

    static void onTick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            tick = 0;
            queue.clear();
            forced.clear();
            active = null;
            return;
        }
        tick++;
        Screen s = Scr.get(mc);
        if (active != null) {
            if (tick - sentAt > 60) { // the menu never showed up / was not recognised
                if (s instanceof AbstractContainerScreen<?> c) c.onClose();
                active = null;
                quietUntil = tick + 10;
            }
            return;
        }
        if (queue.isEmpty() || tick < 100 || tick - sentAt < 60) return;
        String cmd = queue.peek();
        boolean force = forced.contains(cmd);
        if (!force && s != null) return;
        queue.poll();
        forced.remove(cmd);
        if (send(mc, cmd)) {
            active = cmd;
            sentAt = tick;
        } else {
            CobblestonePlus.LOG.warn("Could not send /" + cmd);
        }
    }

    /** A reader found what it was waiting for in the menu: close it. */
    static void done(String cmd, AbstractContainerScreen<?> gs) {
        if (!cmd.equals(active)) return;
        active = null;
        quietUntil = tick + 10;
        gs.onClose();
    }

    private static boolean send(Minecraft mc, String cmd) {
        try {
            Field f = null;
            for (Class<?> c = mc.player.getClass(); c != null && f == null; c = c.getSuperclass()) {
                try {
                    f = c.getDeclaredField("connection");
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (f == null) return false;
            f.setAccessible(true);
            Object conn = f.get(mc.player);
            for (Class<?> c = conn.getClass(); c != null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("sendCommand") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
                        m.setAccessible(true);
                        m.invoke(conn, cmd);
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            CobblestonePlus.LOG.warn("Could not send /" + cmd, t);
        }
        return false;
    }
}
