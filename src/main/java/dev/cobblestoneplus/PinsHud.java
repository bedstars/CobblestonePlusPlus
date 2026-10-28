package dev.cobblestoneplus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/** On-screen crafting pin list. Green = step met, red = still needed. */
final class PinsHud {
    private PinsHud() {}

    private static final int OK = 0xFF55E58A, BAD = 0xFFFF5555, WARN = 0xFFFFC857;

    static int[] draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !Cfg.I.pinsHud || mc.options.hideGui) return null;
        Pins.Live lv = Pins.live;
        if (lv == null || lv.steps.isEmpty()) return null;

        List<Pins.Step> steps = lv.steps;
        int met = 0;
        for (Pins.Step s : steps) if (s.met()) met++;

        String title = "PINNED  " + lv.title;
        String count = met + "/" + steps.size();
        int nameW = 0, valW = 0;
        String[] vals = new String[steps.size()];
        for (int i = 0; i < steps.size(); i++) {
            Pins.Step s = steps.get(i);
            vals[i] = Nums.fmt(Math.min(s.have, Math.max(s.need, s.have))) + "/" + Nums.fmt(s.need);
            nameW = Math.max(nameW, Ui.tw(s.name));
            valW = Math.max(valW, Ui.tw(vals[i]));
        }
        String note = lv.stale ? "from the menu - waiting for the sidebar" : lv.full ? "" : "open the recipe once for all steps";
        int w = Math.max(Ui.tw(title) + Ui.tw(count) + 22, 7 + 9 + nameW + 10 + valW + 7);
        w = Math.max(w, Ui.tw(note) + 14);
        int h = 16 + steps.size() * 11 + 3 + (note.isEmpty() ? 0 : 11);

        int x = 0, y = 0;

        boolean all = met == steps.size();
        int head = all ? OK : Ui.ACCENT;
        Ui.rr(g, x, y, w, h, Ui.PANEL);
        Ui.stripe(g, x, y, h, head);
        Ui.text(g, title, x + 7, y + 4, head);
        Ui.text(g, count, x + w - Ui.tw(count) - 5, y + 4, all ? OK : Ui.DIM);

        int ry = y + 16;
        for (int i = 0; i < steps.size(); i++) {
            Pins.Step s = steps.get(i);
            boolean ok = s.met();
            int col = ok ? OK : BAD;
            g.fill(x + 7, ry + 1, x + 13, ry + 7, col);
            Ui.text(g, s.name, x + 16, ry, ok ? OK : Ui.TEXT);
            Ui.text(g, vals[i], x + w - Ui.tw(vals[i]) - 5, ry, col);
            ry += 11;
        }
        if (!note.isEmpty()) Ui.text(g, note, x + 7, ry + 1, WARN);
        return new int[]{w, h};
    }
}
