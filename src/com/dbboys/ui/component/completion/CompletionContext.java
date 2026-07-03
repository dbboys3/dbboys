package com.dbboys.ui.component.completion;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Holds the result of analysing the SQL text around the caret.
 * Drives which CandidateProviders are applicable and how results are filtered.
 */
public class CompletionContext {

    private final boolean disabled;
    private final String prefixToken;
    private final Set<CompletionKind> expectedKinds;
    private final List<String> tableReferences;
    private final String qualifiedPrefix;
    private final int caretPosition;

    private CompletionContext(Builder builder) {
        this.disabled = builder.disabled;
        this.prefixToken = builder.prefixToken;
        this.expectedKinds = Collections.unmodifiableSet(builder.expectedKinds);
        this.tableReferences = Collections.unmodifiableList(builder.tableReferences);
        this.qualifiedPrefix = builder.qualifiedPrefix;
        this.caretPosition = builder.caretPosition;
    }

    /** When true, no completion should be offered (e.g. inside a string literal or comment). */
    public boolean isDisabled() {
        return disabled;
    }

    /** The partial word immediately before the caret. Never null. */
    public String getPrefixToken() {
        return prefixToken;
    }

    /** Which kinds of candidates are relevant at this caret position. */
    public Set<CompletionKind> getExpectedKinds() {
        return expectedKinds;
    }

    /** Table/alias names found in the current statement's FROM/JOIN clauses. */
    public List<String> getTableReferences() {
        return tableReferences;
    }

    /**
     * When the caret follows {@code schema.} or {@code alias.}, this is the qualifier
     * token before the dot. Null otherwise.
     */
    public String getQualifiedPrefix() {
        return qualifiedPrefix;
    }

    /** Absolute caret position in the document (0-based). */
    public int getCaretPosition() {
        return caretPosition;
    }

    /** True when completion should actively suggest table/view/synonym names. */
    public boolean expectsTableReferences() {
        return expectedKinds.contains(CompletionKind.TABLE)
                || expectedKinds.contains(CompletionKind.VIEW)
                || expectedKinds.contains(CompletionKind.SYNONYM)
                || expectedKinds.contains(CompletionKind.SYSTABLE);
    }

    /** True when completion should suggest column names. */
    public boolean expectsColumns() {
        return expectedKinds.contains(CompletionKind.COLUMN);
    }

    public static Builder disabled() {
        return new Builder().setDisabled(true);
    }

    public static Builder enabled(String prefixToken, int caretPosition) {
        return new Builder()
                .setDisabled(false)
                .setPrefixToken(prefixToken)
                .setCaretPosition(caretPosition);
    }

    @Override
    public String toString() {
        return "CompletionContext{disabled=" + disabled
                + ", prefix='" + prefixToken + "'"
                + ", expectedKinds=" + expectedKinds
                + ", tables=" + tableReferences
                + ", qualifier='" + qualifiedPrefix + "'}";
    }

    // ---- builder ----

    public static class Builder {
        private boolean disabled;
        private String prefixToken = "";
        private Set<CompletionKind> expectedKinds = EnumSet.allOf(CompletionKind.class);
        private List<String> tableReferences = List.of();
        private String qualifiedPrefix;
        private int caretPosition;

        public Builder setDisabled(boolean disabled) { this.disabled = disabled; return this; }
        public Builder setPrefixToken(String prefixToken) { this.prefixToken = prefixToken; return this; }
        public Builder setExpectedKinds(Set<CompletionKind> expectedKinds) { this.expectedKinds = expectedKinds; return this; }
        public Builder setTableReferences(List<String> tableReferences) { this.tableReferences = tableReferences; return this; }
        public Builder setQualifiedPrefix(String qualifiedPrefix) { this.qualifiedPrefix = qualifiedPrefix; return this; }
        public Builder setCaretPosition(int caretPosition) { this.caretPosition = caretPosition; return this; }

        public CompletionContext build() {
            return new CompletionContext(this);
        }
    }
}
