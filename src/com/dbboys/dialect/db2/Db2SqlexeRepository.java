package com.dbboys.dialect.db2;

import com.dbboys.core.SqlErrorPosition;
import com.dbboys.core.SqlErrorPositionCapability;
import com.dbboys.core.SqlexeRepository;
import com.dbboys.dialect.common.ErrorPositionSupport;
import com.dbboys.infra.i18n.I18n;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Db2SqlexeRepository implements SqlexeRepository, SqlErrorPositionCapability {

    private final Db2MetadataRepository metadataRepository;

    public Db2SqlexeRepository(Db2MetadataRepository metadataRepository) {
        this.metadataRepository = metadataRepository;
    }

    @Override
    public SqlErrorPosition locateErrorPosition(Connection conn, SQLException error, String sentSql, String errorMessage) {
        return ErrorPositionSupport.locateDb2(sentSql, errorMessage);
    }

    @Override
    public void setDatabase(Connection conn, String databaseName) throws SQLException {
        metadataRepository.setDatabase(conn, databaseName);
    }

    @Override
    public boolean autoCommitsDdl() {
        return false;
    }

    /**
     * DB2 has no plan text function like DBMS_XPLAN: EXPLAIN PLAN FOR only
     * populates the EXPLAIN_* tables, so the operator tree is rebuilt from
     * EXPLAIN_OPERATOR/EXPLAIN_STREAM/EXPLAIN_OBJECT.
     */
    @Override
    public String explain(Connection conn, String sql) throws SQLException {
        if (conn == null || sql == null || sql.isBlank()) {
            return null;
        }
        String normalizedSql = normalizeExplainSql(sql);
        if (normalizedSql.isBlank()) {
            return null;
        }

        try {
            try (var stmt = conn.createStatement()) {
                stmt.execute("EXPLAIN PLAN FOR " + normalizedSql);
            }

            PlanStatement statement = loadPlanStatement(conn);
            if (statement == null) {
                return null;
            }
            OperatorLoad operatorLoad = loadPlanOperators(conn, statement);
            if (operatorLoad.operators().isEmpty()) {
                return null;
            }
            Map<Integer, PlanOperator> operators = operatorLoad.operators();
            Map<Integer, String> objects = loadOperatorObjects(conn, statement);
            Map<Integer, List<PlanStream>> children = loadPlanStreams(conn, statement);

            StringBuilder text = new StringBuilder();
            if (statement.totalCost() == null) {
                text.append(String.format(Locale.ROOT, "Total cost: N/A%n"));
            } else {
                text.append(String.format(Locale.ROOT, "Total cost: %.2f timerons%n", statement.totalCost()));
            }
            if (operatorLoad.identityAvailable() && !children.isEmpty()) {
                Integer root = findRootOperator(operators, children);
                if (root != null) {
                    renderOperator(text, root, operators, objects, children, "", true, true);
                    return text.toString().stripTrailing();
                }
            }
            renderFlatOperators(text, operators, objects);
            return text.toString().stripTrailing();
        } catch (SQLException e) {
            throw translateExplainFailure(e);
        }
    }

    /**
     * LUW has no SET QUERYNO (z/OS only), so the just-created plan is the one
     * with the newest EXPLAIN_TIME for this user. Do not depend on
     * STATEMENT_NO here: some installed EXPLAIN table versions do not expose
     * that column, while the UI explains exactly one statement at a time.
     */
    private PlanStatement loadPlanStatement(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT * FROM EXPLAIN_STATEMENT"
                        + " WHERE EXPLAIN_REQUESTER = CURRENT USER"
                        + " ORDER BY EXPLAIN_TIME DESC FETCH FIRST 1 ROWS ONLY")) {
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                ResultSetMetaData metadata = rs.getMetaData();
                int timeColumn = findColumn(metadata, "EXPLAIN_TIME");
                if (timeColumn < 0) {
                    throw new SQLException("EXPLAIN_STATEMENT is missing EXPLAIN_TIME");
                }
                return new PlanStatement(
                        rs.getTimestamp(timeColumn),
                        readNullableDouble(rs, findColumn(metadata, "TOTAL_COST")));
            }
        }
    }

    /**
     * EXPLAIN table columns vary across DB2 releases. Read the returned
     * metadata instead of hard-coding optional fields such as CARDINALITY or
     * even the operator key; older or partially upgraded installations can
     * still be rendered as a flat operator list.
     */
    private OperatorLoad loadPlanOperators(Connection conn, PlanStatement statement) throws SQLException {
        Map<Integer, PlanOperator> operators = new HashMap<>();
        boolean identityAvailable = true;
        int nextSyntheticId = 1;
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT * FROM EXPLAIN_OPERATOR"
                        + " WHERE EXPLAIN_REQUESTER = CURRENT USER AND EXPLAIN_TIME = ?")) {
            stmt.setTimestamp(1, statement.explainTime());
            try (ResultSet rs = stmt.executeQuery()) {
                ResultSetMetaData metadata = rs.getMetaData();
                int idColumn = findColumn(metadata, "OPERATOR_ID");
                int typeColumn = findColumn(metadata, "OPERATOR_TYPE");
                int costColumn = findColumn(metadata, "TOTAL_COST");
                int cardinalityColumn = findColumn(metadata, "CARDINALITY");
                while (rs.next()) {
                    Integer operatorId = readNullableInt(rs, idColumn);
                    if (operatorId == null) {
                        identityAvailable = false;
                        operatorId = -nextSyntheticId++;
                    }
                    String type = readString(rs, typeColumn);
                    operators.put(operatorId, new PlanOperator(
                            type == null ? "UNKNOWN" : type,
                            readNullableDouble(rs, costColumn),
                            readNullableDouble(rs, cardinalityColumn)));
                }
            }
        }
        return new OperatorLoad(operators, identityAvailable);
    }

    private Map<Integer, String> loadOperatorObjects(Connection conn, PlanStatement statement) throws SQLException {
        Map<Integer, String> objects = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT * FROM EXPLAIN_OBJECT"
                        + " WHERE EXPLAIN_REQUESTER = CURRENT USER AND EXPLAIN_TIME = ?")) {
            stmt.setTimestamp(1, statement.explainTime());
            try (ResultSet rs = stmt.executeQuery()) {
                ResultSetMetaData metadata = rs.getMetaData();
                int idColumn = findColumn(metadata, "OPERATOR_ID");
                int schemaColumn = findColumn(metadata, "OBJECT_SCHEMA");
                int nameColumn = findColumn(metadata, "OBJECT_NAME");
                if (idColumn < 0 || schemaColumn < 0 || nameColumn < 0) {
                    return objects;
                }
                while (rs.next()) {
                    String name = qualifiedName(
                            rs.getString(schemaColumn), rs.getString(nameColumn));
                    if (!name.isEmpty()) {
                        objects.put(rs.getInt(idColumn), name);
                    }
                }
            }
        }
        return objects;
    }

    /** Streams point from producer (SOURCE_ID) to consumer (TARGET_ID): children of an operator. */
    private Map<Integer, List<PlanStream>> loadPlanStreams(Connection conn, PlanStatement statement) throws SQLException {
        Map<Integer, List<PlanStream>> children = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT * FROM EXPLAIN_STREAM"
                        + " WHERE EXPLAIN_REQUESTER = CURRENT USER AND EXPLAIN_TIME = ?")) {
            stmt.setTimestamp(1, statement.explainTime());
            try (ResultSet rs = stmt.executeQuery()) {
                ResultSetMetaData metadata = rs.getMetaData();
                int targetColumn = findColumn(metadata, "TARGET_ID");
                int sourceColumn = findColumn(metadata, "SOURCE_ID");
                int streamColumn = findColumn(metadata, "STREAM_ID");
                if (targetColumn < 0 || sourceColumn < 0 || streamColumn < 0) {
                    return children;
                }
                while (rs.next()) {
                    children.computeIfAbsent(rs.getInt(targetColumn), k -> new ArrayList<>())
                            .add(new PlanStream(
                                    rs.getInt(streamColumn), rs.getInt(sourceColumn)));
                }
            }
        }
        for (List<PlanStream> streams : children.values()) {
            streams.sort((a, b) -> Integer.compare(a.streamId(), b.streamId()));
        }
        return children;
    }

    /** The root operator (RETURN) produces no stream, so it is never a SOURCE_ID. */
    private static Integer findRootOperator(Map<Integer, PlanOperator> operators,
                                            Map<Integer, List<PlanStream>> children) {
        java.util.Set<Integer> sources = new java.util.HashSet<>();
        for (List<PlanStream> streams : children.values()) {
            for (PlanStream stream : streams) {
                sources.add(stream.sourceId());
            }
        }
        for (Integer id : operators.keySet()) {
            if (!sources.contains(id)) {
                return id;
            }
        }
        return operators.keySet().stream().min(Integer::compareTo).orElse(null);
    }

    private static void renderOperator(StringBuilder text, int operatorId,
                                       Map<Integer, PlanOperator> operators,
                                       Map<Integer, String> objects,
                                       Map<Integer, List<PlanStream>> children,
                                       String prefix, boolean isLast, boolean isRoot) {
        PlanOperator operator = operators.get(operatorId);
        if (operator == null) {
            return;
        }
        text.append(prefix);
        if (!isRoot) {
            text.append(isLast ? "└─ " : "├─ ");
        }
        text.append(operator.type());
        String object = objects.get(operatorId);
        if (object != null && !object.isBlank()) {
            text.append(' ').append(object);
        }
        appendOperatorMetrics(text, operator);
        List<PlanStream> streams = children.getOrDefault(operatorId, List.of());
        String childPrefix = isRoot ? "" : prefix + (isLast ? "   " : "│  ");
        for (int i = 0; i < streams.size(); i++) {
            renderOperator(text, streams.get(i).sourceId(), operators, objects, children,
                    childPrefix, i == streams.size() - 1, false);
        }
    }

    private static void renderFlatOperators(StringBuilder text,
                                            Map<Integer, PlanOperator> operators,
                                            Map<Integer, String> objects) {
        List<Map.Entry<Integer, PlanOperator>> entries = new ArrayList<>(operators.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Integer, PlanOperator> entry : entries) {
            PlanOperator operator = entry.getValue();
            text.append("├─ ").append(operator.type());
            String object = objects.get(entry.getKey());
            if (object != null && !object.isBlank()) {
                text.append(' ').append(object);
            }
            appendOperatorMetrics(text, operator);
        }
    }

    private static void appendOperatorMetrics(StringBuilder text, PlanOperator operator) {
        if (operator.cost() == null && operator.cardinality() == null) {
            text.append(String.format(Locale.ROOT, "%n"));
            return;
        }
        text.append("  (");
        if (operator.cost() != null) {
            text.append(String.format(Locale.ROOT, "cost=%.2f", operator.cost()));
        }
        if (operator.cardinality() != null) {
            if (operator.cost() != null) {
                text.append(", ");
            }
            text.append(String.format(Locale.ROOT, "card=%.0f", operator.cardinality()));
        }
        text.append(String.format(Locale.ROOT, ")%n"));
    }

    private static int findColumn(ResultSetMetaData metadata, String columnName) throws SQLException {
        if (metadata == null || columnName == null) {
            return -1;
        }
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            if (columnName.equalsIgnoreCase(metadata.getColumnName(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String readString(ResultSet rs, int column) throws SQLException {
        if (column < 0) {
            return null;
        }
        String value = rs.getString(column);
        return value == null ? null : value.trim();
    }

    private static Integer readNullableInt(ResultSet rs, int column) throws SQLException {
        if (column < 0) {
            return null;
        }
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Double readNullableDouble(ResultSet rs, int column) throws SQLException {
        if (column < 0) {
            return null;
        }
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static String qualifiedName(String schema, String name) {
        String objectName = name == null ? "" : name.trim();
        String objectSchema = schema == null ? "" : schema.trim();
        if (objectName.isEmpty()) {
            return "";
        }
        return objectSchema.isEmpty() ? objectName : objectSchema + "." + objectName;
    }

    /**
     * Missing explain tables surface as SQL0219N, or SQL0204N when only some of
     * the EXPLAIN_* tables exist (partial creation). A -204 raised by the
     * explained SQL itself (bad table/column name) must pass through unchanged,
     * so only translate when the message names an EXPLAIN_* table.
     */
    private static SQLException translateExplainFailure(SQLException e) {
        if (e.getErrorCode() == -219
                || (e.getErrorCode() == -204 && mentionsExplainTable(e.getMessage()))) {
            return new SQLException(I18n.t("sql.explain.db2.missing_tables"), e.getSQLState(), e.getErrorCode(), e);
        }
        return e;
    }

    private static boolean mentionsExplainTable(String message) {
        return message != null && EXPLAIN_TABLE_PATTERN.matcher(message).find();
    }

    private static final java.util.regex.Pattern EXPLAIN_TABLE_PATTERN = java.util.regex.Pattern.compile(
            "(?i)\\bEXPLAIN_(INSTANCE|STATEMENT|ARGUMENT|OBJECT|OPERATOR|PREDICATE|STREAM|DIAGNOSTIC)\\b");

    private static String normalizeExplainSql(String sql) {
        String normalized = sql == null ? "" : sql.trim();
        while (normalized.endsWith(";") || normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private record PlanStatement(Timestamp explainTime, Double totalCost) {
    }

    private record PlanOperator(String type, Double cost, Double cardinality) {
    }

    private record OperatorLoad(Map<Integer, PlanOperator> operators, boolean identityAvailable) {
    }

    private record PlanStream(int streamId, int sourceId) {
    }
}
