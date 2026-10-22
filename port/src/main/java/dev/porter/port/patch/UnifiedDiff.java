package dev.porter.port.patch;

import java.util.ArrayList;
import java.util.List;

/**
 * Line diffs in the unified format ({@code diff -u} / {@code git diff}), written and applied without external tools.
 *
 * <p>Text is a list of lines split on {@code \n} ({@code \r} stays part of a line, so CRLF files round-trip). A
 * missing newline at the end of a file is written with the standard {@code \ No newline at end of file} marker, so
 * applying a patch reproduces the target text exactly. Applying is strict: every context and removed line must match
 * at the hunk's position; there is no fuzz, so a patch either reproduces the file it was made for or fails loudly.
 */
public final class UnifiedDiff {
    /** Marks (internally) the last line of a text that has no final newline. */
    private static final String NOEOL = "\u0000noeol";
    private static final String MARKER = "\\ No newline at end of file";

    private UnifiedDiff() {
    }

    /** One line of a hunk: ' ' context, '-' removed, '+' added. */
    public record Line(char op, String text) {
    }

    /** A hunk: 1-based start lines and lengths in the old and new file. */
    public record Hunk(int oldStart, int oldLen, int newStart, int newLen, List<Line> lines) {
    }

    /**
     * The patch of one file. {@code oldPath}/{@code newPath} are the header paths without the {@code a/}/{@code b/}
     * prefixes ({@code null} for {@code /dev/null}: a created or deleted file).
     */
    public record FilePatch(String oldPath, String newPath, List<Hunk> hunks, String baseSha256) {
        public FilePatch(String oldPath, String newPath, List<Hunk> hunks) {
            this(oldPath, newPath, hunks, null);
        }

        /** A blind patch: its hunks hold only the added lines; the base is identified by its sha256 instead. */
        public boolean blind() {
            return baseSha256 != null;
        }

        public boolean created() {
            return oldPath == null;
        }

        public boolean deleted() {
            return newPath == null;
        }
    }

    /** Lines of a text; the last line carries {@link #NOEOL} when the text does not end with a newline. */
    static List<String> lines(String s) {
        List<String> out = new ArrayList<>();
        int a = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                out.add(s.substring(a, i));
                a = i + 1;
            }
        }
        if (a < s.length()) {
            out.add(s.substring(a) + NOEOL);
        }
        return out;
    }

    static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            if (l.endsWith(NOEOL)) {
                sb.append(l, 0, l.length() - NOEOL.length());
            } else {
                sb.append(l).append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------ diff

    /** Edit script of a -> b (Myers' O(ND) algorithm after stripping the common prefix and suffix). */
    static List<Line> script(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int pre = 0;
        while (pre < n && pre < m && a.get(pre).equals(b.get(pre))) {
            pre++;
        }
        int suf = 0;
        while (suf < n - pre && suf < m - pre && a.get(n - 1 - suf).equals(b.get(m - 1 - suf))) {
            suf++;
        }
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < pre; i++) {
            out.add(new Line(' ', a.get(i)));
        }
        out.addAll(myers(a.subList(pre, n - suf), b.subList(pre, m - suf)));
        for (int i = n - suf; i < n; i++) {
            out.add(new Line(' ', a.get(i)));
        }
        return out;
    }

    private static List<Line> myers(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int max = n + m;
        List<Line> out = new ArrayList<>();
        if (max == 0) {
            return out;
        }
        int off = max;
        int[] v = new int[2 * max + 2];
        List<int[]> trace = new ArrayList<>(); // v before step d, window [-d, d]
        int dEnd = -1;
        outer:
        for (int d = 0; d <= max; d++) {
            int[] snap = new int[2 * d + 1];
            for (int k = -d; k <= d; k++) {
                snap[k + d] = v[k + off];
            }
            trace.add(snap);
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[k - 1 + off] < v[k + 1 + off])) {
                    x = v[k + 1 + off];
                } else {
                    x = v[k - 1 + off] + 1;
                }
                int y = x - k;
                while (x < n && y < m && a.get(x).equals(b.get(y))) {
                    x++;
                    y++;
                }
                v[k + off] = x;
                if (x >= n && y >= m) {
                    dEnd = d;
                    break outer;
                }
            }
        }
        List<Line> rev = new ArrayList<>();
        int x = n;
        int y = m;
        for (int d = dEnd; d > 0; d--) {
            int[] pv = trace.get(d);
            int k = x - y;
            int prevK = (k == -d || (k != d && val(pv, k - 1, d) < val(pv, k + 1, d))) ? k + 1 : k - 1;
            int prevX = val(pv, prevK, d);
            int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                rev.add(new Line(' ', a.get(x - 1)));
                x--;
                y--;
            }
            if (x == prevX) {
                rev.add(new Line('+', b.get(y - 1)));
                y--;
            } else {
                rev.add(new Line('-', a.get(x - 1)));
                x--;
            }
        }
        while (x > 0 && y > 0) {
            rev.add(new Line(' ', a.get(x - 1)));
            x--;
            y--;
        }
        for (int i = rev.size() - 1; i >= 0; i--) {
            out.add(rev.get(i));
        }
        return out;
    }

    private static int val(int[] window, int k, int d) {
        int i = k + d;
        return i < 0 || i >= window.length ? -1 : window[i];
    }

    /** Hunks of a -> b with {@code context} unchanged lines around every change. */
    public static List<Hunk> hunks(List<String> a, List<String> b, int context) {
        List<Line> s = script(a, b);
        int[] oldAt = new int[s.size() + 1];
        int[] newAt = new int[s.size() + 1];
        int o = 1;
        int nn = 1;
        for (int j = 0; j < s.size(); j++) {
            oldAt[j] = o;
            newAt[j] = nn;
            char op = s.get(j).op();
            if (op != '+') {
                o++;
            }
            if (op != '-') {
                nn++;
            }
        }
        oldAt[s.size()] = o;
        newAt[s.size()] = nn;
        List<Hunk> out = new ArrayList<>();
        int i = 0;
        while (i < s.size()) {
            if (s.get(i).op() == ' ') {
                i++;
                continue;
            }
            int start = Math.max(0, i - context);
            int end = i;
            while (true) {
                while (end < s.size() && s.get(end).op() != ' ') {
                    end++;
                }
                int gap = end;
                while (gap < s.size() && s.get(gap).op() == ' ') {
                    gap++;
                }
                if (gap < s.size() && gap - end <= 2 * context) {
                    end = gap; // the next change is close: same hunk
                } else {
                    end = Math.min(s.size(), end + context);
                    break;
                }
            }
            List<Line> lines = new ArrayList<>(s.subList(start, end));
            int ol = 0;
            int nl = 0;
            for (Line l : lines) {
                if (l.op() != '+') {
                    ol++;
                }
                if (l.op() != '-') {
                    nl++;
                }
            }
            out.add(new Hunk(ol == 0 ? oldAt[start] - 1 : oldAt[start], ol, nl == 0 ? newAt[start] - 1 : newAt[start], nl, lines));
            i = end;
        }
        return out;
    }

    /**
     * Unified diff of one file ({@code null} text = the file is absent); empty when the texts are equal. Paths are
     * written as {@code a/<oldPath>} and {@code b/<newPath>} ({@code /dev/null} for an absent side).
     */
    public static String diff(String oldPath, String oldText, String newPath, String newText, int context) {
        if (oldText != null && oldText.equals(newText)) {
            return "";
        }
        List<Hunk> hs = hunks(oldText == null ? List.of() : lines(oldText), newText == null ? List.of() : lines(newText), context);
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(oldText == null ? "/dev/null" : "a/" + oldPath).append('\n');
        sb.append("+++ ").append(newText == null ? "/dev/null" : "b/" + newPath).append('\n');
        for (Hunk h : hs) {
            sb.append("@@ -").append(range(h.oldStart(), h.oldLen())).append(" +").append(range(h.newStart(), h.newLen())).append(" @@\n");
            for (Line l : h.lines()) {
                if (l.text().endsWith(NOEOL)) {
                    sb.append(l.op()).append(l.text(), 0, l.text().length() - NOEOL.length()).append('\n').append(MARKER).append('\n');
                } else {
                    sb.append(l.op()).append(l.text()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** Header line of a blind patch, before its {@code ---} line. */
    public static final String BLIND = "porter-blind-patch base-sha256=";

    /**
     * A blind diff: like {@link #diff} with no context, but the removed lines are not written (only how many), so the
     * patch carries no text of the base; the base is pinned by its sha256 instead. For bases that must not be
     * redistributed (e.g. the user's own decompile of a mod whose licence forbids it): the patch holds only new text.
     */
    public static String diffBlind(String oldPath, String oldText, String newPath, String newText) {
        if (oldText == null) {
            return diff(oldPath, null, newPath, newText, 0);
        }
        if (oldText.equals(newText)) {
            return "";
        }
        List<Hunk> hs = hunks(lines(oldText), newText == null ? List.of() : lines(newText), 0);
        StringBuilder sb = new StringBuilder();
        sb.append(BLIND).append(sha256(oldText)).append('\n');
        sb.append("--- a/").append(oldPath).append('\n');
        sb.append("+++ ").append(newText == null ? "/dev/null" : "b/" + newPath).append('\n');
        for (Hunk h : hs) {
            sb.append("@@ -").append(range(h.oldStart(), h.oldLen())).append(" +").append(range(h.newStart(), h.newLen())).append(" @@\n");
            for (Line l : h.lines()) {
                if (l.op() != '+') {
                    continue;
                }
                if (l.text().endsWith(NOEOL)) {
                    sb.append('+').append(l.text(), 0, l.text().length() - NOEOL.length()).append('\n').append(MARKER).append('\n');
                } else {
                    sb.append('+').append(l.text()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String range(int start, int len) {
        return len == 1 ? String.valueOf(start) : start + "," + len;
    }

    // ----------------------------------------------------------------------------------------------- parse

    /** Parses a (multi-file) unified diff; {@code diff --git}, {@code index} and other header lines are skipped. */
    public static List<FilePatch> parse(String text) {
        List<FilePatch> out = new ArrayList<>();
        List<String> ls = new ArrayList<>();
        for (String l : lines(text)) {
            ls.add(l.endsWith(NOEOL) ? l.substring(0, l.length() - NOEOL.length()) : l);
        }
        int i = 0;
        String blindSha = null;
        while (i < ls.size()) {
            String l = ls.get(i);
            if (l.startsWith(BLIND)) {
                blindSha = l.substring(BLIND.length()).strip();
                i++;
                continue;
            }
            if (!l.startsWith("--- ") || i + 1 >= ls.size() || !ls.get(i + 1).startsWith("+++ ")) {
                i++;
                continue;
            }
            String sha = blindSha;
            blindSha = null;
            String oldP = headerPath(l.substring(4), "a/");
            String newP = headerPath(ls.get(i + 1).substring(4), "b/");
            i += 2;
            List<Hunk> hunks = new ArrayList<>();
            while (i < ls.size() && ls.get(i).startsWith("@@ ")) {
                String h = ls.get(i);
                int[] r = parseRange(h);
                i++;
                List<Line> lines = new ArrayList<>();
                int oldLeft = sha != null ? 0 : r[1]; // a blind hunk lists no removed lines
                int newLeft = r[3];
                while (i < ls.size() && (oldLeft > 0 || newLeft > 0 || ls.get(i).equals(MARKER))) {
                    String x = ls.get(i++);
                    if (x.equals(MARKER)) {
                        Line prev = lines.remove(lines.size() - 1);
                        lines.add(new Line(prev.op(), prev.text() + NOEOL));
                        continue;
                    }
                    char op = x.isEmpty() ? ' ' : x.charAt(0);
                    if (op != ' ' && op != '-' && op != '+' || sha != null && op != '+') {
                        throw new IllegalArgumentException("malformed hunk line in the patch of " + newP + ": " + x);
                    }
                    lines.add(new Line(op, x.isEmpty() ? "" : x.substring(1)));
                    if (op != '+') {
                        oldLeft--;
                    }
                    if (op != '-') {
                        newLeft--;
                    }
                }
                if (oldLeft != 0 || newLeft != 0) {
                    throw new IllegalArgumentException("truncated hunk in the patch of " + newP + ": " + h);
                }
                hunks.add(new Hunk(r[0], r[1], r[2], r[3], lines));
            }
            out.add(new FilePatch(oldP, newP, hunks, sha));
        }
        return out;
    }

    private static String headerPath(String s, String prefix) {
        int tab = s.indexOf('\t');
        String p = tab < 0 ? s : s.substring(0, tab);
        if (p.equals("/dev/null")) {
            return null;
        }
        return p.startsWith(prefix) ? p.substring(prefix.length()) : p;
    }

    private static int[] parseRange(String h) {
        String[] parts = h.split(" ");
        int[] o = range(parts[1].substring(1));
        int[] n = range(parts[2].substring(1));
        return new int[] {o[0], o[1], n[0], n[1]};
    }

    private static int[] range(String s) {
        int c = s.indexOf(',');
        return c < 0 ? new int[] {Integer.parseInt(s), 1} : new int[] {Integer.parseInt(s.substring(0, c)), Integer.parseInt(s.substring(c + 1))};
    }

    // ----------------------------------------------------------------------------------------------- apply

    /**
     * Applies one file patch to the old text ({@code null} for a created file); returns the new text ({@code null}
     * for a deleted file). Throws {@link IllegalStateException} naming the first mismatch when the patch does not fit.
     */
    public static String apply(FilePatch p, String oldText) {
        String name = p.newPath() != null ? p.newPath() : p.oldPath();
        if (p.created() && oldText != null) {
            throw new IllegalStateException("the patch creates " + name + " but the file exists");
        }
        if (!p.created() && oldText == null) {
            throw new IllegalStateException("the patch of " + name + " needs its base file " + p.oldPath() + ", which is missing");
        }
        if (p.blind() && !sha256(oldText).equalsIgnoreCase(p.baseSha256())) {
            throw new IllegalStateException("the blind patch of " + name + " needs base " + p.oldPath() + " with sha256 " + p.baseSha256()
                    + ", found " + sha256(oldText) + " (a different decompiler, decompiler runtime or original jar?)");
        }
        List<String> src = oldText == null ? List.of() : lines(oldText);
        List<String> res = new ArrayList<>();
        int pos = 0;
        for (Hunk h : p.hunks()) {
            int start = h.oldLen() == 0 ? h.oldStart() : h.oldStart() - 1;
            if (start < pos || start > src.size()) {
                throw new IllegalStateException("the patch of " + name + " does not apply: hunk @@ -" + h.oldStart() + " is out of range");
            }
            res.addAll(src.subList(pos, start));
            int k = start;
            if (p.blind()) {
                if (start + h.oldLen() > src.size()) {
                    throw new IllegalStateException("the patch of " + name + " does not apply: hunk @@ -" + h.oldStart() + " is out of range");
                }
                h.lines().forEach(l -> res.add(l.text()));
                pos = start + h.oldLen();
                continue;
            }
            for (Line l : h.lines()) {
                if (l.op() == '+') {
                    res.add(l.text());
                    continue;
                }
                if (k >= src.size() || !src.get(k).equals(l.text())) {
                    throw new IllegalStateException("the patch of " + name + " does not apply at line " + (k + 1) + ": expected '"
                            + shown(l.text()) + "', found '" + (k < src.size() ? shown(src.get(k)) : "<end of file>") + "'");
                }
                if (l.op() == ' ') {
                    res.add(l.text());
                }
                k++;
            }
            pos = k;
        }
        res.addAll(src.subList(pos, src.size()));
        return p.deleted() ? null : join(res);
    }

    private static String shown(String s) {
        String t = s.replace(NOEOL, "");
        return t.length() <= 80 ? t : t.substring(0, 77) + "...";
    }
}
