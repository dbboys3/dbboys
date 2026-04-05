package com.dbboys.impl.dialect.oracle;

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
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.LongConsumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class OracleDdlRepository implements DdlRepository {

    private static final Logger log = LogManager.getLogger(OracleDdlRepository.class);
    private static final int QUERY_TIMEOUT = 60;

    /**
     * {@code CREATE OR REPLACE … (PACKAGE [BODY]|PROCEDURE|FUNCTION) <name>} header; group 3 is the dotted name chain.
     */
    private static final Pattern ORACLE_QUALIFIABLE_HEADER = Pattern.compile(
            "(?is)^\\s*create\\s+or\\s+replace\\s+(?:editionable\\s+|editioning\\s+|noneditionable\\s+)*"
                    + "(?:\\s*(?:\\r\\n|[\\r\\n])\\s*)*"
                    + "(package\\s+body|package|procedure|function)(\\s+)"
                    + "((?:\\\"[^\\\"]+\\\"|[a-zA-Z][a-zA-Z0-9_$#]*)(?:\\s*\\.\\s*(?:\\\"[^\\\"]+\\\"|[a-zA-Z][a-zA-Z0-9_$#]*))*)"
                    + "(?=\\s*(?:\\(|is\\b|as\\b|;|[\\r\\n]|$))"
    );

    private static final String SQL_OBJECT_COUNT = """
            SELECT COUNT(*) FROM all_objects
            WHERE owner = ? AND object_type IN (
                'TABLE','VIEW','INDEX','SEQUENCE','SYNONYM',
                'FUNCTION','PROCEDURE','TRIGGER','PACKAGE'
            ) AND secondary = 'N'
            """;

    private static final String SQL_SCHEMA_OBJECTS = """
            SELECT object_name, object_type FROM all_objects
            WHERE owner = ? AND object_type = ? AND secondary = 'N'
            ORDER BY object_name
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
        String schema = conn.getSchema();
        if (schema != null && !schema.isBlank()) {
            return schema.toUpperCase();
        }
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        String result = runner.queryOne(
                "SELECT sys_context('USERENV','CURRENT_SCHEMA') FROM dual",
                null, rs -> rs.getString(1));
        return result != null ? result.toUpperCase() : "ORACLE";
    }

    private static final int CLOB_READ_CHUNK = 32_767;

    /**
     * Reads {@code DBMS_METADATA.GET_DDL} CLOB. Prefers {@link ResultSet#getClob(int)} (more reliable than
     * {@link ResultSet#getObject(int)} with some Oracle drivers), then reads in chunks to avoid single-call
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
    private static boolean oraclePlSqlLooksComplete(String ddl) {
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

    private static String oracleQuoteIdent(String ident) {
        if (ident == null) {
            return "\"\"";
        }
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private static List<String> splitOracleDottedIdentifiers(String chain) {
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
        Matcher m = ORACLE_QUALIFIABLE_HEADER.matcher(slice);
        if (!m.find()) {
            return ddl;
        }
        String nameChain = m.group(3).trim();
        List<String> parts = splitOracleDottedIdentifiers(nameChain);
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
        String qualified = oracleQuoteIdent(ou) + "." + bu;
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

    private String getDdl(Connection conn, String objectType, String objectName, String schema) throws SQLException {
        String sql = "SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM dual";
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        String ddl = runner.queryOne(sql, List.of(objectType, objectName, schema), rs -> readDdlClob(rs, 1));
        if (ddl != null) {
            ddl = ddl.trim();
        } else {
            ddl = "";
        }
        if ("PACKAGE_SPEC".equals(objectType) && !oraclePlSqlLooksComplete(ddl)) {
            try {
                String fromDict = fetchPlSqlFromAllSource(conn, schema, objectName, "PACKAGE");
                if (!fromDict.isBlank()) {
                    log.debug("PACKAGE_SPEC GET_DDL fragment for {}.{}, using ALL_SOURCE", schema, objectName);
                    ddl = fromDict.trim();
                }
            } catch (SQLException e) {
                log.debug("ALL_SOURCE fallback for PACKAGE_SPEC {}.{}: {}", schema, objectName, e.getMessage());
            }
        } else if ("PACKAGE_BODY".equals(objectType) && !oraclePlSqlLooksComplete(ddl)) {
            try {
                String fromDict = fetchPlSqlFromAllSource(conn, schema, objectName, "PACKAGE BODY");
                if (!fromDict.isBlank()) {
                    log.debug("PACKAGE_BODY GET_DDL fragment for {}.{}, using ALL_SOURCE", schema, objectName);
                    ddl = fromDict.trim();
                }
            } catch (SQLException e) {
                log.debug("ALL_SOURCE fallback for PACKAGE_BODY {}.{}: {}", schema, objectName, e.getMessage());
            }
        } else if ("PROCEDURE".equals(objectType) && !oraclePlSqlLooksComplete(ddl)) {
            try {
                String fromDict = fetchPlSqlFromAllSource(conn, schema, objectName, "PROCEDURE");
                if (!fromDict.isBlank()) {
                    log.debug("PROCEDURE GET_DDL fragment for {}.{}, using ALL_SOURCE", schema, objectName);
                    ddl = fromDict.trim();
                }
            } catch (SQLException e) {
                log.debug("ALL_SOURCE fallback for PROCEDURE {}.{}: {}", schema, objectName, e.getMessage());
            }
        } else if ("FUNCTION".equals(objectType) && !oraclePlSqlLooksComplete(ddl)) {
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
        try (var stmt = conn.createStatement()) {
            stmt.execute("BEGIN " +
                    "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'STORAGE',FALSE);" +
                    "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'TABLESPACE',FALSE);" +
                    "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'SEGMENT_ATTRIBUTES',FALSE);" +
                    "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'SQLTERMINATOR',TRUE);" +
                    "END;");
        }
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
        return getDdl(conn, "TRIGGER", objectName.toUpperCase(), schema);
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
    public long countDatabaseExportItems(Connection conn, String databaseName) throws SQLException {
        String schema = databaseName != null ? databaseName.toUpperCase() : currentSchema(conn);
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        Integer count = runner.queryOne(SQL_OBJECT_COUNT, List.of(schema), rs -> rs.getInt(1));
        return count != null ? count : 0;
    }

    @Override
    public String printDatabase(Connection conn, String databaseName) throws SQLException {
        return printDatabase(conn, databaseName, null);
    }

    @Override
    public String printDatabase(Connection conn, String databaseName, LongConsumer progressCallback) throws SQLException {
        String schema = databaseName != null ? databaseName.toUpperCase() : currentSchema(conn);
        configureMetadataTransform(conn);

        StringBuilder ddl = new StringBuilder();
        long completed = 0;

        completed = appendObjectsDdl(conn, ddl, schema, "SEQUENCE", "Sequences", completed, progressCallback);
        completed = appendObjectsDdl(conn, ddl, schema, "TABLE", "Tables", completed, progressCallback);
        completed = appendObjectsDdl(conn, ddl, schema, "INDEX", "Indexes", completed, progressCallback);
        completed = appendObjectsDdl(conn, ddl, schema, "VIEW", "Views", completed, progressCallback);
        completed = appendObjectsDdl(conn, ddl, schema, "SYNONYM", "Synonyms", completed, progressCallback);
        completed = appendObjectsDdl(conn, ddl, schema, "FUNCTION", "Functions", completed, progressCallback);
        completed = appendObjectsDdl(conn, ddl, schema, "PROCEDURE", "Procedures", completed, progressCallback);
        completed = appendPackagesDdl(conn, ddl, schema, completed, progressCallback);
        completed = appendObjectsDdl(conn, ddl, schema, "TRIGGER", "Triggers", completed, progressCallback);

        appendTableComments(conn, ddl, schema);
        appendColumnComments(conn, ddl, schema);

        ddl.append("-- ### END OF EXPORT\n");
        return ddl.toString();
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
