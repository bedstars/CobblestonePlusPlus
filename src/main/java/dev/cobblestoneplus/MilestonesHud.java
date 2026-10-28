package dev.cobblestoneplus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Milestones for the world you are in: per crop / mob the finished stages (x/5), progress to the next stage,
 * idols (x/10, boss drops) and treasure level (x/3). Finished entries are hidden unless turned on, so what is
 * left on screen is what is left to do.
 */
final class MilestonesHud {
    private MilestonesHud() {}

    private static final int OK = 0xFF55E58A, BAD = 0xFFFF6B6B, MS = 0xFF5CC8FF, BAR_BG = 0xFF2A2230;
    private static final int BAR = 30;

    private record Line(String name, String stage, String prog, double frac, String idol, boolean idolOk,
                        String tre, boolean treOk, boolean done) {}

    private static List<Line> lines(boolean mob, int world, int[] left) {
        List<Line> open = new ArrayList<>(), fin = new ArrayList<>();
        for (Milestones.Track t : Milestones.forWorld(mob, world)) {
            boolean complete = t.complete();
            Milestones.Stage n = t.next();
            String prog = "";
            double frac = 0;
            if (n != null) {
                double cur = t.cur();
                if (cur < 0) cur = 0;
                frac = n.target > 0 ? Math.min(1, cur / n.target) : n.pct / 100.0;
                prog = Nums.fmt(Math.min(cur, n.target)) + "/" + Nums.fmt(n.target);
            } else if (t.allStagesKnown()) {
                frac = 1;
                prog = "max";
            }
            String stage = t.stagesDone() + "/" + Milestones.STAGES;
            String idol = t.idols >= 0 ? "I " + t.idols + "/" + t.idolsMax : "";
            String tre = t.treasure >= 0 ? "T " + t.treasure + "/" + t.treasureMax : "";
            Line l = new Line(t.name, stage, prog, frac, idol, !t.idolsLeft(), tre, !t.treasureLeft(), complete);
            if (complete) fin.add(l);
            else {
                open.add(l);
                left[0]++;
            }
        }
        int max = Math.max(1, Cfg.I.milestoneRows);
        if (open.size() > max) open = new ArrayList<>(open.subList(0, max));
        List<Line> out = new ArrayList<>(open);
        if (Cfg.I.milestoneDone) out.addAll(fin);
        return out;
    }

    static int[] draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !Cfg.I.milestonesHud || mc.options.hideGui) return null;
        int world = Milestones.currentWorld();
        if (world == 0) world = Milestones.lowestWorld();
        if (world == 0) return null;

        int[] left = {0};
        List<Line> crops = lines(false, world, left);
        List<Line> mobs = Cfg.I.milestoneMobs ? lines(true, world, left) : List.of();
        boolean any = !Milestones.forWorld(false, world).isEmpty() || (Cfg.I.milestoneMobs && !Milestones.forWorld(true, world).isEmpty());
        if (!any) return null;

        String title = "MILESTONES  WORLD " + world;
        String head = left[0] == 0 ? "all done" : left[0] + " left";

        int nW = 0, sW = 0, pW = 0, iW = 0, tW = 0;
        for (List<Line> ls : List.of(crops, mobs)) {
            for (Line l : ls) {
                nW = Math.max(nW, Ui.tw(l.name()));
                sW = Math.max(sW, Ui.tw(l.stage()));
                pW = Math.max(pW, Ui.tw(l.prog()));
                iW = Math.max(iW, Ui.tw(l.idol()));
                tW = Math.max(tW, Ui.tw(l.tre()));
            }
        }
        int w = 7 + nW + 8 + sW + 8 + BAR + 4 + pW + (iW > 0 ? 8 + iW : 0) + (tW > 0 ? 6 + tW : 0) + 7;
        w = Math.max(w, Ui.tw(title) + Ui.tw(head) + 22);
        int rows = crops.size() + mobs.size();
        int sections = (crops.isEmpty() ? 0 : 1) + (mobs.isEmpty() ? 0 : 1);
        int h = 16 + rows * 12 + sections * 11 + 3;
        if (rows == 0) h = 16 + 3;

        int x = 0, y = 0;
        Ui.rr(g, x, y, w, h, Ui.PANEL);
        g.fill(x, y, x + 2, y + h, left[0] == 0 ? OK : MS);
        Ui.text(g, title, x + 7, y + 4, left[0] == 0 ? OK : MS);
        Ui.text(g, head, x + w - Ui.tw(head) - 5, y + 4, left[0] == 0 ? OK : Ui.TEXT);

        int ry = y + 16;
        for (int sec = 0; sec < 2; sec++) {
            List<Line> ls = sec == 0 ? crops : mobs;
            if (ls.isEmpty()) continue;
            Ui.text(g, sec == 0 ? "CROPS" : "MOBS", x + 7, ry, Ui.DIM);
            ry += 11;
            for (Line l : ls) {
                int cx = x + 7;
                Ui.text(g, l.name(), cx, ry, l.done() ? OK : Ui.TEXT);
                cx += nW + 8;
                Ui.text(g, l.stage(), cx, ry, l.done() ? OK : Ui.DIM);
                cx += sW + 8;
                g.fill(cx, ry + 3, cx + BAR, ry + 7, BAR_BG);
                if (l.frac() > 0) g.fill(cx, ry + 3, cx + (int) (BAR * Math.min(1, l.frac())), ry + 7, l.done() ? OK : Ui.alpha(MS, 230));
                cx += BAR + 4;
                Ui.text(g, l.prog(), cx, ry, Ui.DIM);
                cx += pW;
                if (iW > 0) {
                    cx += 8;
                    if (!l.idol().isEmpty()) Ui.text(g, l.idol(), cx, ry, l.idolOk() ? OK : BAD);
                    cx += iW;
                }
                if (tW > 0) {
                    cx += 6;
                    if (!l.tre().isEmpty()) Ui.text(g, l.tre(), cx, ry, l.treOk() ? OK : BAD);
                }
                ry += 12;
            }
        }
        return new int[]{w, h};
    }
}
