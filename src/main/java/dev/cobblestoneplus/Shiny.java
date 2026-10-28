package dev.cobblestoneplus;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds shiny blocks around you (client-side, from entities the server already sent):
 *   ECHO = outline colour cycles through the rainbow
 *   RUSH = steady yellow / gold outline
 * Normal shinies are ignored.
 *
 * Shiny blocks are the floating glowing blocks, so candidates are glowing display / armor stand /
 * falling block entities. The outline colour is read from the entity's glow colour. If the server
 * draws them some other way, /cpp shiny debug lists what is around you so the detection can be tuned.
 */
public final class Shiny {
    private Shiny() {}

    public enum Kind {ECHO, RUSH}

    public static final class Target {
        public int id;
        public Kind kind;
        public double x, y, z, dist, dy;
        public float rel; // degrees relative to where you look, + = to your right
        public int color;
    }

    private static final class Hist {
        final List<long[]> samples = new ArrayList<>(); // {time, hueBucket}
        int lastColor;
        long seen;
    }

    private static final long WINDOW = 4000;
    private static final Map<Integer, Hist> hist = new HashMap<>();
    public static List<Target> targets = new ArrayList<>();
    private static int tick;

    private static Method mGlow, mTeam, mOverride;
    private static boolean looked;

    private static void look() {
        if (looked) return;
        looked = true;
        mGlow = find(Entity.class, "isCurrentlyGlowing");
        mTeam = find(Entity.class, "getTeamColor");
        try {
            Class<?> d = Class.forName("net.minecraft.world.entity.Display");
            mOverride = find(d, "getGlowColorOverride");
        } catch (Throwable ignored) {
        }
    }

    private static Method find(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static boolean glowing(Entity e) {
        try {
            if (mGlow != null) return (boolean) mGlow.invoke(e);
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static int color(Entity e) {
        try {
            if (mOverride != null && mOverride.getDeclaringClass().isInstance(e)) {
                int c = (int) mOverride.invoke(e);
                if (c != -1) return c & 0xFFFFFF;
            }
            if (mTeam != null) return ((int) mTeam.invoke(e)) & 0xFFFFFF;
        } catch (Throwable ignored) {
        }
        return 0xFFFFFF;
    }

    private static String typeOf(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    private static boolean candidateType(String t) {
        return t.contains("display") || t.contains("armor_stand") || t.contains("falling_block");
    }

    /** 0..11 hue bucket, -1 for grey / dark. */
    private static int bucket(int rgb) {
        float[] hsv = hsv(rgb);
        if (hsv[1] < 0.25f || hsv[2] < 0.25f) return -1;
        return (int) (hsv[0] / 30f) % 12;
    }

    static float[] hsv(int rgb) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h;
        if (d == 0) h = 0;
        else if (max == r) h = 60f * (((g - b) / d) % 6f);
        else if (max == g) h = 60f * (((b - r) / d) + 2f);
        else h = 60f * (((r - g) / d) + 4f);
        if (h < 0) h += 360f;
        return new float[]{h, max == 0 ? 0 : d / max, max};
    }

    public static int rainbow(double speed) {
        float h = (float) ((System.currentTimeMillis() * speed) % 360.0) / 360f;
        return java.awt.Color.HSBtoRGB(h, 0.8f, 1f) & 0xFFFFFF;
    }

    public static void onTick(Minecraft mc) {
        if (mc.player == null || mc.level == null || !Cfg.I.shinyHud) {
            targets = new ArrayList<>();
            hist.clear();
            return;
        }
        if (++tick % 2 != 0) return;
        look();
        long now = System.currentTimeMillis();
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        float yaw = mc.player.getYRot();
        double range = Cfg.I.shinyRange;
        List<Target> out = new ArrayList<>();
        Set<Integer> alive = new HashSet<>();

        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || !candidateType(typeOf(e)) || !glowing(e)) continue;
            double dx = e.getX() - px, dy = e.getY() - py, dz = e.getZ() - pz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > range) continue;
            int col = color(e);
            Hist h = hist.computeIfAbsent(e.getId(), k -> new Hist());
            h.lastColor = col;
            h.seen = now;
            alive.add(e.getId());
            h.samples.add(new long[]{now, bucket(col)});
            h.samples.removeIf(s -> now - s[0] > WINDOW);

            Set<Long> distinct = new HashSet<>();
            for (long[] s : h.samples) if (s[1] >= 0) distinct.add(s[1]);
            Kind kind = null;
            if (distinct.size() >= 3) kind = Kind.ECHO;
            else if (distinct.size() == 1 && distinct.contains(1L) || distinct.size() == 1 && distinct.contains(2L)) {
                // hue 30-90 degrees = orange-yellow / yellow
                float[] hv = hsv(col);
                if (hv[0] >= 35f && hv[0] <= 65f && hv[1] >= 0.5f && hv[2] >= 0.7f) kind = Kind.RUSH;
            }
            if (kind == null) continue;
            if (kind == Kind.ECHO && !Cfg.I.shinyEcho) continue;
            if (kind == Kind.RUSH && !Cfg.I.shinyRush) continue;

            Target t = new Target();
            t.id = e.getId();
            t.kind = kind;
            t.x = e.getX();
            t.y = e.getY();
            t.z = e.getZ();
            t.dist = Math.sqrt(dx * dx + dz * dz);
            t.dy = dy;
            t.color = col;
            double ang = Math.toDegrees(Math.atan2(-dx, dz));
            double rel = ang - yaw;
            rel = ((rel % 360) + 540) % 360 - 180;
            t.rel = (float) rel;
            out.add(t);
        }
        for (Iterator<Map.Entry<Integer, Hist>> it = hist.entrySet().iterator(); it.hasNext(); ) {
            if (!alive.contains(it.next().getKey())) it.remove();
        }
        out.sort(Comparator.comparingDouble(t -> t.dist));
        targets = out;
    }

    /** For /cpp shiny debug: every glowing entity within 64 blocks. */
    public static List<String> debug(Minecraft mc) {
        List<String> out = new ArrayList<>();
        if (mc.player == null || mc.level == null) return out;
        look();
        List<Entity> es = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || !glowing(e)) continue;
            if (e.distanceTo(mc.player) > 64) continue;
            es.add(e);
        }
        es.sort(Comparator.comparingDouble(e -> e.distanceTo(mc.player)));
        for (Entity e : es) {
            if (out.size() >= 14) break;
            out.add(String.format(Locale.ROOT, "%s  %.0fm  glow=#%06X  %s%s", typeOf(e), (double) e.distanceTo(mc.player), color(e),
                    candidateType(typeOf(e)) ? "candidate" : "ignored-type", e.hasCustomName() ? "  name=" + e.getName().getString() : ""));
        }
        return out;
    }
}
