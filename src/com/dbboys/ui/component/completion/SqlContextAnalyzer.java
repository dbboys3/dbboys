package com.dbboys.ui.component.completion;

import java.util.*;

/**
 * Lightweight char-scanning analyser that determines what kind of SQL context the
 * caret is in and extracts the partial token being typed.
 *
 * <p>Pure state-machine 鈥?no JSqlParser dependency.  Walks backward from the caret
 * through the SQL text to detect:
 * <ul>
 *   <li>String / comment boundaries (鈫?disable completion)</li>
 *   <li>The partial word prefix immediately before the caret</li>
 *   <li>Dot-qualified prefixes (schema. / alias.)</li>
 *   <li>(Phase 2) Clause-introducing keywords to narrow expected kinds</li>
 *   <li>(Phase 2) Table references in FROM/JOIN</li>
 * </ul>
 */
public class SqlContextAnalyzer {

    /** Max chars to scan backward 鈥?mirrors {@code LOCAL_HIGHLIGHT_MAX} in the editor. */
    private static final int MAX_SCAN_BACK = 4000;

    /** Characters that count as "word" for the prefix token. */
    private static final String WORD_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_";

    /** Clause-introducing keywords (uppercase for fast comparison). */
    private static final Set<String> FROM_CLAUSE_KW = Set.of(
            "FROM", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL",
            "INTO", "UPDATE", "TABLE", "VIEW"
    );
    private static final Set<String> COLUMN_CLAUSE_KW = Set.of(
            "SELECT", "WHERE", "AND", "OR", "ON", "HAVING", "SET",
            "ORDER", "GROUP", "BY"
    );
    private static final Set<String> STATEMENT_START_KW = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP",
            "TRUNCATE", "MERGE", "WITH", "BEGIN", "DECLARE", "CALL", "EXECUTE",
            "GRANT", "REVOKE"
    );

    /**
     * Analyse the SQL text around {@code caretPos} and return a
     * {@link CompletionContext}.
     *
     * @param fullSql  complete editor text
     * @param caretPos absolute caret offset (0 鈮?caretPos 鈮?fullSql.length())
     */
    public CompletionContext analyze(String fullSql, int caretPos) {
        if (caretPos <= 0) {
            return CompletionContext.enabled("", caretPos).build();
        }
        // Safety clamp: caret beyond text length would cause
        // StringIndexOutOfBoundsException in substring() below.
        if (caretPos > fullSql.length()) {
            caretPos = fullSql.length();
        }

        // ---- phase 1: walk backward; detect string/comment state ----
        int scanStart = Math.max(0, caretPos - MAX_SCAN_BACK);
        String prefix = fullSql.substring(scanStart, caretPos);

        BackwardScanResult scan = scanBackwardForContext(prefix);
        if (scan.disabled) {
            return CompletionContext.disabled().setCaretPosition(caretPos).build();
        }

        // ---- phase 2: extract prefix token ----
        String prefixToken = scan.partialWord;

        // ---- phase 3: detect clause context and table references ----
        Set<CompletionKind> expectedKinds = EnumSet.allOf(CompletionKind.class);
        List<String> tableRefs = List.of();
        String qualifiedPrefix = null;

        String precedingText = scan.precedingText;
        boolean hasDot = precedingText.endsWith(".");
        if (hasDot) {
            // Extract the qualifier before the dot
            String beforeDot = extractLastWord(precedingText.substring(0, precedingText.length() - 1));
            qualifiedPrefix = beforeDot.isEmpty() ? null : beforeDot;
        }

        ClauseInfo clauseInfo = detectClause(precedingText, hasDot, qualifiedPrefix);
        expectedKinds = clauseInfo.expectedKinds;
        tableRefs = clauseInfo.tableRefs;

        return CompletionContext.enabled(prefixToken, caretPos)
                .setExpectedKinds(expectedKinds)
                .setTableReferences(tableRefs)
                .setQualifiedPrefix(qualifiedPrefix)
                .build();
    }

    // ---- backward scan ----

    private BackwardScanResult scanBackwardForContext(String prefix) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inBacktick = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        // Phase 2: track brace-style comments too (Informix { 鈥?})

        // Walk FROM THE END of prefix toward the beginning so we know the
        // "current" state nearest the caret.
        int len = prefix.length();
        int wordStart = len; // where the partial word starts (exclusive end)
        StringBuilder preceding = new StringBuilder();

        for (int i = len - 1; i >= 0; i--) {
            char c = prefix.charAt(i);

            // -- block-comment close
            if (i > 0 && c == '/' && prefix.charAt(i - 1) == '*' && !inSingleQuote && !inDoubleQuote && !inBacktick && !inLineComment) {
                inBlockComment = false;
                i--; // skip '*'
                continue;
            }
            // -- block-comment open (scanning backward, so: close before open)
            if (i > 0 && c == '*' && prefix.charAt(i - 1) == '/' && !inSingleQuote && !inDoubleQuote && !inBacktick && !inLineComment) {
                inBlockComment = true;
                i--; // skip '/'
                continue;
            }
            // -- Informix brace comment close
            if (c == '}' && !inSingleQuote && !inDoubleQuote && !inBacktick && !inLineComment && !inBlockComment) {
                inBlockComment = true;
                continue;
            }
            if (c == '{' && !inSingleQuote && !inDoubleQuote && !inBacktick && !inLineComment) {
                inBlockComment = false;
                continue;
            }
            // -- line comment (walking backward, encounter \n 鈫?past start of comment)
            if (c == '\n' && inLineComment) {
                inLineComment = false;
                continue;
            }
            if (i > 0 && c == '-' && prefix.charAt(i - 1) == '-' && !inSingleQuote && !inDoubleQuote && !inBacktick && !inBlockComment) {
                inLineComment = true;
                i--; // skip other '-'
                continue;
            }

            // If we're inside a string or comment, we're not at a completion site
            if (inSingleQuote || inDoubleQuote || inBacktick || inLineComment || inBlockComment) {
                // Keep unwinding state 鈥?toggle in/out of strings
                if (c == '\'' && !inDoubleQuote && !inBacktick && !inLineComment && !inBlockComment) {
                    inSingleQuote = !inSingleQuote;
                } else if (c == '"' && !inSingleQuote && !inBacktick && !inLineComment && !inBlockComment) {
                    inDoubleQuote = !inDoubleQuote;
                } else if (c == '`' && !inSingleQuote && !inDoubleQuote && !inLineComment && !inBlockComment) {
                    inBacktick = !inBacktick;
                }
                continue;
            }

            // ---- we are NOT in a string/comment 鈥?track the word boundary ----
            if (WORD_CHARS.indexOf(c) >= 0) {
                // still inside the partial word 鈥?extend left
                wordStart = i;
            } else {
                // we hit a non-word char 鈫?word boundary
                if (wordStart < len) {
                    // we've already found the word; collect preceding text and stop
                    preceding.insert(0, prefix.substring(i + 1, wordStart));
                    // Store the rest of preceding (everything before the word)
                    String rest = prefix.substring(0, i + 1);
                    return new BackwardScanResult(false, prefix.substring(wordStart, len), rest, preceding.toString());
                }
                preceding.insert(0, c);
            }
        }

        // Reached start of scanned region 鈥?word extends to position 0
        if (wordStart < len) {
            return new BackwardScanResult(false, prefix.substring(wordStart, len), "", "");
        }

        // No word found at all (caret preceded by only non-word chars)
        return new BackwardScanResult(false, "", prefix, "");
    }

    // ---- clause detection (Phase 2) ----

    private ClauseInfo detectClause(String precedingText, boolean hasDot, String qualifiedPrefix) {
        Set<CompletionKind> kinds = EnumSet.of(CompletionKind.KEYWORD, CompletionKind.FUNCTION);

        // If we have a dot-qualified prefix, expect columns
        if (hasDot && qualifiedPrefix != null && !qualifiedPrefix.isEmpty()) {
            kinds.add(CompletionKind.COLUMN);
            return new ClauseInfo(kinds, List.of(qualifiedPrefix));
        }

        // Extract the previous significant token(s) to determine clause context
        String prevToken = extractLastSignificantToken(precedingText);
        String prevUpper = prevToken.toUpperCase(java.util.Locale.ROOT);

        if (prevToken.isEmpty()) {
            // At statement start 鈥?suggest everything
            kinds.addAll(EnumSet.of(
                    CompletionKind.TABLE, CompletionKind.VIEW,
                    CompletionKind.SCHEMA, CompletionKind.SNIPPET
            ));
            return new ClauseInfo(kinds, List.of());
        }

        if (FROM_CLAUSE_KW.contains(prevUpper)) {
            kinds.addAll(EnumSet.of(
                    CompletionKind.TABLE, CompletionKind.VIEW,
                    CompletionKind.SCHEMA, CompletionKind.SYNONYM
            ));
            return new ClauseInfo(kinds, extractTableReferences(precedingText));
        }

        if (COLUMN_CLAUSE_KW.contains(prevUpper)) {
            kinds.add(CompletionKind.COLUMN);
            kinds.add(CompletionKind.ALIAS);
            return new ClauseInfo(kinds, extractTableReferences(precedingText));
        }

        if (",".equals(prevToken)) {
            // Comma 鈥?what to suggest depends on what clause we're in.
            // Fall back: suggest columns+functions (most comma-separated lists are columns)
            kinds.addAll(EnumSet.of(CompletionKind.COLUMN, CompletionKind.ALIAS));
            return new ClauseInfo(kinds, extractTableReferences(precedingText));
        }

        // Default: generic context
        kinds.addAll(EnumSet.of(
                CompletionKind.TABLE, CompletionKind.VIEW,
                CompletionKind.COLUMN, CompletionKind.ALIAS,
                CompletionKind.SCHEMA, CompletionKind.SYNONYM
        ));
        return new ClauseInfo(kinds, extractTableReferences(precedingText));
    }

    /**
     * Extract the last "significant" token from preceding text.
     * "Significant" = not whitespace, not a dot, not a parenthesis/bracket.
     */
    private String extractLastSignificantToken(String preceding) {
        int len = preceding.length();
        int end = len;
        // skip trailing whitespace
        while (end > 0 && Character.isWhitespace(preceding.charAt(end - 1))) {
            end--;
        }
        if (end <= 0) return "";
        // walk backward to find token start
        int start = end;
        while (start > 0 && !Character.isWhitespace(preceding.charAt(start - 1))
                && preceding.charAt(start - 1) != '('
                && preceding.charAt(start - 1) != ')'
                && preceding.charAt(start - 1) != ','
                && preceding.charAt(start - 1) != ';') {
            start--;
        }
        return preceding.substring(start, end);
    }

    /**
     * Naive extraction of table references from FROM/JOIN clauses.
     * For Phase 2 鈥?a simple regex-based scan; will be refined later.
     */
    private List<String> extractTableReferences(String precedingText) {
        // Simple approach: find words following FROM or JOIN that are not keywords
        List<String> refs = new ArrayList<>();
        String upper = precedingText.toUpperCase(java.util.Locale.ROOT);

        // Quick scan: look for "FROM <word>" and "JOIN <word>" patterns
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?:FROM|JOIN)\\s+([A-Za-z_][A-Za-z0-9_]*)",
                java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(precedingText);

        while (m.find()) {
            String ref = m.group(1);
            if (!FROM_CLAUSE_KW.contains(ref.toUpperCase(java.util.Locale.ROOT))
                    && !COLUMN_CLAUSE_KW.contains(ref.toUpperCase(java.util.Locale.ROOT))) {
                refs.add(ref);
            }
        }
        return refs;
    }

    /** Extract the last word from a string. */
    private String extractLastWord(String s) {
        int len = s.length();
        int end = len;
        while (end > 0 && WORD_CHARS.indexOf(s.charAt(end - 1)) < 0) {
            end--;
        }
        int start = end;
        while (start > 0 && WORD_CHARS.indexOf(s.charAt(start - 1)) >= 0) {
            start--;
        }
        return s.substring(start, end);
    }

    // ---- inner types ----

    private record BackwardScanResult(boolean disabled, String partialWord, String precedingText, String wordPrefix) {
    }

    private record ClauseInfo(Set<CompletionKind> expectedKinds, List<String> tableRefs) {
    }
}
