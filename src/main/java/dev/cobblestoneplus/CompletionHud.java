package dev.cobblestoneplus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Always-on completion marker. Global = the overall percentage plus the least finished categories ("next up").
 * With a part chosen in the planner it tracks that part: progress, what is left, where to do it.
 */
final class CompletionHud {
    private CompletionHud() {}

    private static final int BAR = 90;

    static int pctColor(double pct) {
        return Ui.lerp(0xFFFF6B6B, 0xFF55E58A, (float) (pct / 100.0));
    }

    static int[] draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !Cfg.I.completionHud || mc.options.hideGui) return null;
        Completion.Data d = Completion.data;
        if (!Completion.known()) {
            String a = "COMPLETION", b = "open /stats once";
            int w = Math.max(Ui.tw(a), Ui.tw(b)) + 16;
            Ui.rr(g, 0, 0, w, 28, Ui.PANEL);
            Ui.stripe(g, 0, 0, 28, Ui.GOLD);
            Ui.text(g, a, 7, 4, Ui.GOLD);
            Ui.text(g, b, 7, 15, Ui.DIM);
            return new int[]{w, 28};
        }

        Completion.Cat focus = Cfg.I.focus.isEmpty() ? null : Completion.find(Cfg.I.focus);
        List<String[]> lines = new ArrayList<>(); // {left, right}
        List<Integer> cols = new ArrayList<>();
        String sub;
        if (focus != null) {
            sub = focus.name;
            double left = Math.max(0, focus.max - focus.cur);
            lines.add(new String[]{Nums.fmt(focus.cur) + "/" + Nums.fmt(focus.max), String.format(Locale.ROOT, "%.1f%%", focus.pct())});
            cols.add(focus.done() ? 0xFF55E58A : Ui.TEXT);
            lines.add(new String[]{focus.done() ? "finished" : Nums.fmt(left) + " left", ""});
            cols.add(focus.done() ? 0xFF55E58A : Ui.GOLD);
            String hint = Completion.hint(focus.name);
            for (String h : wrap(hint, 34)) {
                lines.add(new String[]{h, ""});
                cols.add(Ui.DIM);
            }
            int cw = Worlds.current();
            String n = focus.name.toLowerCase(Locale.ROOT);
            if (cw > 0 && (n.contains("milestone") || n.contains("idol"))) {
                int[] sm = Milestones.summary(cw);
                lines.add(new String[]{Worlds.name(cw) + ": " + (sm[0] + sm[1]) + " milestone rows left", ""});
                cols.add(Ui.TEXT);
            }
        } else {
            sub = "global";
            List<Completion.Cat> open = new ArrayList<>();
            for (Completion.Cat c : d.cats) if (!c.done()) open.add(c);
            open.sort(Comparator.comparingDouble(Completion.Cat::pct).reversed()); // closest to done first = quickest wins
            lines.add(new String[]{"CLOSEST TO DONE", ""});
            cols.add(Ui.DIM);
            for (int i = 0; i < Math.min(3, open.size()); i++) {
                Completion.Cat c = open.get(i);
                lines.add(new String[]{c.name, Nums.fmt(c.cur) + "/" + Nums.fmt(c.max)});
                cols.add(Ui.TEXT);
            }
        }

        String pct = d.percent >= 0 ? String.format(Locale.ROOT, "%.1f%%", d.percent) : "?";
        String title = "COMPLETION";
        int w = Ui.tw(title) + Ui.tw(pct) + Ui.tw(sub) + 34;
        for (String[] l : lines) w = Math.max(w, 7 + Ui.tw(l[0]) + (l[1].isEmpty() ? 0 : 14 + Ui.tw(l[1])) + 7);
        w = Math.max(w, BAR + 14);
        int h = 16 + 8 + lines.size() * 11 + 4;

        double frac = d.percent >= 0 ? d.percent / 100.0 : 0;
        Ui.rr(g, 0, 0, w, h, Ui.PANEL);
        Ui.stripe(g, 0, 0, h, pctColor(frac * 100));
        Ui.text(g, title, 7, 4, Ui.GOLD);
        Ui.text(g, pct, 7 + Ui.tw(title) + 6, 4, pctColor(frac * 100));
        Ui.text(g, sub, w - Ui.tw(sub) - 5, 4, Ui.DIM);
        g.fill(7, 16, w - 7, 20, 0xFF2A2230);
        g.fill(7, 16, 7 + (int) ((w - 14) * Math.min(1, frac)), 20, pctColor(frac * 100));

        int ry = 24;
        for (int i = 0; i < lines.size(); i++) {
            Ui.text(g, lines.get(i)[0], 7, ry, cols.get(i));
            if (!lines.get(i)[1].isEmpty()) Ui.text(g, lines.get(i)[1], w - Ui.tw(lines.get(i)[1]) - 7, ry, Ui.DIM);
            ry += 11;
        }
        return new int[]{w, h};
    }

    static List<String> wrap(String s, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : s.split(" ")) {
            if (cur.length() + w.length() + 1 > max && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(w);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
