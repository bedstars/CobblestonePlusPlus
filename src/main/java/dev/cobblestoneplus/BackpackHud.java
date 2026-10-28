package dev.cobblestoneplus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** On-screen backpack panel: item, gained, total, sell value. */
final class BackpackHud {
    private BackpackHud() {}

    static int[] draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !Cfg.I.backpackHud) return null;
        if (mc.options.hideGui) return null;

        long now = System.currentTimeMillis();
        boolean all = "all".equalsIgnoreCase(Cfg.I.backpackMode);
        List<Backpack.Entry> rows = new ArrayList<>();
        for (Backpack.Entry e : Backpack.items.values()) {
            if (all ? e.amount > 0 : (e.last > 0 && now - e.last < Cfg.I.backpackSecs * 1000L)) rows.add(e);
        }
        if (rows.isEmpty()) return null;
        if (all) rows.sort(Comparator.comparingDouble((Backpack.Entry e) -> e.value()).reversed());
        else rows.sort(Comparator.comparingLong((Backpack.Entry e) -> e.last).reversed());
        if (rows.size() > Cfg.I.backpackMax) rows = rows.subList(0, Cfg.I.backpackMax);

        double hours = Math.max(1.0 / 3600, (now - Backpack.sessionStart) / 3_600_000.0);

        // build lines first so the panel can be sized
        List<String[]> lines = new ArrayList<>();
        int[] cols = new int[rows.size()];
        int w = Ui.tw("BACKPACK") + 30;
        for (int i = 0; i < rows.size(); i++) {
            Backpack.Entry e = rows.get(i);
            String gain = e.session > 0 ? "+" + Nums.fmt(e.session) : "";
            if (!all && e.last > 0) gain = "+" + Nums.fmt(e.lastGain);
            String total = Nums.fmt(e.amount);
            String val = (Cfg.I.backpackValue && e.unit > 0) ? "~" + Nums.fmt(e.value()) : "";
            lines.add(new String[]{e.name, gain, total, val});
            cols[i] = "gems".equals(e.cur) ? Ui.GEM : Ui.GOLD;
            int lw = Ui.tw(e.name) + 10 + Ui.tw(gain) + 10 + Ui.tw(total) + (val.isEmpty() ? 0 : 10 + Ui.tw(val)) + 14;
            w = Math.max(w, lw);
        }

        int x = 0, y = 0;
        int h = 16 + rows.size() * 11 + 4;
        Ui.rr(g, x, y, w, h, Ui.PANEL);
        Ui.stripe(g, x, y, h, Ui.ACCENT);
        Ui.text(g, "BACKPACK", x + 7, y + 4, Ui.ACCENT);
        String sub = all ? "all" : String.format(Locale.ROOT, "session %.0fm", (now - Backpack.sessionStart) / 60000.0);
        Ui.text(g, sub, x + w - Ui.tw(sub) - 5, y + 4, Ui.DIM);

        int ry = y + 16;
        for (int i = 0; i < lines.size(); i++) {
            String[] l = lines.get(i);
            Backpack.Entry e = rows.get(i);
            boolean fresh = now - e.last < 1500;
            int cx = x + 7;
            Ui.text(g, l[0], cx, ry, fresh ? 0xFFFFFFFF : Ui.TEXT);
            cx += Ui.tw(l[0]) + 10;
            if (!l[1].isEmpty()) {
                Ui.text(g, l[1], cx, ry, Ui.GAIN);
                cx += Ui.tw(l[1]) + 10;
            }
            Ui.text(g, l[2], cx, ry, Ui.TEXT);
            cx += Ui.tw(l[2]) + 10;
            if (!l[3].isEmpty()) Ui.text(g, l[3], cx, ry, cols[i]);
            ry += 11;
        }
        return new int[]{w, h};
    }
}
