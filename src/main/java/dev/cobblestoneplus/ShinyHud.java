package dev.cobblestoneplus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;
import java.util.Locale;

/**
 * Compass strip at the top of the screen: every echo / rush block shows as a marker where it is
 * relative to where you are looking, with its distance. Markers stuck at an edge mean "turn that way".
 * Below it, a short list of the nearest of each kind with a direction hint.
 */
final class ShinyHud {
    private ShinyHud() {}

    private static final int RUSH = 0xFFFFC857;

    static int[] draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !Cfg.I.shinyHud || mc.options.hideGui) return null;
        List<Shiny.Target> ts = Shiny.targets;
        if (ts.isEmpty()) return null;

        int W = 200, x0 = 0, y0 = 0;
        Ui.rr(g, x0, y0, W, 24, Ui.PANEL);
        g.fill(x0 + W / 2, y0, x0 + W / 2 + 1, y0 + 24, 0x55FFFFFF); // looking direction

        int shown = 0;
        for (Shiny.Target t : ts) {
            if (shown++ >= 14) break;
            float rel = t.rel;
            boolean edge = Math.abs(rel) > 90f;
            float c = Math.max(-90f, Math.min(90f, rel));
            int mx = x0 + W / 2 + (int) (c / 90f * (W / 2 - 6));
            int col = t.kind == Shiny.Kind.ECHO ? (0xFF000000 | Shiny.rainbow(0.25)) : RUSH;
            g.fill(mx - 3, y0 + 4, mx + 3, y0 + 10, col);
            if (edge) Ui.text(g, rel > 0 ? ">" : "<", rel > 0 ? mx - 10 : mx + 5, y0 + 3, col);
            String d = String.format(Locale.ROOT, "%.0f", t.dist);
            Ui.text(g, d, mx - Ui.tw(d) / 2, y0 + 13, Ui.TEXT);
        }

        // nearest of each kind
        int ly = y0 + 28;
        for (Shiny.Kind k : Shiny.Kind.values()) {
            Shiny.Target near = null;
            int n = 0;
            for (Shiny.Target t : ts) {
                if (t.kind != k) continue;
                n++;
                if (near == null) near = t;
            }
            if (near == null) continue;
            String arrow;
            float r = near.rel;
            if (Math.abs(r) < 12) arrow = "ahead";
            else if (r > 0) arrow = Math.abs(r) > 135 ? "behind (right)" : "right";
            else arrow = Math.abs(r) > 135 ? "behind (left)" : "left";
            String vert = Math.abs(near.dy) < 3 ? "" : (near.dy > 0 ? "  up " : "  down ") + String.format(Locale.ROOT, "%.0f", Math.abs(near.dy));
            String line = (k == Shiny.Kind.ECHO ? "ECHO x" : "RUSH x") + n + "  nearest "
                    + String.format(Locale.ROOT, "%.0f", near.dist) + "m  " + arrow + vert;
            int w = Ui.tw(line) + 14;
            int col = k == Shiny.Kind.ECHO ? (0xFF000000 | Shiny.rainbow(0.25)) : RUSH;
            Ui.rr(g, (W - w) / 2, ly, w, 13, Ui.PANEL);
            g.fill((W - w) / 2, ly, (W - w) / 2 + 2, ly + 13, col);
            Ui.text(g, line, (W - w) / 2 + 7, ly + 3, Ui.TEXT);
            ly += 15;
        }
        return new int[]{W, ly};
    }
}
