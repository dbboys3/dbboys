package com.dbboys.ui.component.completion;

import java.util.Objects;

/**
 * A single completion candidate shown in the autocomplete popup.
 * Immutable — constructed once per provider, filtered by prefix at query time.
 */
public class CompletionItem implements Comparable<CompletionItem> {

    private final String label;
    private final String insertText;
    private final CompletionKind kind;
    private final String detail;
    private final int priority;

    public CompletionItem(String label, String insertText, CompletionKind kind, String detail, int priority) {
        this.label = label;
        this.insertText = (insertText != null) ? insertText : label;
        this.kind = kind;
        this.detail = (detail != null) ? detail : "";
        this.priority = priority;
    }

    // ---- getters ----

    public String getLabel() {
        return label;
    }

    public String getInsertText() {
        return insertText;
    }

    public CompletionKind getKind() {
        return kind;
    }

    public String getDetail() {
        return detail;
    }

    public int getPriority() {
        return priority;
    }

    // ---- sorting — lower priority first, then alphabetically ----

    @Override
    public int compareTo(CompletionItem o) {
        int cmp = Integer.compare(this.priority, o.priority);
        if (cmp != 0) return cmp;
        return this.label.compareToIgnoreCase(o.label);
    }

    // ---- dedup by (label, kind) ----

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CompletionItem that)) return false;
        return label.equals(that.label) && kind == that.kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(label, kind);
    }

    @Override
    public String toString() {
        return label + " [" + kind + "]";
    }
}
