package dev.cobblestoneplus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Treasure panel for the world you are in: seal state, active boosts and exactly which relics are still missing
 * (what the vault menu says about each "?") or below max level. The other worlds follow as one line each.
 * Only real worlds are listed (locked / unreleased places are dropped). Data comes from the menus you open
 * (or /cpp treasure refresh, which asks the server for /treasures and reads it).
 *
 *   completion = at least 1 of every relic     max = every relic at max level (3/3)
 */
final class TreasureHud {
    private TreasureHud() {}

    private static final int OK = 0xFF55E58A, SEAL = 0xFFFFC857, BAR_BG = 0xFF2A2230, BAD = 0xFFFF6B6B, WARN = 0xFFFFC857;

    /** One printed line: left text, optional right text, optional bar (frac < 0 = none). */
    private record Row(String left, int lc, String right, int rc, double frac, int barColor) {}

    private static String shortName(String n) {
        return switch (n) {
            case "Sell Value" -> "Sell";
            case "XP Gain" -> "XP";
            case "Special Crop Chance" -> "Crop";
            case "Gem Find" -> "Gem";
            case "Spawner Luck" -> "Spawner";
            case "Treasure Luck" -> "Luck";
            default -> n;
        };
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "~";
    }

    static int[] draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !Cfg.I.treasureHud || mc.options.hideGui) return null;
        Treasure.Summary sm = Treasure.summary;
        List<Treasure.Vault> ws = Treasure.worldVaults();
        if (ws.isEmpty() && !sm.known) return null;

        boolean max = "max".equalsIgnoreCase(Cfg.I.treasureMode);
        int ci = Treasure.currentIndex(ws);
        List<Row> rows = new ArrayList<>();

        // ---- the treasure you just found (announced in chat)
        if (!Treasure.lastFind.isEmpty() && System.currentTimeMillis() - Treasure.lastFindAt < 90_000) {
            rows.add(new Row("NEW  " + Treasure.lastFind, Ui.GAIN, "", 0, -1, 0));
        }

        // ---- the world you are in, but its vault was never read
        int cw = Treasure.currentWorld();
        if (ci < 0 && cw > 0) {
            rows.add(new Row("World " + cw + "  " + Worlds.name(cw), Ui.TEXT, "", 0, -1, 0));
            rows.add(new Row("open The Treasury, then this world's vault,", WARN, "", 0, -1, 0));
            rows.add(new Row("once - it is remembered from then on", WARN, "", 0, -1, 0));
        }

        // ---- the world you are in
        if (ci >= 0) {
            Treasure.Vault v = ws.get(ci);
            int[] lv = max ? v.levels() : null;
            int cur = lv != null ? lv[0] : v.found, goal = lv != null ? lv[1] : v.total;
            boolean done = goal > 0 && cur >= goal;
            rows.add(new Row("World " + Worlds.byName(v.name) + "  " + v.name, done ? OK : Ui.TEXT,
                    cur + "/" + goal + ((max && lv == null) ? "*" : ""), done ? OK : Ui.DIM, goal > 0 ? (double) cur / goal : 0, done ? OK : SEAL));
            String seal = v.forged ? "Seal forged" : (v.left >= 0 ? "Seal: " + v.left + " relic(s) left" : "Seal not forged");
            rows.add(new Row(seal, v.forged ? OK : WARN, "", 0, -1, 0));

            if (!v.bonuses.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, Double> e : v.bonuses.entrySet()) {
                    if (sb.length() > 0) sb.append("  ");
                    sb.append(shortName(e.getKey())).append(" x").append(Nums.fmt(e.getValue()));
                }
                rows.add(new Row(sb.toString(), Ui.GAIN, "", 0, -1, 0));
            }

            int shown = 0, cap = Math.max(1, Cfg.I.treasureRows), hidden = 0;
            if (v.relics.isEmpty()) {
                rows.add(new Row("open this world's vault once to see", WARN, "", 0, -1, 0));
                rows.add(new Row("which relics are missing", WARN, "", 0, -1, 0));
            } else {
                for (Treasure.Relic r : v.relics) {
                    if (r.found) continue;
                    if (shown >= cap) {
                        hidden++;
                        continue;
                    }
                    shown++;
                    String hint = r.hint == null ? "" : r.hint.trim();
                    rows.add(new Row("MISSING  " + (hint.isEmpty() ? "?" : trim(hint, 34)), BAD, "", 0, -1, 0));
                }
                if (max) {
                    for (Treasure.Relic r : v.relics) {
                        if (!r.found || r.level < 0 || r.max <= 0 || r.level >= r.max) continue;
                        if (shown >= cap) {
                            hidden++;
                            continue;
                        }
                        shown++;
                        rows.add(new Row("UPGRADE  " + r.name, WARN, r.level + "/" + r.max, Ui.DIM, (double) r.level / r.max, SEAL));
                    }
                }
                if (hidden > 0) rows.add(new Row("+" + hidden + " more", Ui.DIM, "", 0, -1, 0));
                if (shown == 0 && v.relics.size() > 0) rows.add(new Row(max ? "every relic maxed" : "every relic found", OK, "", 0, -1, 0));
            }
        }

        // ---- the other worlds
        if (Cfg.I.treasureOthers || ci < 0) {
            for (int i = 0; i < ws.size(); i++) {
                if (i == ci) continue;
                Treasure.Vault v = ws.get(i);
                int[] lv = max ? v.levels() : null;
                int cur = lv != null ? lv[0] : v.found, goal = lv != null ? lv[1] : v.total;
                boolean done = goal > 0 && cur >= goal;
                rows.add(new Row("W" + Worlds.byName(v.name) + " " + v.name + (v.forged ? "  \u2713" : ""), done ? OK : Ui.DIM,
                        cur + "/" + goal + ((max && lv == null) ? "*" : ""), done ? OK : Ui.DIM, goal > 0 ? (double) cur / goal : 0, done ? OK : SEAL));
            }
        }
        if (rows.isEmpty()) return null;

        String title = "TREASURES  " + (max ? "MAX" : "COMPLETION");
        String head = sm.known ? sm.relicsFound + "/" + sm.relicsTotal + " relics  seals " + sm.sealsForged + "/" + sm.sealsTotal : "";

        int lw = 0, rw = 0;
        boolean anyBar = false;
        for (Row r : rows) {
            lw = Math.max(lw, Ui.tw(r.left()));
            rw = Math.max(rw, Ui.tw(r.right()));
            if (r.frac() >= 0) anyBar = true;
        }
        int w = 7 + lw + (anyBar ? 8 + 36 : 0) + (rw > 0 ? 8 + rw : 0) + 7;
        w = Math.max(w, Ui.tw(title) + Ui.tw(head) + 24);
        int h = 16 + rows.size() * 12 + 3;

        int x = 0, y = 0;
        Ui.rr(g, x, y, w, h, Ui.PANEL);
        Ui.stripe(g, x, y, h, SEAL);
        Ui.text(g, title, x + 7, y + 4, SEAL);
        Ui.text(g, head, x + w - Ui.tw(head) - 5, y + 4, Ui.TEXT);

        int ry = y + 16;
        for (Row r : rows) {
            Ui.text(g, r.left(), x + 7, ry, r.lc());
            if (r.frac() >= 0) {
                int bx = x + 7 + lw + 8;
                g.fill(bx, ry + 3, bx + 36, ry + 7, BAR_BG);
                g.fill(bx, ry + 3, bx + (int) (36.0 * Math.min(1, r.frac())), ry + 7, Ui.alpha(r.barColor(), 220));
            }
            if (!r.right().isEmpty()) Ui.text(g, r.right(), x + w - Ui.tw(r.right()) - 7, ry, r.rc());
            ry += 12;
        }
        return new int[]{w, h};
    }
}
