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
import java.util.TreeMap;

/**
 * Finds shiny blocks around you (client-side, from entities the server already sent).
 *
 *   ECHO = the glow PULSES (dark blue -> cyan -> near white and back, ~1.3s per cycle). A rainbow cycle or a steady
 *          blue / cyan glow counts as well.
 *   RUSH = steady yellow / gold glow AND the yellow rings on the ground around it (a number of display entities
 *          within a few blocks). Without the rings a yellow glow is just a normal block on yellow terrain.
 * Normal shinies (steady purple / pink ...) are ignored.
 *
 * /cpp shiny debug lists every glowing entity near you with its colour, how much that colour moved, the ring count
 * and what else is lying around it, so the detection can be tuned.
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
        public int rings;
    }

    private static final class Hist {
        final List<long[]> samples = new ArrayList<>(); // {time, rgb}
        int lastColor;
        long seen;
    }

    /** What the colour history of one entity looks like. */
    private record Stats(int spread, long span, boolean rainbow, boolean yellowish, boolean blueish, float hue) {}

    private static final long WINDOW = 4000;
    private static final double RING_RADIUS = 6.5;
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

    /** Entity types the rings could be made of. */
    private static boolean ringType(String t) {
        return t.contains("display") || t.contains("armor_stand") || t.contains("area_effect_cloud");
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

    // ------------------------------------------------------------ classification

    private static Stats stats(Hist h) {
        int minR = 255, maxR = 0, minG = 255, maxG = 0, minB = 255, maxB = 0;
        Set<Integer> buckets = new HashSet<>();
        float bestSat = -1, bestHue = 0;
        for (long[] s : h.samples) {
            int c = (int) s[1];
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            minR = Math.min(minR, r);
            maxR = Math.max(maxR, r);
            minG = Math.min(minG, g);
            maxG = Math.max(maxG, g);
            minB = Math.min(minB, b);
            maxB = Math.max(maxB, b);
            int bk = bucket(c);
            if (bk >= 0) buckets.add(bk);
            float[] hv = hsv(c);
            if (hv[2] > 0.3f && hv[1] > bestSat) {
                bestSat = hv[1];
                bestHue = hv[0];
            }
        }
        int spread = Math.max(maxR - minR, Math.max(maxG - minG, maxB - minB));
        long span = h.samples.isEmpty() ? 0 : h.samples.get(h.samples.size() - 1)[0] - h.samples.get(0)[0];
        boolean yellowish = bestSat >= 0.45f && bestHue >= 35f && bestHue <= 70f;
        boolean blueish = bestSat >= 0.35f && bestHue >= 165f && bestHue <= 265f;
        return new Stats(spread, span, buckets.size() >= 3, yellowish, blueish, bestHue);
    }

    /** ECHO, RUSH (before the ring check) or null for a normal / unknown block. */
    private static Kind classify(Stats st) {
        if (st.rainbow()) return Kind.ECHO;                                   // old style: cycles through the rainbow
        if (st.yellowish() && st.spread() < 60) return Kind.RUSH;             // steady yellow, rings still to be checked
        if (st.spread() >= 60 && !st.yellowish() && st.span() >= 800) return Kind.ECHO; // pulsing glow
        if (st.blueish() && Cfg.I.shinyBlueEcho && st.span() >= 600) return Kind.ECHO; // steady blue / cyan
        return null;
    }

    /** Ring pieces: display entities (or similar) around a block that are not glowing themselves. */
    private static int rings(Minecraft mc, Entity centre) {
        int n = 0;
        for (Entity o : mc.level.entitiesForRendering()) {
            if (o == centre || o == mc.player || glowing(o) || !ringType(typeOf(o))) continue;
            double dx = o.getX() - centre.getX(), dz = o.getZ() - centre.getZ(), dy = o.getY() - centre.getY();
            if (dy < -8 || dy > 3) continue;
            if (dx * dx + dz * dz > RING_RADIUS * RING_RADIUS) continue;
            n++;
        }
        return n;
    }

    /** What is lying around an entity: type -> count (everything that is not glowing, within 8 blocks). */
    private static Map<String, Integer> around(Minecraft mc, Entity centre) {
        Map<String, Integer> m = new TreeMap<>();
        for (Entity o : mc.level.entitiesForRendering()) {
            if (o == centre || o == mc.player || glowing(o)) continue;
            double dx = o.getX() - centre.getX(), dz = o.getZ() - centre.getZ(), dy = o.getY() - centre.getY();
            if (dx * dx + dz * dz > 64 || Math.abs(dy) > 8) continue;
            m.merge(typeOf(o).replace("minecraft:", ""), 1, Integer::sum);
        }
        return m;
    }

    // ------------------------------------------------------------ tick

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
            h.samples.add(new long[]{now, col});
            h.samples.removeIf(s -> now - s[0] > WINDOW);

            Kind kind = classify(stats(h));
            if (kind == null) continue;
            if (kind == Kind.ECHO && !Cfg.I.shinyEcho) continue;
            if (kind == Kind.RUSH && !Cfg.I.shinyRush) continue;
            int ringCount = 0;
            if (kind == Kind.RUSH) {
                ringCount = rings(mc, e);
                if (Cfg.I.shinyNeedRings && ringCount < Cfg.I.shinyRingMin) continue; // yellow but no rings = normal block
            }

            Target t = new Target();
            t.id = e.getId();
            t.kind = kind;
            t.x = e.getX();
            t.y = e.getY();
            t.z = e.getZ();
            t.dist = Math.sqrt(dx * dx + dz * dz);
            t.dy = dy;
            t.color = col;
            t.rings = ringCount;
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
            if (out.size() >= 12) break;
            Hist h = hist.get(e.getId());
            String info = "no colour history yet";
            String guess = "-";
            if (h != null && !h.samples.isEmpty()) {
                Stats st = stats(h);
                Kind k = classify(st);
                guess = k == null ? "normal" : k.name();
                info = String.format(Locale.ROOT, "moved=%d over %.1fs hue=%.0f%s%s%s", st.spread(), st.span() / 1000.0, st.hue(),
                        st.rainbow() ? " rainbow" : "", st.yellowish() ? " yellow" : "", st.blueish() ? " blue" : "");
            }
            out.add(String.format(Locale.ROOT, "%s  %.0fm  glow=#%06X  %s  -> %s  rings=%d%s", typeOf(e).replace("minecraft:", ""),
                    (double) e.distanceTo(mc.player), color(e), info, guess, rings(mc, e),
                    e.hasCustomName() ? "  name=" + e.getName().getString() : ""));
            if (!candidateType(typeOf(e))) out.add("   (ignored type - not a display / armor stand / falling block)");
            Map<String, Integer> near = around(mc, e);
            if (!near.isEmpty()) {
                StringBuilder sb = new StringBuilder("   around it: ");
                int c = 0;
                for (Map.Entry<String, Integer> en : near.entrySet()) {
                    if (c++ > 0) sb.append(", ");
                    sb.append(en.getKey()).append(" x").append(en.getValue());
                }
                out.add(sb.toString());
            }
        }
        return out;
    }
}
