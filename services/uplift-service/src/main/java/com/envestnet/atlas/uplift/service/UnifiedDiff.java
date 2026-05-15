package com.envestnet.atlas.uplift.service;

/**
 * Demo unified-diff generator. Recipe transforms are line-local (they don't
 * insert or remove lines, only edit content), so we can do a simple per-line
 * diff: print equal lines as context, differing pairs as -orig +new.
 */
public final class UnifiedDiff {

    public static String generate(String filePath, String original, String revised) {
        if (original.equals(revised)) return "";

        String[] o = original.split("\\r?\\n", -1);
        String[] r = revised.split("\\r?\\n", -1);
        int n = Math.max(o.length, r.length);

        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(filePath).append('\n');
        sb.append("+++ b/").append(filePath).append('\n');
        sb.append("@@ -1,").append(o.length).append(" +1,").append(r.length).append(" @@\n");

        for (int i = 0; i < n; i++) {
            String oi = i < o.length ? o[i] : null;
            String ri = i < r.length ? r[i] : null;
            if (oi != null && ri != null && oi.equals(ri)) {
                sb.append(' ').append(oi).append('\n');
            } else {
                if (oi != null) sb.append('-').append(oi).append('\n');
                if (ri != null) sb.append('+').append(ri).append('\n');
            }
        }
        return sb.toString();
    }

    private UnifiedDiff() {}
}
