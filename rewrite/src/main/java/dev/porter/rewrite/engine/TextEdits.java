package dev.porter.rewrite.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Position-based rewriting of one source file. Edits replace an original range [start, end) with pieces of literal
 * text and references to original sub-ranges; referenced ranges are rendered recursively, so edits nested inside a
 * placeholder's range (e.g. a type rename inside a call's argument) are applied too. Edits inside a replaced range
 * but outside every placeholder are dropped (the template replaced that code). Insertions are zero-width edits.
 * Everything else in the file stays byte-identical, which keeps formatting and makes the output deterministic.
 */
public final class TextEdits {

    public sealed interface Piece permits Lit, Ref {
    }

    public record Lit(String text) implements Piece {
    }

    public record Ref(int start, int end) implements Piece {
    }

    public static final class Edit {
        final int start;
        final int end;
        final List<Piece> pieces;
        final Object tag;
        final int seq;
        final List<Edit> children = new ArrayList<>();

        Edit(int start, int end, List<Piece> pieces, Object tag, int seq) {
            this.start = start;
            this.end = end;
            this.pieces = pieces;
            this.tag = tag;
            this.seq = seq;
        }

        boolean insertion() {
            return start == end;
        }

        public int start() {
            return start;
        }

        public int end() {
            return end;
        }

        public Object tag() {
            return tag;
        }

        boolean contains(Edit e) {
            if (e.insertion()) {
                return start < e.start && e.start < end;
            }
            return start <= e.start && e.end <= end;
        }
    }

    /** Result: new text, edits that could not be placed (overlap) and edits swallowed by an enclosing replacement. */
    public record Result(String text, List<Edit> conflicts, List<Edit> dropped) {
    }

    private final String text;
    private final List<Edit> edits = new ArrayList<>();

    public TextEdits(String text) {
        this.text = text;
    }

    public Edit replace(int start, int end, List<Piece> pieces, Object tag) {
        Edit e = new Edit(start, end, List.copyOf(pieces), tag, edits.size());
        edits.add(e);
        return e;
    }

    public Edit replace(int start, int end, String literal, Object tag) {
        return replace(start, end, List.of(new Lit(literal)), tag);
    }

    public Edit insert(int at, String literal, Object tag) {
        return replace(at, at, List.of(new Lit(literal)), tag);
    }

    public List<Edit> edits() {
        return edits;
    }

    public Result apply() {
        List<Edit> sorted = new ArrayList<>(edits);
        for (Edit e : sorted) {
            e.children.clear();
        }
        sorted.sort(Comparator.<Edit>comparingInt(e -> e.start)
                .thenComparing(e -> e.insertion() ? 0 : 1)
                .thenComparing(Comparator.<Edit>comparingInt(e -> e.end).reversed())
                .thenComparingInt(e -> e.seq));
        List<Edit> roots = new ArrayList<>();
        List<Edit> conflicts = new ArrayList<>();
        List<Edit> stack = new ArrayList<>();
        for (Edit e : sorted) {
            boolean rejected = false;
            while (!stack.isEmpty()) {
                Edit top = stack.get(stack.size() - 1);
                if (!e.insertion() && e.start < top.end && e.end > top.end) {
                    rejected = true; // partial overlap
                    break;
                }
                if (!e.insertion() && e.start == top.start && e.end == top.end) {
                    rejected = true; // two replacements of the same range
                    break;
                }
                if (top.contains(e)) {
                    break;
                }
                stack.remove(stack.size() - 1);
            }
            if (rejected) {
                conflicts.add(e);
                continue;
            }
            if (stack.isEmpty()) {
                roots.add(e);
            } else {
                stack.get(stack.size() - 1).children.add(e);
            }
            if (!e.insertion()) {
                stack.add(e);
            }
        }
        Set<Edit> rendered = new HashSet<>();
        StringBuilder sb = new StringBuilder(text.length() + 256);
        render(0, text.length(), roots, sb, rendered);
        List<Edit> dropped = new ArrayList<>();
        for (Edit e : sorted) {
            if (!rendered.contains(e) && !conflicts.contains(e)) {
                dropped.add(e);
            }
        }
        return new Result(sb.toString(), conflicts, dropped);
    }

    private void render(int start, int end, List<Edit> candidates, StringBuilder sb, Set<Edit> rendered) {
        int pos = start;
        for (Edit c : candidates) {
            boolean inside = c.insertion() ? c.start >= start && c.start <= end : c.start >= start && c.end <= end;
            if (!inside || c.start < pos || rendered.contains(c)) {
                continue;
            }
            sb.append(text, pos, c.start);
            rendered.add(c);
            for (Piece p : c.pieces) {
                if (p instanceof Lit l) {
                    sb.append(l.text());
                } else if (p instanceof Ref r) {
                    render(r.start(), r.end(), c.children, sb, rendered);
                }
            }
            pos = c.end;
        }
        sb.append(text, pos, end);
    }
}
