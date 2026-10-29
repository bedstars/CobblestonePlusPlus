package dev.cobblestoneplus;

import java.util.Locale;

/** Parses and formats the 1.2k / 3.4M / 5.6B / 7.8T numbers the server uses. */
final class Nums {
    private Nums() {}

    static final String NUM = "\\d[\\d.,]*[kKmMbBtT]?";

    static double parse(String s) {
        if (s == null) return 0;
        s = s.trim().replace(",", "");
        if (s.isEmpty()) return 0;
        double mul = 1;
        char c = Character.toLowerCase(s.charAt(s.length() - 1));
        switch (c) {
            case 'k' -> mul = 1e3;
            case 'm' -> mul = 1e6;
            case 'b' -> mul = 1e9;
            case 't' -> mul = 1e12;
            default -> {
            }
        }
        if (mul != 1) s = s.substring(0, s.length() - 1);
        try {
            return Double.parseDouble(s) * mul;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 2,020 style (full digits with separators) up to a billion, abbreviated above. */
    private static final String SC_FROM = "\u1d00\u0299\u1d04\u1d05\u1d07\ua730\u0262\u029c\u026a\u1d0a\u1d0b\u029f\u1d0d\u0274\u1d0f\u1d18\u01eb\u0280\ua731\u1d1b\u1d1c\u1d20\u1d21\u028f\u1d22";
    private static final String SC_TO = "ABCDEFGHIJKLMNOPQRSTUVWYZ";

    /** Small-caps letters (a font trick some servers use) become plain capitals, so "PINNED" is found either way. */
    static String fold(String s) {
        if (s == null) return "";
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int k = ch < 0x100 ? -1 : SC_FROM.indexOf(ch);
            if (k >= 0 && sb == null) {
                sb = new StringBuilder(s.length());
                sb.append(s, 0, i);
            }
            if (sb != null) sb.append(k >= 0 ? SC_TO.charAt(k) : ch);
        }
        return sb == null ? s : sb.toString();
    }

    static String exact(double v) {
        if (Math.abs(v) >= 1e9) return fmt(v);
        return String.format(Locale.ROOT, "%,.0f", v);
    }

    static String fmt(double v) {
        double a = Math.abs(v);
        if (a >= 1e15) return String.format(Locale.ROOT, "%.2fQa", v / 1e15);
        if (a >= 1e12) return String.format(Locale.ROOT, "%.2fT", v / 1e12);
        if (a >= 1e9) return String.format(Locale.ROOT, "%.2fB", v / 1e9);
        if (a >= 1e6) return String.format(Locale.ROOT, "%.2fM", v / 1e6);
        if (a >= 1e3) return String.format(Locale.ROOT, "%.2fk", v / 1e3);
        if (a >= 100) return String.format(Locale.ROOT, "%.0f", v);
        if (a == Math.rint(a)) return String.format(Locale.ROOT, "%.0f", v);
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
