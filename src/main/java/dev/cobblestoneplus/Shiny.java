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
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds shiny blocks around you (client-side, from entities the server already sent).
 *
 *   ECHO = the block PULSES. Any of these counts: the glow colour moves (dark blue -> cyan -> near white and back),
 *          the glow flashes on / off, a rainbow cycle, or a blue / cyan glow whose entity data keeps changing.
 *          The history is kept while the glow is off, so a flashing block is no longer forgotten every flash.
 *   RUSH = steady yellow glow AND its rings: several stationary display entities lying in concentric bands
 *          around it (not near a player, not moving, yellow when their block / item can be read).
 *          A yellow glow without rings is a normal block on yellow terrain.
 * Normal shinies (steady purple / pink ...) are ignored.
 *
 * /cpp shiny debug lists every glowing entity near you with its colour history, flashes, data changes and the
 * ring breakdown, so the detection can be tuned from real numbers.
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
        final List<long[]> samples = new ArrayList<>(); // {time, rgb or -1 when the glow is off}
        final List<Long> changes = new ArrayList<>();   // times the synched entity data changed
        int lastSig;
        boolean sigKnown;
        long glowAt;
    }

    /** What the history of one entity looks like. */
    private record Stats(int spread, long span, boolean rainbow, boolean yellowish, boolean blueish, float hue, int toggles, int changes) {}

    private record Rings(int pieces, int bands, int yellow, int unknown, int unstable, int nearPlayer) {
        boolean ok() {
            int min = Math.max(1, Cfg.I.shinyRingMin);
            if (pieces < min) return false;
            if (yellow == 0 && unknown == 0) return false;
            return bands >= 2 || pieces >= min * 2;
        }
    }

    private static final long WINDOW = 4000, KEEP = 3500;
    private static final double RING_RADIUS = 7.5;
    private static final Map<Integer, Hist> hist = new HashMap<>();
    private static final Map<Long, double[]> radii = new HashMap<>(); // (centre, piece) -> {radius, since}
    private static final Map<Class<?>, Optional<Method>> colorMethods = new HashMap<>();
    public static List<Target> targets = new ArrayList<>();
    private static int tick;

    private static Method mGlow, mTeam, mOverride, mData, mNonDefault;
    private static boolean looked;

    private static void look() {
        if (looked) return;
        looked = true;
        mGlow = find(Entity.class, "isCurrentlyGlowing");
        mTeam = find(Entity.class, "getTeamColor");
        mData = find(Entity.class, "getEntityData");
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

    /** Hash of everything the server synched on this entity that differs from the default (changes = it animates). */
    private static int dataSig(Entity e) {
        try {
            if (mData == null) return 0;
            Object d = mData.invoke(e);
            if (d == null) return 0;
            if (mNonDefault == null) mNonDefault = find(d.getClass(), "getNonDefaultValues");
            if (mNonDefault == null) return 0;
            Object v = mNonDefault.invoke(d);
            return v == null ? 0 : v.toString().hashCode();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String typeOf(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    private static boolean candidateType(String t) {
        return t.contains("display") || t.contains("armor_stand") || t.contains("falling_block") || t.equals("minecraft:item");
    }

    /** Entity types the rings could be made of. */
    private static boolean ringType(String t) {
        return t.contains("display") || t.contains("armor_stand") || t.contains("area_effect_cloud") || t.contains("marker");
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

    private static Stats stats(Hist h, long now) {
        int minR = 255, maxR = 0, minG = 255, maxG = 0, minB = 255, maxB = 0;
        Set<Integer> buckets = new HashSet<>();
        float bestSat = -1, bestHue = 0;
        int toggles = 0;
        boolean prevOn = true, first = true;
        long t0 = Long.MAX_VALUE, t1 = 0;
        for (long[] s : h.samples) {
            boolean on = s[1] >= 0;
            if (!first && on != prevOn) toggles++;
            prevOn = on;
            first = false;
            if (!on) continue;
            t0 = Math.min(t0, s[0]);
            t1 = Math.max(t1, s[0]);
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
        int spread = t0 == Long.MAX_VALUE ? 0 : Math.max(maxR - minR, Math.max(maxG - minG, maxB - minB));
        long span = h.samples.isEmpty() ? 0 : h.samples.get(h.samples.size() - 1)[0] - h.samples.get(0)[0];
        boolean yellowish = bestSat >= 0.45f && bestHue >= 35f && bestHue <= 70f;
        boolean blueish = bestSat >= 0.35f && bestHue >= 165f && bestHue <= 265f;
        int changes = 0;
        for (long t : h.changes) if (now - t <= WINDOW) changes++;
        return new Stats(spread, span, buckets.size() >= 3, yellowish, blueish, bestHue, toggles, changes);
    }

    /** ECHO, RUSH (before the ring check) or null for a normal / unknown block. */
    private static Kind classify(Stats st) {
        if (st.rainbow()) return Kind.ECHO;                                                  // cycles through the rainbow
        if (st.yellowish() && st.spread() < 60 && st.toggles() < 3) return Kind.RUSH;        // steady yellow, rings still to be checked
        if (st.yellowish()) return null;
        if (st.spread() >= 60 && st.span() >= 700) return Kind.ECHO;                         // the glow colour pulses
        if (st.toggles() >= 4) return Kind.ECHO;                                             // the glow flashes on / off
        if (st.blueish() && st.changes() >= 5) return Kind.ECHO;                             // blue and animating
        if (st.blueish() && Cfg.I.shinyBlueEcho && st.span() >= 600) return Kind.ECHO;       // steady blue / cyan
        return null;
    }

    // ------------------------------------------------------------ rings

    private static Optional<Method> colorMethod(Class<?> c) {
        Optional<Method> cached = colorMethods.get(c);
        if (cached != null) return cached;
        Method found = null;
        for (Class<?> k = c; k != null && k != Entity.class && found == null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getParameterCount() != 0 || java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                String rt = m.getReturnType().getSimpleName();
                if (rt.equals("BlockState") || rt.equals("ItemStack")) {
                    try {
                        m.setAccessible(true);
                        found = m;
                        break;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        Optional<Method> r = Optional.ofNullable(found);
        colorMethods.put(c, r);
        return r;
    }

    /** +1 yellow block / item, -1 clearly another colour, 0 unknown (text display, unreadable ...). */
    private static int yellowness(Entity o, String type) {
        if (!type.contains("block_display") && !type.contains("item_display")) return 0;
        try {
            Optional<Method> m = colorMethod(o.getClass());
            if (m.isEmpty()) return 0;
            String s = String.valueOf(m.get().invoke(o)).toLowerCase(Locale.ROOT);
            if (s.contains("air")) return 0;
            if (s.contains("yellow") || s.contains("gold") || s.contains("amber") || s.contains("honey") || s.contains("glowstone") || s.contains("hay")) return 1;
            for (String bad : new String[]{"pink", "purple", "magenta", "blue", "red", "green", "lime", "cyan", "white", "black", "gray", "grey", "brown", "orange"}) {
                if (s.contains(bad)) return -1;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static boolean nearPlayer(Minecraft mc, Entity o) {
        for (var p : mc.level.players()) {
            double dx = o.getX() - p.getX(), dz = o.getZ() - p.getZ(), dy = o.getY() - p.getY();
            if (dx * dx + dz * dz < 9 && Math.abs(dy) < 3.5) return true; // pets / cosmetics that follow a player
        }
        return false;
    }

    /** Ring pieces: display entities (or similar) around a block that are not glowing themselves. */
    private static Rings rings(Minecraft mc, Entity centre, List<Entity> all, long now, Set<Long> touched) {
        int pieces = 0, yel = 0, unk = 0, unst = 0, near = 0;
        Set<Integer> bands = new HashSet<>();
        for (Entity o : all) {
            if (o == centre || o == mc.player) continue;
            String t = typeOf(o);
            if (!ringType(t) || glowing(o)) continue;
            double dx = o.getX() - centre.getX(), dz = o.getZ() - centre.getZ(), dy = o.getY() - centre.getY();
            if (dy < -8 || dy > 2) continue;
            double r = Math.sqrt(dx * dx + dz * dz);
            if (r > RING_RADIUS) continue;
            if (nearPlayer(mc, o)) {
                near++;
                continue;
            }
            int y = yellowness(o, t);
            if (y < 0) continue;
            long key = ((long) centre.getId() << 32) | (o.getId() & 0xFFFFFFFFL);
            double[] st = radii.get(key);
            if (st == null || Math.abs(st[0] - r) > 0.35) {
                st = new double[]{r, now};
                radii.put(key, st);
            }
            touched.add(key);
            if (now - (long) st[1] < 1000) { // not stable for a second yet: something that moves (a pet, a hologram...)
                unst++;
                continue;
            }
            pieces++;
            if (y > 0) yel++;
            else unk++;
            bands.add((int) Math.floor(r / 0.75));
        }
        return new Rings(pieces, bands.size(), yel, unk, unst, near);
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

    private static List<Entity> snapshot(Minecraft mc) {
        List<Entity> all = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) all.add(e);
        return all;
    }

    public static void onTick(Minecraft mc) {
        if (mc.player == null || mc.level == null || !Cfg.I.shinyHud) {
            targets = new ArrayList<>();
            hist.clear();
            radii.clear();
            return;
        }
        if (++tick % 2 != 0) return;
        look();
        long now = System.currentTimeMillis();
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        float yaw = mc.player.getYRot();
        double range = Cfg.I.shinyRange;
        List<Target> out = new ArrayList<>();
        Set<Integer> present = new HashSet<>();
        Set<Long> touched = new HashSet<>();
        List<Entity> all = snapshot(mc);

        for (Entity e : all) {
            if (e == mc.player) continue;
            String type = typeOf(e);
            if (!candidateType(type)) continue;
            double dx = e.getX() - px, dy = e.getY() - py, dz = e.getZ() - pz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > range) continue;
            boolean glow = glowing(e);
            Hist h = hist.get(e.getId());
            if (h == null) {
                if (!glow) continue;
                h = new Hist();
                hist.put(e.getId(), h);
            }
            present.add(e.getId());
            if (glow) h.glowAt = now;
            int col = glow ? color(e) : -1;
            h.samples.add(new long[]{now, col});
            h.samples.removeIf(s -> now - s[0] > WINDOW);
            int sig = dataSig(e);
            if (h.sigKnown && sig != h.lastSig) h.changes.add(now);
            h.lastSig = sig;
            h.sigKnown = true;
            h.changes.removeIf(t -> now - t > WINDOW);
            if (!glow && now - h.glowAt > 1500) continue; // off for a while: nothing to show right now

            Kind kind = classify(stats(h, now));
            if (kind == null) continue;
            if (kind == Kind.ECHO && !Cfg.I.shinyEcho) continue;
            if (kind == Kind.RUSH && !Cfg.I.shinyRush) continue;
            int ringCount = 0;
            if (kind == Kind.RUSH) {
                Rings rg = rings(mc, e, all, now, touched);
                ringCount = rg.pieces();
                if (Cfg.I.shinyNeedRings && !rg.ok()) continue; // yellow but no rings = normal block
            }

            Target t = new Target();
            t.id = e.getId();
            t.kind = kind;
            t.x = e.getX();
            t.y = e.getY();
            t.z = e.getZ();
            t.dist = Math.sqrt(dx * dx + dz * dz);
            t.dy = dy;
            t.color = col >= 0 ? col : 0xFFFFFF;
            t.rings = ringCount;
            double ang = Math.toDegrees(Math.atan2(-dx, dz));
            double rel = ang - yaw;
            rel = ((rel % 360) + 540) % 360 - 180;
            t.rel = (float) rel;
            out.add(t);
        }
        for (Iterator<Map.Entry<Integer, Hist>> it = hist.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Hist> en = it.next();
            if (!present.contains(en.getKey()) || now - en.getValue().glowAt > KEEP) it.remove();
        }
        radii.keySet().retainAll(touched);
        out.sort(Comparator.comparingDouble(t -> t.dist));
        targets = out;
    }

    /** For /cpp shiny debug: every glowing entity within 64 blocks. */
    public static List<String> debug(Minecraft mc) {
        List<String> out = new ArrayList<>();
        if (mc.player == null || mc.level == null) return out;
        look();
        long now = System.currentTimeMillis();
        List<Entity> all = snapshot(mc);
        List<Entity> es = new ArrayList<>();
        for (Entity e : all) {
            if (e == mc.player) continue;
            if (!glowing(e) && !hist.containsKey(e.getId())) continue;
            if (e.distanceTo(mc.player) > 64) continue;
            es.add(e);
        }
        es.sort(Comparator.comparingDouble(e -> e.distanceTo(mc.player)));
        for (Entity e : es) {
            if (out.size() >= 14) break;
            Hist h = hist.get(e.getId());
            String info = "no colour history yet";
            String guess = "-";
            if (h != null && !h.samples.isEmpty()) {
                Stats st = stats(h, now);
                Kind k = classify(st);
                guess = k == null ? "normal" : k.name();
                info = String.format(Locale.ROOT, "moved=%d over %.1fs hue=%.0f flashes=%d data-changes=%d%s%s%s", st.spread(), st.span() / 1000.0, st.hue(),
                        st.toggles(), st.changes(), st.rainbow() ? " rainbow" : "", st.yellowish() ? " yellow" : "", st.blueish() ? " blue" : "");
            }
            Rings rg = rings(mc, e, all, now, new HashSet<>());
            out.add(String.format(Locale.ROOT, "%s  %.0fm  glow=#%06X  %s  -> %s  rings=%d/%d(bands %d, yellow %d, unknown %d, moving %d, near player %d) %s%s",
                    typeOf(e).replace("minecraft:", ""), (double) e.distanceTo(mc.player), glowing(e) ? color(e) : 0, info, guess,
                    rg.pieces(), Math.max(1, Cfg.I.shinyRingMin), rg.bands(), rg.yellow(), rg.unknown(), rg.unstable(), rg.nearPlayer(), rg.ok() ? "RINGS-OK" : "no-rings",
                    e.hasCustomName() ? "  name=" + e.getName().getString() : ""));
            if (!candidateType(typeOf(e))) out.add("   (ignored type - not a display / armor stand / falling block / item)");
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
