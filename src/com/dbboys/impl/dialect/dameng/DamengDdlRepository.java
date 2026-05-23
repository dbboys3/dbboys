package com.dbboys.impl.dialect.dameng;

import com.dbboys.api.DdlRepository;
import com.dbboys.db.SqlRunner;

import java.io.IOException;
import java.io.Reader;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.LongConsumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class DamengDdlRepository implements DdlRepository {

    private static final Logger log = LogManager.getLogger(DamengDdlRepository.class);
    private static final int QUERY_TIMEOUT = 60;

    /**
     * {@code CREATE OR REPLACE … (PACKAGE [BODY]|PROCEDURE|FUNCTION) <name>} header; group 3 is the dotted name chain.
     */
    private static final Pattern DAMENG_QUALIFIABLE_HEADER = Pattern.compile(
            "(?is)^\\s*create\\s+or\\s+replace\\s+(?:editionable\\s+|editioning\\s+|noneditionable\\s+)*"
                    + "(?:\\s*(?:\\r\\n|[\\r\\n])\\s*)*"
                    + "(package\\s+body|package|procedure|function)(\\s+)"
                    + "((?:\\\"[^\\\"]+\\\"|[a-zA-Z][a-zA-Z0-9_$#]*)(?:\\s*\\.\\s*(?:\\\"[^\\\"]+\\\"|[a-zA-Z][a-zA-Z0-9_$#]*))*)"
                    + "(?=\\s*(?:\\(|is\\b|as\\b|;|[\\r\\n]|$))"
    );

    /**
     * Export progress total when splitting DDL: pre-data phase + post-data (standalone indexes, constraints, triggers).
     * Matches {@link #exportDatabaseDdlParts}; table count uses body-only DDL, indexes exclude constraint-backed ones.
     */
    private static final String SQL_EXPORT_ITEM_COUNT_SPLIT = """
            SELECT
              (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'SEQUENCE' AND secondary = 'N')
            + (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'TABLE' AND secondary = 'N')
            + (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'VIEW' AND secondary = 'N')
            + (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'SYNONYM' AND secondary = 'N')
            + (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'FUNCTION' AND secondary = 'N')
            + (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'PROCEDURE' AND secondary = 'N')
            + (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'PACKAGE' AND secondary = 'N')
            + (SELECT COUNT(*) FROM all_scheduler_jobs WHERE owner = ?)
            + (SELECT COUNT(*) FROM all_objects i WHERE i.owner = ? AND i.object_type = 'INDEX' AND i.secondary = 'N'
               AND NOT EXISTS (
                   SELECT 1 FROM all_constraints c
                   WHERE c.owner = i.owner
                     AND c.index_name = i.object_name
                     AND c.index_name IS NOT NULL))
            + (SELECT COUNT(*) FROM all_constraints
               WHERE owner = ? AND status = 'ENABLED'
                 AND constraint_type IN ('P','U','C','R')
                 AND (generated IS NULL OR generated = 'USER NAME'))
            + (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'TRIGGER' AND secondary = 'N')
            AS cnt FROM dual
            """;

    /** Indexes not backing a constraint (PK/UK); exported in post-data phase. */
    private static final String SQL_STANDALONE_INDEX_NAMES = """
            SELECT i.object_name
            FROM all_objects i
            WHERE i.owner = ?
              AND i.object_type = 'INDEX'
              AND i.secondary = 'N'
              AND NOT EXISTS (
                  SELECT 1 FROM all_constraints c
                  WHERE c.owner = i.owner
                    AND c.index_name = i.object_name
                    AND c.index_name IS NOT NULL)
            ORDER BY i.object_name
            """;

    private static final String SQL_CONSTRAINTS_PUC = """
            SELECT constraint_name, constraint_type
            FROM all_constraints
            WHERE owner = ?
              AND status = 'ENABLED'
              AND constraint_type IN ('P','U','C')
              AND (generated IS NULL OR generated = 'USER NAME')
            ORDER BY CASE constraint_type
                       WHEN 'P' THEN 1 WHEN 'U' THEN 2 WHEN 'C' THEN 3
                     END,
                     constraint_name
            """;

    private static final String SQL_CONSTRAINTS_R = """
            SELECT constraint_name
            FROM all_constraints
            WHERE owner = ?
              AND status = 'ENABLED'
              AND constraint_type = 'R'
              AND (generated IS NULL OR generated = 'USER NAME')
            ORDER BY constraint_name
            """;

    private static final String SQL_SCHEMA_OBJECTS = """
            SELECT object_name, object_type FROM all_objects
            WHERE owner = ? AND object_type = ? AND secondary = 'N'
            ORDER BY object_name
            """;

    private static final String SQL_QUEUE_NAMES = """
            SELECT name
            FROM all_queues
            WHERE owner = ?
            ORDER BY name
            """;

    private static final String SQL_SCHEDULER_JOB_NAMES = """
            SELECT job_name
            FROM all_scheduler_jobs
            WHERE owner = ?
            ORDER BY job_name
            """;

    private static final String SQL_COMMENTS = """
            SELECT table_name, column_name, comments FROM all_col_comments
            WHERE owner = ? AND comments IS NOT NULL
            ORDER BY table_name, column_name
            """;

    private static final String SQL_TABLE_COMMENTS = """
            SELECT table_name, comments FROM all_tab_comments
            WHERE owner = ? AND comments IS NOT NULL AND table_type = 'TABLE'
            ORDER BY table_name
            """;

    private String currentSchema(Connection conn) throws SQLException {
        try {
            String schema = conn.getSchema();
            if (schema != null && !schema.isBlank()) {
                return schema.trim();
            }
        } catch (Exception ignored) {
        }
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        String result = runner.queryOne(
                "SELECT sys_context('USERENV','CURRENT_SCHEMA') FROM dual",
                null, rs -> rs.getString(1));
        return result != null && !result.isBlank() ? result.trim() : "DAMENG";
    }

    private static final int CLOB_READ_CHUNK = 32_767;

    /**
     * Reads {@code DBMS_METADATA.GET_DDL} CLOB. Prefers {@link ResultSet#getClob(int)} (more reliable than
     * {@link ResultSet#getObject(int)} with some Dameng drivers), then reads in chunks to avoid single-call
     * {@link Clob#getSubString(long, int)} truncation; falls back to character stream when length is unknown.
     */
    private static String readDdlClob(ResultSet rs, int columnIndex) throws SQLException {
        Clob clob = rs.getClob(columnIndex);
        if (rs.wasNull() || clob == null) {
            return rs.getString(columnIndex);
        }
        try {
            long len = clob.length();
            if (len > Integer.MAX_VALUE) {
                throw new SQLException("DDL CLOB length exceeds Java string limit");
            }
            if (len > 0) {
                StringBuilder sb = new StringBuilder((int) len);
                long pos = 1;
                while (pos <= len) {
                    int n = (int) Math.min(CLOB_READ_CHUNK, len - pos + 1);
                    sb.append(clob.getSubString(pos, n));
                    pos += n;
                }
                return sb.toString();
            }
            Reader reader = clob.getCharacterStream();
            if (reader == null) {
                return "";
            }
            try {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[16384];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    sb.append(buf, 0, n);
                }
                return sb.toString();
            } catch (IOException e) {
                throw new SQLException("Failed reading DDL CLOB", e);
            } finally {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        } finally {
            try {
                clob.free();
            } catch (SQLException ignored) {
            }
        }
    }

    /** Heuristic: package / procedure source normally ends with an {@code END ...;} tail. */
    private static boolean damengPlSqlLooksComplete(String ddl) {
        if (ddl == null || ddl.isBlank()) {
            return false;
        }
        return ddl.strip().matches("(?is).*\\bend\\b.*;\\s*$");
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        return s.length() >= prefix.length() && s.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /** Index of first code token after leading whitespace, line comments, and block comments. */
    private static int skipLeadingSqlCommentsAndWhitespace(String ddl) {
        int i = 0;
        int n = ddl.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(ddl.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            if (i + 1 < n && ddl.charAt(i) == '-' && ddl.charAt(i + 1) == '-') {
                while (i < n && ddl.charAt(i) != '\n' && ddl.charAt(i) != '\r') {
                    i++;
                }
                continue;
            }
            if (i + 1 < n && ddl.charAt(i) == '/' && ddl.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(ddl.charAt(i) == '*' && ddl.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                continue;
            }
            break;
        }
        return i;
    }

    /**
     * Trim trailing spaces per line, normalize newlines, collapse runs of blank lines (keeps at most one blank line).
     */
    private static String normalizePlSqlInternalWhitespace(String ddl) {
        if (ddl == null || ddl.isBlank()) {
            return ddl;
        }
        String t = ddl.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = t.split("\n", -1);
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i].stripTrailing());
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        t = sb.toString().replaceAll("\n{3,}", "\n\n");
        return t.stripLeading();
    }

    /**
     * {@link #normalizePlSqlInternalWhitespace} does not squeeze spaces <em>between</em> tokens on one line.
     * {@code DBMS_METADATA.GET_DDL} often pads heavily after {@code PACKAGE} / {@code PACKAGE BODY}; procedures
     * and functions are usually tighter, which is why packages looked worse.
     */
    private static String collapseHeaderLineKeywordSpacing(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        String s = line;
        s = s.replaceAll("(?i)(\\bcreate)\\h{2,}(?=or\\b)", "$1 ");
        s = s.replaceAll("(?i)(\\bor)\\h{2,}(?=replace\\b)", "$1 ");
        s = s.replaceAll("(?i)(\\breplace)\\h{2,}(?=editionable\\b|editioning\\b|noneditionable\\b|package\\b|procedure\\b|function\\b|trigger\\b|type\\b)", "$1 ");
        s = s.replaceAll("(?i)(\\beditionable)\\h{2,}(?=package\\b|procedure\\b|function\\b|trigger\\b|type\\b)", "$1 ");
        s = s.replaceAll("(?i)(\\beditioning)\\h{2,}(?=package\\b|procedure\\b|function\\b|trigger\\b|type\\b)", "$1 ");
        s = s.replaceAll("(?i)(\\bnoneditionable)\\h{2,}(?=package\\b|procedure\\b|function\\b|trigger\\b|type\\b)", "$1 ");
        s = s.replaceAll("(?i)(\\bpackage\\s+body)\\h{2,}", "$1 ");
        s = s.replaceAll("(?i)(\\bpackage)\\h{2,}(?!body\\b)", "$1 ");
        s = s.replaceAll("(?i)(\\bprocedure)\\h{2,}", "$1 ");
        s = s.replaceAll("(?i)(\\bfunction)\\h{2,}", "$1 ");
        s = s.replaceAll("(?i)(\\btrigger)\\h{2,}", "$1 ");
        return s;
    }

    /**
     * Apply {@link #collapseHeaderLineKeywordSpacing} only on the opening DDL lines (after leading comments),
     * so we do not rewrite spaces inside arbitrary PL/SQL bodies or string literals below {@code BEGIN}.
     */
    private static String collapseMetadataKeywordSpacing(String ddl) {
        if (ddl == null || ddl.isBlank()) {
            return ddl;
        }
        int start = skipLeadingSqlCommentsAndWhitespace(ddl);
        String prefix = ddl.substring(0, start);
        String code = ddl.substring(start);
        if (code.isEmpty()) {
            return ddl;
        }
        String[] lines = code.split("\n", -1);
        int budget = 16;
        for (int i = 0; i < lines.length && budget > 0; i++) {
            String raw = lines[i];
            String t = raw.stripLeading();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("--")) {
                continue;
            }
            if (t.length() >= 5 && t.regionMatches(true, 0, "BEGIN", 0, 5)
                    && (t.length() == 5 || Character.isWhitespace(t.charAt(5)))) {
                break;
            }
            lines[i] = collapseHeaderLineKeywordSpacing(raw);
            budget--;
        }
        return prefix + String.join("\n", lines);
    }

    private static String damengQuoteIdent(String ident) {
        if (ident == null) {
            return "\"\"";
        }
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private static List<String> splitDamengDottedIdentifiers(String chain) {
        List<String> parts = new ArrayList<>();
        if (chain == null) {
            return parts;
        }
        int i = 0;
        int n = chain.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(chain.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            if (chain.charAt(i) == '"') {
                int j = chain.indexOf('"', i + 1);
                if (j < 0) {
                    break;
                }
                parts.add(chain.substring(i + 1, j).replace("\"\"", "\""));
                i = j + 1;
            } else {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(chain.charAt(j))
                        || chain.charAt(j) == '_' || chain.charAt(j) == '$' || chain.charAt(j) == '#')) {
                    j++;
                }
                if (j == i) {
                    break;
                }
                parts.add(chain.substring(i, j));
                i = j;
            }
            while (i < n && Character.isWhitespace(chain.charAt(i))) {
                i++;
            }
            if (i < n && chain.charAt(i) == '.') {
                i++;
            }
        }
        return parts;
    }

    /**
     * If the opening {@code PACKAGE} / {@code PACKAGE BODY} / {@code PROCEDURE} / {@code FUNCTION} name is not
     * already {@code owner.object}, rewrite to {@code "OWNER".OBJECT} (owner quoted, object unquoted).
     */
    private static String qualifyOwnerInPlSqlHeader(String ddl, String owner, String baseObjectName) {
        if (ddl == null || ddl.isBlank() || owner == null || owner.isBlank()
                || baseObjectName == null || baseObjectName.isBlank()) {
            return ddl;
        }
        String ou = owner.trim();
        String bu = baseObjectName.trim();

        int st = skipLeadingSqlCommentsAndWhitespace(ddl);
        String slice = ddl.substring(st);
        Matcher m = DAMENG_QUALIFIABLE_HEADER.matcher(slice);
        if (!m.find()) {
            return ddl;
        }
        String nameChain = m.group(3).trim();
        List<String> parts = splitDamengDottedIdentifiers(nameChain);
        if (parts.isEmpty()) {
            return ddl;
        }
        String last = parts.get(parts.size() - 1);
        if (!last.equalsIgnoreCase(bu)) {
            return ddl;
        }
        if (parts.size() >= 2 && parts.get(0).equalsIgnoreCase(ou)) {
            return ddl;
        }
        // Keep object spelling from the DDL header (GET_DDL / ALL_SOURCE), not baseObjectName (may differ in case).
        String qualified = damengQuoteIdent(ou) + "." + last;
        int absStart = st + m.start(3);
        int absEnd = st + m.end(3);
        return ddl.substring(0, absStart) + qualified + ddl.substring(absEnd);
    }

    /**
     * Strip a trailing SQL*Plus {@code /} line, then ensure the unit ends with {@code ;}.
     * Does not append {@code /} (callers add a single {@code /} between exported units).
     */
    private static String ensureTrailingSemicolon(String ddl) {
        if (ddl == null || ddl.isBlank()) {
            return ddl;
        }
        String t = ddl.stripTrailing();
        t = t.replaceFirst("(?s)\\n\\s*/\\s*$", "").stripTrailing();
        if (t.isEmpty()) {
            return ddl;
        }
        if (!t.endsWith(";")) {
            t = t + ";";
        }
        return t;
    }

    /** Single-unit DDL for UI: ensure a SQL*Plus {@code /} line after the final {@code ;}. */
    private static String withTrailingSqlPlusSlash(String ddl) {
        if (ddl == null || ddl.isBlank()) {
            return ddl;
        }
        String t = ddl.stripTrailing();
        if (t.endsWith("/")) {
            return t;
        }
        return t + "\n/";
    }

    /**
     * {@code DBMS_METADATA.GET_DDL('TYPE_BODY', …)} sometimes emits the same {@code CREATE TYPE BODY} unit twice
     * (seen with MAP/ORDER member types). When the second copy is identical, keep one.
     */
    private static final Pattern DAMENG_TYPE_BODY_HEADER_START = Pattern.compile(
            "^\\s*create\\s+(?:or\\s+replace\\s+)?(?:editionable\\s+|editioning\\s+|noneditionable\\s+)*type\\s+body\\b",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private static String dedupeRepeatedDamengTypeBody(String ddl) {
        if (ddl == null || ddl.isBlank()) {
            return ddl;
        }
        Matcher m = DAMENG_TYPE_BODY_HEADER_START.matcher(ddl);
        if (!m.find()) {
            return ddl;
        }
        int firstStart = m.start();
        if (!m.find()) {
            return ddl;
        }
        int secondStart = m.start();
        String first = ddl.substring(firstStart, secondStart).stripTrailing();
        String second = ddl.substring(secondStart).stripTrailing();
        if (damengTypeBodyComparable(first).equals(damengTypeBodyComparable(second))) {
            if (firstStart > 0) {
                return ddl.substring(0, firstStart) + first;
            }
            return first;
        }
        return ddl;
    }

    private static String damengTypeBodyComparable(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("(?s)/\\*.*?\\*/", " ");
        t = t.replaceAll("(?m)^\\s*--[^\\n\\r]*", " ");
        return t.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /** Remove {@code ENABLE}/{@code DISABLE} immediately after {@code FOR EACH ROW} (same line or next line). */
    private static String stripForEachRowEnableDisable(String ddl) {
        if (ddl == null || ddl.isBlank()) {
            return ddl;
        }
        // Horizontal space only: do not treat the newline after ROW as part of same-line ENABLE/DISABLE.
        String s = ddl.replaceAll("(?i)(\\bfor\\s+each\\s+row)[ \t]+(enable|disable)\\b", "$1");
        return s.replaceAll("(?i)(\\bfor\\s+each\\s+row)([ \t]*\\R[ \t]*)(enable|disable)\\b\\s*", "$1$2");
    }

    private static final Pattern LAST_ALTER_TRIGGER = Pattern.compile("(?is)\\balter\\s+trigger\\b");
    private static final Pattern ALTER_TAIL_DISABLE = Pattern.compile("(?i)\\bdisable\\b");
    private static final Pattern FOR_EACH_ROW_TOKEN = Pattern.compile("(?i)\\bfor\\s+each\\s+row\\b(?!\\s+disable\\b)");

    /**
     * {@code GET_DDL('TRIGGER', …)} often appends {@code ALTER TRIGGER … ENABLE|DISABLE}. Drop that tail; if it was
     * {@code DISABLE}, fold state into {@code FOR EACH ROW DISABLE} on the create text.
     */
    private static String normalizeDamengTriggerAlterAndForEachRow(String ddl) {
        if (ddl == null || ddl.isBlank()) {
            return ddl;
        }
        Matcher alterM = LAST_ALTER_TRIGGER.matcher(ddl);
        int cut = -1;
        while (alterM.find()) {
            cut = alterM.start();
        }
        if (cut < 0) {
            return ddl;
        }
        String alterTail = ddl.substring(cut);
        boolean alterWasDisable = ALTER_TAIL_DISABLE.matcher(alterTail).find();
        ddl = ddl.substring(0, cut).stripTrailing();
        ddl = stripForEachRowEnableDisable(ddl);
        if (alterWasDisable) {
            Matcher rowM = FOR_EACH_ROW_TOKEN.matcher(ddl);
            if (rowM.find()) {
                // Same line: FOR EACH ROW DISABLE, then original newline / rest unchanged.
                ddl = ddl.substring(0, rowM.end()) + " DISABLE" + ddl.substring(rowM.end());
            }
        }
        return ddl;
    }

    /**
     * {@code ALL_SOURCE} (and some {@code GET_DDL} forms) may omit {@code CREATE OR REPLACE} before
     * {@code PACKAGE} / {@code PACKAGE BODY}; prepend so exported scripts are runnable. Preserves leading comments.
     */
    private static String ensureCreateOrReplacePackagePrefix(String ddl) {
        if (ddl == null || ddl.isBlank()) {
            return ddl;
        }
        int i = skipLeadingSqlCommentsAndWhitespace(ddl);
        String rest = ddl.substring(i);
        if (rest.isEmpty()) {
            return ddl;
        }
        if (startsWithIgnoreCase(rest, "CREATE ")) {
            return ddl;
        }
        if (startsWithIgnoreCase(rest, "ALTER ")) {
            return ddl;
        }
        if (startsWithIgnoreCase(rest, "PACKAGE BODY")) {
            return ddl.substring(0, i) + "CREATE OR REPLACE " + rest;
        }
        if (startsWithIgnoreCase(rest, "PACKAGE") && rest.length() > "PACKAGE".length()
                && Character.isWhitespace(rest.charAt("PACKAGE".length()))) {
            return ddl.substring(0, i) + "CREATE OR REPLACE " + rest;
        }
        return ddl;
    }

    /** {@code ALL_SOURCE} often starts at {@code PROCEDURE}/{@code FUNCTION} without {@code CREATE OR REPLACE}. */
    private static String ensureCreateOrReplaceRoutinePrefix(String ddl) {
        if (ddl == null || ddl.isBlank()) {
            return ddl;
        }
        int i = skipLeadingSqlCommentsAndWhitespace(ddl);
        String rest = ddl.substring(i);
        if (rest.isEmpty()) {
            return ddl;
        }
        if (startsWithIgnoreCase(rest, "CREATE ")) {
            return ddl;
        }
        if (startsWithIgnoreCase(rest, "ALTER ")) {
            return ddl;
        }
        if (startsWithIgnoreCase(rest, "PROCEDURE")
                && rest.length() > "PROCEDURE".length()
                && Character.isWhitespace(rest.charAt("PROCEDURE".length()))) {
            return ddl.substring(0, i) + "CREATE OR REPLACE " + rest;
        }
        if (startsWithIgnoreCase(rest, "FUNCTION")
                && rest.length() > "FUNCTION".length()
                && Character.isWhitespace(rest.charAt("FUNCTION".length()))) {
            return ddl.substring(0, i) + "CREATE OR REPLACE " + rest;
        }
        return ddl;
    }

    /**
     * Reconstruct PL/SQL text from the data dictionary when {@code GET_DDL} LOB handling returns a fragment.
     */
    private static String fetchPlSqlFromAllSource(Connection conn, String owner, String objectName, String allSourceType)
            throws SQLException {
        String sql = "SELECT text FROM all_source WHERE owner = ? AND name = ? AND type = ? ORDER BY line";
        StringBuilder sb = new StringBuilder();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(QUERY_TIMEOUT);
            ps.setString(1, owner);
            ps.setString(2, objectName);
            ps.setString(3, allSourceType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String line = rs.getString(1);
                    if (line != null) {
                        sb.append(line);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Dameng {@code GET_DDL} object types differ from Oracle ({@code PKG_SPEC} vs {@code PACKAGE_SPEC}). */
    private static String mapDamengGetDdlObjectType(String objectType) {
        if (objectType == null) {
            return null;
        }
        return switch (objectType) {
            case "PACKAGE_SPEC" -> "PKG_SPEC";
            case "PACKAGE_BODY" -> "PKG_BODY";
            default -> objectType;
        };
    }

    private String getDdl(Connection conn, String objectType, String objectName, String schema) throws SQLException {
        String damengObjectType = mapDamengGetDdlObjectType(objectType);
        String sql = "SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM dual";
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        String ddl = runner.queryOne(sql, List.of(damengObjectType, objectName, schema), rs -> readDdlClob(rs, 1));
        if (ddl != null) {
            ddl = ddl.trim();
        } else {
            ddl = "";
        }
        if ("PACKAGE_SPEC".equals(objectType) && !damengPlSqlLooksComplete(ddl)) {
            try {
                String fromDict = fetchPlSqlFromAllSource(conn, schema, objectName, "PACKAGE");
                if (!fromDict.isBlank()) {
                    log.debug("PACKAGE_SPEC GET_DDL fragment for {}.{}, using ALL_SOURCE", schema, objectName);
                    ddl = fromDict.trim();
                }
            } catch (SQLException e) {
                log.debug("ALL_SOURCE fallback for PACKAGE_SPEC {}.{}: {}", schema, objectName, e.getMessage());
            }
        } else if ("PACKAGE_BODY".equals(objectType) && !damengPlSqlLooksComplete(ddl)) {
            try {
                String fromDict = fetchPlSqlFromAllSource(conn, schema, objectName, "PACKAGE BODY");
                if (!fromDict.isBlank()) {
                    log.debug("PACKAGE_BODY GET_DDL fragment for {}.{}, using ALL_SOURCE", schema, objectName);
                    ddl = fromDict.trim();
                }
            } catch (SQLException e) {
                log.debug("ALL_SOURCE fallback for PACKAGE_BODY {}.{}: {}", schema, objectName, e.getMessage());
            }
        } else if ("PROCEDURE".equals(objectType) && !damengPlSqlLooksComplete(ddl)) {
            try {
                String fromDict = fetchPlSqlFromAllSource(conn, schema, objectName, "PROCEDURE");
                if (!fromDict.isBlank()) {
                    log.debug("PROCEDURE GET_DDL fragment for {}.{}, using ALL_SOURCE", schema, objectName);
                    ddl = fromDict.trim();
                }
            } catch (SQLException e) {
                log.debug("ALL_SOURCE fallback for PROCEDURE {}.{}: {}", schema, objectName, e.getMessage());
            }
        } else if ("FUNCTION".equals(objectType) && !damengPlSqlLooksComplete(ddl)) {
            try {
                String fromDict = fetchPlSqlFromAllSource(conn, schema, objectName, "FUNCTION");
                if (!fromDict.isBlank()) {
                    log.debug("FUNCTION GET_DDL fragment for {}.{}, using ALL_SOURCE", schema, objectName);
                    ddl = fromDict.trim();
                }
            } catch (SQLException e) {
                log.debug("ALL_SOURCE fallback for FUNCTION {}.{}: {}", schema, objectName, e.getMessage());
            }
        }
        if ("PACKAGE_SPEC".equals(objectType) || "PACKAGE_BODY".equals(objectType)) {
            ddl = ensureCreateOrReplacePackagePrefix(ddl);
            ddl = normalizePlSqlInternalWhitespace(ddl);
            ddl = collapseMetadataKeywordSpacing(ddl);
            ddl = qualifyOwnerInPlSqlHeader(ddl, schema, objectName);
            ddl = ensureTrailingSemicolon(ddl);
        } else if ("PROCEDURE".equals(objectType) || "FUNCTION".equals(objectType)) {
            ddl = ensureCreateOrReplaceRoutinePrefix(ddl);
            ddl = normalizePlSqlInternalWhitespace(ddl);
            ddl = collapseMetadataKeywordSpacing(ddl);
            ddl = qualifyOwnerInPlSqlHeader(ddl, schema, objectName);
            ddl = ensureTrailingSemicolon(ddl);
        } else if ("TRIGGER".equals(objectType)) {
            ddl = normalizeDamengTriggerAlterAndForEachRow(ddl);
            ddl = ensureTrailingSemicolon(ddl);
        } else if ("TYPE".equals(objectType) || "AQ_QUEUE".equals(objectType)) {
            ddl = normalizePlSqlInternalWhitespace(ddl);
            ddl = collapseMetadataKeywordSpacing(ddl);
            ddl = ensureTrailingSemicolon(ddl);
        } else if ("TYPE_BODY".equals(objectType)) {
            ddl = normalizePlSqlInternalWhitespace(ddl);
            ddl = collapseMetadataKeywordSpacing(ddl);
            ddl = dedupeRepeatedDamengTypeBody(ddl);
            ddl = ensureTrailingSemicolon(ddl);
        }
        return ddl;
    }

    private String getDdlSafe(Connection conn, String objectType, String objectName, String schema) {
        try {
            return getDdl(conn, objectType, objectName, schema);
        } catch (Exception e) {
            log.warn("Failed to get DDL for {} {}.{}: {}", objectType, schema, objectName, e.getMessage());
            return "-- ERROR: Failed to get DDL for " + objectType + " " + schema + "." + objectName
                    + "\n-- " + e.getMessage();
        }
    }

    private void configureMetadataTransform(Connection conn) throws SQLException {
        setTransformParam(conn, "STORAGE", false);
        setTransformParam(conn, "TABLESPACE", false);
        setTransformParam(conn, "SEGMENT_ATTRIBUTES", false);
        setTransformParam(conn, "SQLTERMINATOR", true);
    }

    /**
     * When {@code false}, {@code GET_DDL('TABLE', ...)} omits inline PK/UK/CK and FK clauses so data can be loaded first
     * (second file adds constraints and standalone indexes, similar to GBase export).
     */
    private void setEmbeddedTableConstraintsInMetadata(Connection conn, boolean include) throws SQLException {
        setTransformParam(conn, "CONSTRAINTS", include);
        setTransformParam(conn, "REF_CONSTRAINTS", include);
    }

    private void setTransformParam(Connection conn, String paramName, boolean value) throws SQLException {
        if (conn == null || paramName == null || paramName.isBlank()) {
            return;
        }
        String v = value ? "TRUE" : "FALSE";
        try (var stmt = conn.createStatement()) {
            stmt.execute("BEGIN DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'"
                    + paramName + "'," + v + "); END;");
        } catch (SQLException e) {
            if (isUnsupportedTransformParam(e)) {
                log.debug("Dameng DBMS_METADATA transform param skipped: {}", paramName, e);
                return;
            }
            throw e;
        }
    }

    private boolean isUnsupportedTransformParam(SQLException e) {
        if (e == null) {
            return false;
        }
        int code = e.getErrorCode();
        if (code == -20006 || code == 20006) {
            return true;
        }
        String message = e.getMessage();
        return message != null
                && (message.contains("非法的参数数据")
                || message.toUpperCase(Locale.ROOT).contains("SET_TRANSFORM_PARAM"));
    }

    @Override
    public String printTable(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        configureMetadataTransform(conn);
        return getDdl(conn, "TABLE", objectName.toUpperCase(), schema);
    }

    @Override
    public String printView(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        return getDdl(conn, "VIEW", objectName.toUpperCase(), schema);
    }

    @Override
    public String printIndex(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        configureMetadataTransform(conn);
        return getDdl(conn, "INDEX", objectName.toUpperCase(), schema);
    }

    @Override
    public String printSequence(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        return getDdl(conn, "SEQUENCE", objectName.toUpperCase(), schema);
    }

    @Override
    public String printSynonym(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        return getDdl(conn, "SYNONYM", objectName.toUpperCase(), schema);
    }

    @Override
    public String printFunction(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        configureMetadataTransform(conn);
        return withTrailingSqlPlusSlash(getDdl(conn, "FUNCTION", objectName, schema));
    }

    @Override
    public String printProcedure(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        configureMetadataTransform(conn);
        return withTrailingSqlPlusSlash(getDdl(conn, "PROCEDURE", objectName, schema));
    }

    @Override
    public String printTrigger(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        return withTrailingSqlPlusSlash(getDdl(conn, "TRIGGER", objectName.toUpperCase(), schema));
    }

    @Override
    public String printPackage(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        configureMetadataTransform(conn);
        String spec = getDdl(conn, "PACKAGE_SPEC", objectName, schema);
        String body;
        try {
            body = getDdl(conn, "PACKAGE_BODY", objectName, schema);
        } catch (Exception e) {
            body = "";
        }
        StringBuilder sb = new StringBuilder();
        if (!spec.isEmpty()) {
            sb.append(spec).append("\n/\n");
        }
        if (!body.isEmpty() && !body.startsWith("-- ERROR")) {
            sb.append(body).append("\n/\n");
        }
        return sb.toString().stripTrailing();
    }

    @Override
    public String printType(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        configureMetadataTransform(conn);
        String spec = getDdl(conn, "TYPE", objectName, schema);
        String body;
        
        
        StringBuilder sb = new StringBuilder();
        sb.append(spec).append("\n/\n");
        return sb.toString().stripTrailing();
    }

    @Override
    public String printQueue(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        configureMetadataTransform(conn);
        return withTrailingSqlPlusSlash(getDdl(conn, "AQ_QUEUE", objectName.toUpperCase(), schema));
    }

    @Override
    public String printSchedulerJob(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        configureMetadataTransform(conn);
        return withTrailingSqlPlusSlash(getDdl(conn, "PROCOBJ", objectName.toUpperCase(), schema));
    }

    @Override
    public long countDatabaseExportItems(Connection conn, String databaseName) throws SQLException {
        String schema = databaseName != null ? databaseName.toUpperCase() : currentSchema(conn).toUpperCase();
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        List<Object> binds = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            binds.add(schema);
        }
        Long count = runner.queryOne(SQL_EXPORT_ITEM_COUNT_SPLIT, binds, rs -> Long.valueOf(rs.getLong(1)));
        return count != null ? count : 0L;
    }

    @Override
    public DdlRepository.DatabaseDdlParts exportDatabaseDdlParts(Connection conn,
                                                                 String databaseName,
                                                                 LongConsumer progressCallback) throws SQLException {
        String schema = databaseName != null ? databaseName.toUpperCase() : currentSchema(conn).toUpperCase();
        configureMetadataTransform(conn);
        setEmbeddedTableConstraintsInMetadata(conn, true);

        StringBuilder pre = new StringBuilder();
        StringBuilder post = new StringBuilder();
        long completed = 0;

        pre.append("-- ### Phase 1: objects suitable before bulk data load\n\n");
        completed = appendObjectsDdl(conn, pre, schema, "SEQUENCE", "Sequences", completed, progressCallback);

        setEmbeddedTableConstraintsInMetadata(conn, false);
        completed = appendObjectsDdl(conn, pre, schema, "TABLE", "Tables (columns only, no inline constraints)", completed, progressCallback);
        setEmbeddedTableConstraintsInMetadata(conn, true);

        completed = appendObjectsDdl(conn, pre, schema, "VIEW", "Views", completed, progressCallback);
        completed = appendObjectsDdl(conn, pre, schema, "SYNONYM", "Synonyms", completed, progressCallback);
        completed = appendObjectsDdl(conn, pre, schema, "FUNCTION", "Functions", completed, progressCallback);
        completed = appendObjectsDdl(conn, pre, schema, "PROCEDURE", "Procedures", completed, progressCallback);
        completed = appendPackagesDdl(conn, pre, schema, completed, progressCallback);
        completed = appendSchedulerJobsDdl(conn, pre, schema, completed, progressCallback);
        appendTableComments(conn, pre, schema);
        appendColumnComments(conn, pre, schema);

        post.append("\n-- ### Phase 2: indexes, constraints, triggers (run after data load)\n\n");
        completed = appendStandaloneIndexesDdl(conn, post, schema, completed, progressCallback);
        completed = appendConstraintsDdl(conn, post, schema, completed, progressCallback);
        completed = appendObjectsDdl(conn, post, schema, "TRIGGER", "Triggers", completed, progressCallback);

        pre.append("-- ### END OF PHASE 1\n");
        post.append("-- ### END OF PHASE 2 / EXPORT\n");
        return new DdlRepository.DatabaseDdlParts(pre.toString(), post.toString());
    }

    @Override
    public String printDatabase(Connection conn, String databaseName) throws SQLException {
        return printDatabase(conn, databaseName, null);
    }

    @Override
    public String printDatabase(Connection conn, String databaseName, LongConsumer progressCallback) throws SQLException {
        DdlRepository.DatabaseDdlParts parts = exportDatabaseDdlParts(conn, databaseName, progressCallback);
        return parts.getPreDataSql() + parts.getPostDataSql();
    }

    private long appendStandaloneIndexesDdl(Connection conn, StringBuilder ddl, String schema,
                                            long completed, LongConsumer progressCallback) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        List<String> names = runner.query(SQL_STANDALONE_INDEX_NAMES, List.of(schema), rs -> rs.getString(1));
        if (names.isEmpty()) {
            return completed;
        }
        ddl.append("-- ### Indexes (standalone, excluding constraint-backed) (").append(names.size()).append(")\n\n");
        for (String name : names) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Export cancelled");
            }
            String objDdl = getDdlSafe(conn, "INDEX", name, schema);
            if (!objDdl.isEmpty()) {
                ddl.append(objDdl);
                if (!objDdl.endsWith(";")) {
                    ddl.append("\n;");
                }
                ddl.append("\n\n");
            }
            completed++;
            if (progressCallback != null) {
                progressCallback.accept(completed);
            }
        }
        ddl.append("-- ### FINISH: standalone indexes\n\n");
        return completed;
    }

    private long appendConstraintsDdl(Connection conn, StringBuilder ddl, String schema,
                                    long completed, LongConsumer progressCallback) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        List<String[]> pucRows = runner.query(SQL_CONSTRAINTS_PUC, List.of(schema),
                rs -> new String[]{rs.getString(1), rs.getString(2)});
        List<String> refRows = runner.query(SQL_CONSTRAINTS_R, List.of(schema), rs -> rs.getString(1));
        int n = pucRows.size() + refRows.size();
        if (n == 0) {
            return completed;
        }
        ddl.append("-- ### Constraints (").append(n).append(")\n\n");
        for (String[] row : pucRows) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Export cancelled");
            }
            String consName = row[0];
            String objDdl = getDdlSafe(conn, "CONSTRAINT", consName, schema);
            if (!objDdl.isEmpty()) {
                ddl.append(objDdl);
                if (!objDdl.endsWith(";")) {
                    ddl.append("\n;");
                }
                ddl.append("\n\n");
            }
            completed++;
            if (progressCallback != null) {
                progressCallback.accept(completed);
            }
        }
        ddl.append("-- ### Referential constraints (foreign keys)\n\n");
        for (String consName : refRows) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Export cancelled");
            }
            String objDdl = getDdlSafe(conn, "REF_CONSTRAINT", consName, schema);
            if (!objDdl.isEmpty()) {
                ddl.append(objDdl);
                if (!objDdl.endsWith(";")) {
                    ddl.append("\n;");
                }
                ddl.append("\n\n");
            }
            completed++;
            if (progressCallback != null) {
                progressCallback.accept(completed);
            }
        }
        ddl.append("-- ### FINISH: constraints\n\n");
        return completed;
    }

    private long appendObjectsDdl(Connection conn, StringBuilder ddl, String schema,
                                  String objectType, String label,
                                  long completed, LongConsumer progressCallback) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        List<String> names = runner.query(SQL_SCHEMA_OBJECTS, List.of(schema, objectType),
                rs -> rs.getString("object_name"));
        if (names.isEmpty()) {
            return completed;
        }

        ddl.append("-- ### ").append(label).append(" (").append(names.size()).append(")\n\n");
        boolean plsqlRoutine = "FUNCTION".equals(objectType) || "PROCEDURE".equals(objectType);
        for (String name : names) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Export cancelled");
            }
            String objDdl = getDdlSafe(conn, objectType, name, schema);
            if (!objDdl.isEmpty()) {
                ddl.append(objDdl);
                if (plsqlRoutine && !objDdl.startsWith("-- ERROR")) {
                    ddl.append("\n/\n");
                } else if (!plsqlRoutine) {
                    if (!objDdl.endsWith(";")) {
                        ddl.append("\n;");
                    }
                    ddl.append("\n");
                }
                ddl.append("\n");
            }
            completed++;
            if (progressCallback != null) {
                progressCallback.accept(completed);
            }
        }
        ddl.append("-- ### FINISH: ").append(label).append("\n\n");
        return completed;
    }

    private long appendQueuesDdl(Connection conn, StringBuilder ddl, String schema,
                                 long completed, LongConsumer progressCallback) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        List<String> names = runner.query(SQL_QUEUE_NAMES, List.of(schema), rs -> rs.getString("name"));
        if (names.isEmpty()) {
            return completed;
        }
        ddl.append("-- ### Queues (").append(names.size()).append(")\n\n");
        for (String name : names) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Export cancelled");
            }
            String objDdl = getDdlSafe(conn, "AQ_QUEUE", name, schema);
            if (!objDdl.isEmpty()) {
                ddl.append(objDdl);
                if (!objDdl.endsWith(";")) {
                    ddl.append("\n;");
                }
                ddl.append("\n\n");
            }
            completed++;
            if (progressCallback != null) {
                progressCallback.accept(completed);
            }
        }
        ddl.append("-- ### FINISH: Queues\n\n");
        return completed;
    }

    private long appendPackagesDdl(Connection conn, StringBuilder ddl, String schema,
                                   long completed, LongConsumer progressCallback) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        List<String> names = runner.query(SQL_SCHEMA_OBJECTS, List.of(schema, "PACKAGE"),
                rs -> rs.getString("object_name"));
        if (names.isEmpty()) {
            return completed;
        }

        ddl.append("-- ### Packages (").append(names.size()).append(")\n\n");
        for (String name : names) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Export cancelled");
            }
            String specDdl = getDdlSafe(conn, "PACKAGE_SPEC", name, schema);
            if (!specDdl.isEmpty()) {
                ddl.append(specDdl).append("\n/\n");
            }
            String bodyDdl = getDdlSafe(conn, "PACKAGE_BODY", name, schema);
            if (!bodyDdl.isEmpty() && !bodyDdl.startsWith("-- ERROR")) {
                ddl.append(bodyDdl).append("\n/\n");
            }
            ddl.append("\n");
            completed++;
            if (progressCallback != null) {
                progressCallback.accept(completed);
            }
        }
        ddl.append("-- ### FINISH: Packages\n\n");
        return completed;
    }

    private long appendSchedulerJobsDdl(Connection conn, StringBuilder ddl, String schema,
                                        long completed, LongConsumer progressCallback) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        List<String> names = runner.query(SQL_SCHEDULER_JOB_NAMES, List.of(schema), rs -> rs.getString("job_name"));
        if (names.isEmpty()) {
            return completed;
        }

        ddl.append("-- ### Scheduler Jobs (").append(names.size()).append(")\n\n");
        for (String name : names) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Export cancelled");
            }
            String objDdl = withTrailingSqlPlusSlash(getDdlSafe(conn, "PROCOBJ", name, schema));
            if (!objDdl.isEmpty()) {
                ddl.append(objDdl);
                ddl.append("\n\n");
            }
            completed++;
            if (progressCallback != null) {
                progressCallback.accept(completed);
            }
        }
        ddl.append("-- ### FINISH: Scheduler Jobs\n\n");
        return completed;
    }

    private void appendTableComments(Connection conn, StringBuilder ddl, String schema) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        List<String[]> comments = runner.query(SQL_TABLE_COMMENTS, List.of(schema),
                rs -> new String[]{rs.getString("table_name"), rs.getString("comments")});
        if (comments.isEmpty()) {
            return;
        }
        ddl.append("-- ### Table Comments\n\n");
        for (String[] row : comments) {
            String escapedComment = row[1].replace("'", "''");
            ddl.append("COMMENT ON TABLE \"").append(schema).append("\".\"").append(row[0])
                    .append("\" IS '").append(escapedComment).append("';\n");
        }
        ddl.append("\n");
    }

    private void appendColumnComments(Connection conn, StringBuilder ddl, String schema) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        List<String[]> comments = runner.query(SQL_COMMENTS, List.of(schema),
                rs -> new String[]{rs.getString("table_name"), rs.getString("column_name"), rs.getString("comments")});
        if (comments.isEmpty()) {
            return;
        }
        ddl.append("-- ### Column Comments\n\n");
        for (String[] row : comments) {
            String escapedComment = row[2].replace("'", "''");
            ddl.append("COMMENT ON COLUMN \"").append(schema).append("\".\"").append(row[0])
                    .append("\".\"").append(row[1]).append("\" IS '").append(escapedComment).append("';\n");
        }
        ddl.append("\n");
    }
}
