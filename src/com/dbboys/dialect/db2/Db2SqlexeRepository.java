package com.dbboys.dialect.db2;

import com.dbboys.core.SqlErrorPosition;
import com.dbboys.core.SqlErrorPositionCapability;
import com.dbboys.core.SqlexeRepository;
import com.dbboys.dialect.common.ErrorPositionSupport;
import com.dbboys.infra.i18n.I18n;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
            Map<Integer, PlanOperator> operators = loadPlanOperators(conn, statement);
            if (operators.isEmpty()) {
                return null;
            }
            Map<Integer, String> objects = loadOperatorObjects(conn, statement);
            Map<Integer, List<PlanStream>> children = loadPlanStreams(conn, statement);

            StringBuilder text = new StringBuilder();
            text.append(String.format(Locale.ROOT, "Total cost: %.2f timerons%n", statement.totalCost()));
            Integer root = findRootOperator(operators, children);
            if (root == null) {
                return null;
            }
            renderOperator(text, root, operators, objects, children, "", true, true);
            return text.toString().stripTrailing();
        } catch (SQLException e) {
            throw translateExplainFailure(e);
        }
    }

    /**
     * LUW has no SET QUERYNO (z/OS only), so the just-created plan is the one
     * with the newest EXPLAIN_TIME for this user.
     */
    private PlanStatement loadPlanStatement(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT EXPLAIN_TIME, STATEMENT_NO, TOTAL_COST FROM EXPLAIN_STATEMENT"
                        + " WHERE EXPLAIN_REQUESTER = CURRENT USER"
                        + " ORDER BY EXPLAIN_TIME DESC FETCH FIRST 1 ROWS ONLY")) {
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new PlanStatement(rs.getTimestamp(1), rs.getInt(2), rs.getDouble(3));
            }
        }
    }

    private Map<Integer, PlanOperator> loadPlanOperators(Connection conn, PlanStatement statement) throws SQLException {
        Map<Integer, PlanOperator> operators = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT OPERATOR_ID, OPERATOR_TYPE, TOTAL_COST, CARDINALITY FROM EXPLAIN_OPERATOR"
                        + " WHERE EXPLAIN_REQUESTER = CURRENT USER AND EXPLAIN_TIME = ? AND STATEMENT_NO = ?")) {
            stmt.setTimestamp(1, statement.explainTime());
            stmt.setInt(2, statement.statementNo());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    operators.put(rs.getInt(1),
                            new PlanOperator(rs.getString(2).trim(), rs.getDouble(3), rs.getDouble(4)));
                }
            }
        }
        return operators;
    }

    private Map<Integer, String> loadOperatorObjects(Connection conn, PlanStatement statement) throws SQLException {
        Map<Integer, String> objects = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT OPERATOR_ID, OBJECT_SCHEMA, OBJECT_NAME FROM EXPLAIN_OBJECT"
                        + " WHERE EXPLAIN_REQUESTER = CURRENT USER AND EXPLAIN_TIME = ?")) {
            stmt.setTimestamp(1, statement.explainTime());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String name = qualifiedName(rs.getString(2), rs.getString(3));
                    if (!name.isEmpty()) {
                        objects.put(rs.getInt(1), name);
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
                "SELECT TARGET_ID, SOURCE_ID, STREAM_ID FROM EXPLAIN_STREAM"
                        + " WHERE EXPLAIN_REQUESTER = CURRENT USER AND EXPLAIN_TIME = ?")) {
            stmt.setTimestamp(1, statement.explainTime());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    children.computeIfAbsent(rs.getInt(1), k -> new ArrayList<>())
                            .add(new PlanStream(rs.getInt(3), rs.getInt(2)));
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
        text.append(String.format(Locale.ROOT, "  (cost=%.2f, card=%.0f)%n", operator.cost(), operator.cardinality()));
        List<PlanStream> streams = children.getOrDefault(operatorId, List.of());
        String childPrefix = isRoot ? "" : prefix + (isLast ? "   " : "│  ");
        for (int i = 0; i < streams.size(); i++) {
            renderOperator(text, streams.get(i).sourceId(), operators, objects, children,
                    childPrefix, i == streams.size() - 1, false);
        }
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

    private record PlanStatement(Timestamp explainTime, int statementNo, double totalCost) {
    }

    private record PlanOperator(String type, double cost, double cardinality) {
    }

    private record PlanStream(int streamId, int sourceId) {
    }
}
