package dev.cobblestoneplus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Treasure panel with two goals:
 *   completion = at least 1 of every relic (vault found/total)
 *   max        = every relic at max level (3/3); falls back to found/total when levels were not readable
 */
final class TreasureHud {
    private TreasureHud() {}

    private static final int OK = 0xFF55E58A, SEAL = 0xFFFFC857, BAR_BG = 0xFF2A2230;

    private record Row(String name, int cur, int goal, boolean forged, boolean levelsKnown, boolean locked) {
        boolean done() {
            return goal > 0 && cur >= goal;
        }
    }

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

    static int[] draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !Cfg.I.treasureHud || mc.options.hideGui) return null;
        Treasure.Summary sm = Treasure.summary;
        if (Treasure.vaults.isEmpty() && !sm.known) return null;

        boolean max = "max".equalsIgnoreCase(Cfg.I.treasureMode);
        List<Row> rows = new ArrayList<>();
        for (Treasure.Vault v : Treasure.vaults.values()) {
            int cur = v.found, goal = v.total;
            boolean known = false;
            if (max && !v.locked) {
                int[] lv = v.levels();
                if (lv != null) {
                    cur = lv[0];
                    goal = lv[1];
                    known = true;
                }
            }
            rows.add(new Row(v.name, cur, goal, v.forged, known, v.locked));
        }
        rows.sort(Comparator.comparing((Row r) -> r.locked()).thenComparing(r -> r.done()).thenComparing(Row::name));

        String title = "TREASURES  " + (max ? "MAX" : "COMPLETION");
        String head = sm.known ? sm.relicsFound + "/" + sm.relicsTotal + " relics  seals " + sm.sealsForged + "/" + sm.sealsTotal : "";
        StringBuilder sb = new StringBuilder();
        if (sm.known && !sm.boosts.isEmpty()) {
            if (!sm.dim.isEmpty()) sb.append(sm.dim).append(": ");
            boolean first = true;
            for (Map.Entry<String, Double> e : sm.boosts.entrySet()) {
                if (!first) sb.append("  ");
                first = false;
                sb.append(shortName(e.getKey())).append(" x").append(Nums.fmt(e.getValue()));
            }
        } else if (sm.known && sm.fortune > 0) {
            sb.append(sm.dim.isEmpty() ? "" : sm.dim + ": ").append("Fortune x").append(Nums.fmt(sm.fortune));
        }
        String boost = sb.toString();

        int nameW = 0, valW = Ui.tw("00/000");
        String[] vals = new String[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            vals[i] = r.locked() ? "locked" : r.cur() + "/" + r.goal() + ((max && !r.levelsKnown()) ? "*" : "");
            nameW = Math.max(nameW, Ui.tw(r.name()));
            valW = Math.max(valW, Ui.tw(vals[i]));
        }
        int w = Math.max(Ui.tw(title) + Ui.tw(head) + 24, 7 + nameW + 8 + 36 + 8 + valW + 14);
        w = Math.max(w, Ui.tw(boost) + 14);
        int h = 16 + (boost.isEmpty() ? 0 : 11) + rows.size() * 12 + 3;

        int x = 0, y = 0;

        Ui.rr(g, x, y, w, h, Ui.PANEL);
        g.fill(x, y, x + 2, y + h, SEAL);
        Ui.text(g, title, x + 7, y + 4, SEAL);
        Ui.text(g, head, x + w - Ui.tw(head) - 5, y + 4, Ui.TEXT);

        int ry = y + 16;
        if (!boost.isEmpty()) {
            Ui.text(g, boost, x + 7, ry, Ui.GAIN);
            ry += 11;
        }
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            boolean d = r.done();
            Ui.text(g, r.name(), x + 7, ry, r.locked() ? Ui.DIM : (d ? OK : Ui.TEXT));
            int bx = x + 7 + nameW + 8;
            g.fill(bx, ry + 3, bx + 36, ry + 7, BAR_BG);
            if (r.goal() > 0 && !r.locked()) g.fill(bx, ry + 3, bx + (int) (36.0 * Math.min(r.cur(), r.goal()) / r.goal()), ry + 7, d ? OK : Ui.alpha(SEAL, 220));
            Ui.text(g, vals[i], x + w - Ui.tw(vals[i]) - 12, ry, r.locked() ? Ui.DIM : (d ? OK : Ui.DIM));
            g.fill(x + w - 8, ry + 2, x + w - 4, ry + 6, r.forged() ? SEAL : 0xFF4B4054);
            ry += 12;
        }
        return new int[]{w, h};
    }
}
