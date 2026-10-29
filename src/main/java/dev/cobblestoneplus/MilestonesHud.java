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

    private record Line(String name, String stage, String prog, String pct, double frac, String idol, boolean idolOk,
                        String tre, boolean treOk, boolean done, boolean hot, boolean active) {}

    private static List<Line> lines(boolean mob, int world, int[] left) {
        List<Line> open = new ArrayList<>(), fin = new ArrayList<>();
        for (Milestones.Track t : Milestones.forWorld(mob, world)) {
            boolean complete = t.complete();
            Milestones.Stage n = t.next();
            String prog = "", pct = "";
            double frac = 0;
            if (n != null) {
                double cur = t.cur();
                if (cur < 0) cur = 0;
                frac = n.target > 0 ? Math.min(1, cur / n.target) : n.pct / 100.0;
                prog = (t.est ? "~" : "") + Nums.exact(Math.min(cur, n.target)) + "/" + Nums.exact(n.target);
                pct = String.format(java.util.Locale.ROOT, "%.1f%%", Math.floor(frac * 1000) / 10.0);
            } else if (t.allStagesKnown()) {
                frac = 1;
                prog = "max";
                pct = "100%";
            }
            String stage = t.stagesDone() + "/" + Milestones.STAGES;
            String idol = t.idols >= 0 ? "I " + t.idols + "/" + t.idolsMax : "";
            String tre = t.treasure >= 0 ? "T " + t.treasure + "/" + t.treasureMax : "";
            boolean hot = System.currentTimeMillis() - t.bumpAt < 1500;
            Line l = new Line(t.name, stage, prog, pct, frac, idol, !t.idolsLeft(), tre, !t.treasureLeft(), complete, hot, t == Milestones.lastBumped);
            if (complete) fin.add(l);
            else {
                open.add(l);
                left[0]++;
            }
        }
        for (int i = 1; i < open.size(); i++) { // the one you are working on goes first
            if (open.get(i).active()) {
                open.add(0, open.remove(i));
                break;
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

        String title = "MILESTONES  WORLD " + world + "  " + Worlds.name(world);

        // one summary line per other world with learned data
        List<String[]> others = new ArrayList<>();
        List<Boolean> othersDone = new ArrayList<>();
        if (Cfg.I.milestoneOthers) {
            for (int ow = 1; ow <= Worlds.count(); ow++) {
                if (ow == world) continue;
                int[] sm = Milestones.summary(ow);
                if (sm[2] + sm[3] == 0) continue;
                int lf = sm[0] + sm[1];
                others.add(new String[]{"W" + ow + " " + Worlds.name(ow), lf == 0 ? "done" : lf + " left"});
                othersDone.add(lf == 0);
            }
        }
        String head = left[0] == 0 ? "all done" : left[0] + " left";

        int nW = 0, sW = 0, pW = 0, cW = 0, iW = 0, tW = 0;
        for (List<Line> ls : List.of(crops, mobs)) {
            for (Line l : ls) {
                nW = Math.max(nW, Ui.tw(l.name()));
                sW = Math.max(sW, Ui.tw(l.stage()));
                pW = Math.max(pW, Ui.tw(l.prog()));
                cW = Math.max(cW, Ui.tw(l.pct()));
                iW = Math.max(iW, Ui.tw(l.idol()));
                tW = Math.max(tW, Ui.tw(l.tre()));
            }
        }
        int w = 7 + nW + 8 + sW + 8 + BAR + 4 + pW + (cW > 0 ? 6 + cW : 0) + (iW > 0 ? 8 + iW : 0) + (tW > 0 ? 6 + tW : 0) + 7;
        w = Math.max(w, Ui.tw(title) + Ui.tw(head) + 22);
        for (String[] o : others) w = Math.max(w, 7 + Ui.tw(o[0]) + 14 + Ui.tw(o[1]) + 7);
        String status = Cfg.I.milestoneStatus ? Milestones.status() : "";
        if (!status.isEmpty()) w = Math.max(w, 14 + Ui.tw(status));
        int rows = crops.size() + mobs.size();
        int sections = (crops.isEmpty() ? 0 : 1) + (mobs.isEmpty() ? 0 : 1);
        int h = 16 + rows * 12 + sections * 11 + 3;
        if (rows == 0) h = 16 + 3;
        if (!others.isEmpty()) h += 11 + others.size() * 11;
        if (!status.isEmpty()) h += 12;

        int x = 0, y = 0;
        Ui.rr(g, x, y, w, h, Ui.PANEL);
        Ui.stripe(g, x, y, h, left[0] == 0 ? OK : MS);
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
                Ui.text(g, l.name(), cx, ry, l.done() ? OK : l.active() ? Ui.GOLD : Ui.TEXT);
                cx += nW + 8;
                Ui.text(g, l.stage(), cx, ry, l.done() ? OK : Ui.DIM);
                cx += sW + 8;
                g.fill(cx, ry + 3, cx + BAR, ry + 7, BAR_BG);
                if (l.frac() > 0) g.fill(cx, ry + 3, cx + (int) (BAR * Math.min(1, l.frac())), ry + 7, l.done() ? OK : Ui.alpha(MS, 230));
                cx += BAR + 4;
                Ui.text(g, l.prog(), cx, ry, l.hot() ? Ui.GAIN : l.done() ? OK : Ui.TEXT);
                cx += pW;
                if (cW > 0) {
                    cx += 6;
                    Ui.text(g, l.pct(), cx, ry, l.hot() ? Ui.GAIN : Ui.DIM);
                    cx += cW;
                }
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
        if (!others.isEmpty()) {
            Ui.text(g, "OTHER WORLDS", x + 7, ry, Ui.DIM);
            ry += 11;
            for (int i = 0; i < others.size(); i++) {
                int col = othersDone.get(i) ? OK : Ui.TEXT;
                Ui.text(g, others.get(i)[0], x + 7, ry, col);
                Ui.text(g, others.get(i)[1], x + w - Ui.tw(others.get(i)[1]) - 7, ry, othersDone.get(i) ? OK : Ui.DIM);
                ry += 11;
            }
        }
        if (!status.isEmpty()) Ui.text(g, status, x + 7, ry + 1, Ui.DIM);
        return new int[]{w, h};
    }
}
