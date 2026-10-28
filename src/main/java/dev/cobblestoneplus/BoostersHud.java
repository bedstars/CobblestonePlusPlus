package dev.cobblestoneplus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** On-screen multiplier breakdown with a share bar per booster. */
final class BoostersHud {
    private BoostersHud() {}

    private static final int XPC = 0xFF55E6E6;

    static int[] draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !Cfg.I.boostersHud || mc.options.hideGui) return null;
        Boosters.Data d = Boosters.data;
        if (d.rows.isEmpty()) return null;

        List<Boosters.Row> rows = new ArrayList<>(d.rows);
        rows.sort(Comparator.comparingDouble((Boosters.Row r) -> r.mult).reversed());

        String title = "MULTIPLIER" + (d.crop.isEmpty() ? "" : "  " + d.crop);
        String total = "x" + Nums.fmt(d.total);
        int nameW = 0, multW = 0, xpW = 0;
        for (Boosters.Row r : rows) {
            nameW = Math.max(nameW, Ui.tw(r.name));
            multW = Math.max(multW, Ui.tw("x" + trim(r.mult)));
            if (r.xp > 0) xpW = Math.max(xpW, Ui.tw("x" + trim(r.xp) + " xp"));
        }
        int w = Math.max(Ui.tw(title) + Ui.tw(total) + 22, 7 + nameW + 8 + multW + (xpW > 0 ? 8 + xpW : 0) + 7);
        int h = 16 + rows.size() * 12 + 3;
        int x = 0, y = 0;

        Ui.rr(g, x, y, w, h, Ui.PANEL);
        g.fill(x, y, x + 2, y + h, Ui.GEM);
        Ui.text(g, title, x + 7, y + 4, Ui.GEM);
        Ui.text(g, total, x + w - Ui.tw(total) - 5, y + 4, Ui.GOLD);

        double lt = Math.log(Math.max(1.0001, d.total));
        int ry = y + 16;
        for (Boosters.Row r : rows) {
            double share = lt <= 0 ? 0 : Math.max(0, Math.min(1, Math.log(Math.max(1, r.mult)) / lt));
            // share bar under the row
            g.fill(x + 7, ry + 9, x + w - 5, ry + 10, 0xFF2A2230);
            g.fill(x + 7, ry + 9, x + 7 + (int) ((w - 12) * share), ry + 10, Ui.alpha(Ui.GEM, 200));
            Ui.text(g, r.name, x + 7, ry, Ui.TEXT);
            int cx = x + 7 + nameW + 8;
            Ui.text(g, "x" + trim(r.mult), cx, ry, Ui.GOLD);
            if (r.xp > 0) Ui.text(g, "x" + trim(r.xp) + " xp", cx + multW + 8, ry, XPC);
            ry += 12;
        }
        return new int[]{w, h};
    }

    private static String trim(double v) {
        String s = Nums.fmt(v);
        return s;
    }
}
