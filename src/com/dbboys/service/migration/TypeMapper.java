package com.dbboys.service.migration;

import com.dbboys.model.ColumnsInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 跨数据库表结构迁移的类型映射器（best-effort 语义）。
 *
 * <p>使用方式：先调用 {@link #normalize(String, ColumnsInfo)} 把源平台的列类型
 * 归一到 {@link GenericType}，再调用 {@link #toTargetType(String, GenericType, ColumnsInfo, List)}
 * 生成目标平台的列类型 DDL 片段；或直接调用
 * {@link #buildCreateTableScript(String, String, List, List, String, List)}
 * 生成目标方言的完整建表脚本。</p>
 *
 * <p>映射以兼容性优先：能精确映射就精确映射，无法精确映射时选择目标平台上
 * 语义最接近的兜底类型，并向调用方传入的 warnings 列表追加一条中文说明，
 * 不抛异常。warnings 风格为一句完整中文，含目标平台、列名与处理方式，
 * 例如「目标平台 ORACLE 不支持 VARCHAR 长度 5000（&gt;4000），列 name 已改为 CLOB」。</p>
 *
 * <p>注意：{@link #buildCreateTableScript} 不感知源平台（契约中没有源平台参数），
 * 其内部归一化按源平台未知处理（如 Oracle DATE 会按普通 DATE 处理）。
 * 若调用方需要精确的源平台语义，请自行先 normalize 再逐列 toTargetType。</p>
 */
public final class TypeMapper {

    /** 归一化后的通用列类型。 */
    public enum GenericType {
        TINYINT, SMALLINT, INTEGER, BIGINT, DECIMAL, FLOAT, DOUBLE,
        CHAR, VARCHAR, TEXT, CLOB, DATE, TIME, DATETIME, TIMESTAMP,
        BINARY, BLOB, BOOLEAN, JSON, OTHER
    }

    private TypeMapper() {
    }

    // ------------------------------------------------------------------
    // 平台字面量判断（与各方言 DB_TYPE 常量一致，忽略大小写）
    // ------------------------------------------------------------------

    private static boolean is(String dbType, String expected) {
        return dbType != null && dbType.equalsIgnoreCase(expected);
    }

    /** Oracle 系：ORACLE / DAMENG（NUMBER、VARCHAR2、DATE 含时间等语义一致）。 */
    private static boolean isOracleFamily(String dbType) {
        return is(dbType, "ORACLE") || is(dbType, "DAMENG");
    }

    /** Informix 系：GBASE 8S / INFORMIX（LVARCHAR、DATETIME 限定词、BYTE/TEXT 一致）。 */
    private static boolean isGbaseFamily(String dbType) {
        return is(dbType, "GBASE 8S") || is(dbType, "INFORMIX");
    }

    // ------------------------------------------------------------------
    // normalize：源平台类型 -> GenericType
    // ------------------------------------------------------------------

    /**
     * 把源平台的列类型归一到 GenericType（结合 colType/colLength/typeP/typeS）。
     * 类型名大小写不敏感，忽略 (n)/(p,s) 后缀及 UNSIGNED 等修饰词；
     * Informix 的 "DATETIME YEAR TO FRACTION(3)" 这类限定词也能识别。
     *
     * @param sourceDbType 源平台 dbtype 字面量（ORACLE/MYSQL/...），可为 null（按未知平台处理）
     * @param column       源列元数据
     */
    public static GenericType normalize(String sourceDbType, ColumnsInfo column) {
        String raw = column == null ? null : column.getColType();
        if (raw == null || raw.trim().isEmpty()) {
            return GenericType.OTHER;
        }
        String t = raw.trim().toUpperCase(Locale.ROOT);
        // 去掉 (n) / (p,s) 后缀
        int paren = t.indexOf('(');
        if (paren >= 0) {
            t = t.substring(0, paren).trim();
        }
        // 去掉 MySQL 的 UNSIGNED / ZEROFILL 修饰
        t = t.replace(" UNSIGNED", "").replace(" ZEROFILL", "").trim();

        // 带限定词的类型先按前缀判断（如 DATETIME YEAR TO SECOND、TIMESTAMP WITH TIME ZONE）
        if (t.startsWith("DATETIME")) {
            return GenericType.DATETIME;
        }
        if (t.startsWith("TIMESTAMP")) {
            return GenericType.TIMESTAMP;
        }
        if (t.startsWith("TIME")) {
            return GenericType.TIME; // 含 TIME WITH TIME ZONE / TIMETZ
        }
        if (t.startsWith("INTERVAL")) {
            return GenericType.OTHER;
        }
        // 多词类型名
        if (t.equals("CHARACTER VARYING")) {
            return GenericType.VARCHAR;
        }
        if (t.equals("DOUBLE PRECISION")) {
            return GenericType.DOUBLE;
        }

        int p = column.getTypeP();
        int s = column.getTypeS();

        switch (t) {
            // ---- 整型 ----
            case "TINYINT":
                return GenericType.TINYINT;
            case "SMALLINT":
            case "INT2":
            case "YEAR":
                return GenericType.SMALLINT;
            case "INT":
            case "INTEGER":
            case "INT4":
            case "MEDIUMINT":
            case "SERIAL":
                return GenericType.INTEGER;
            case "BIGINT":
            case "INT8":
            case "BIGSERIAL":
            case "SERIAL8":
                return GenericType.BIGINT;
            case "NUMBER":
                // Oracle/Dameng NUMBER：按精度/小数位推断整数级别
                if (s > 0) {
                    return GenericType.DECIMAL;
                }
                if (p > 0 && p <= 4) {
                    return GenericType.SMALLINT;
                }
                if (p > 0 && p <= 9) {
                    return GenericType.INTEGER;
                }
                if (p > 0 && p <= 18) {
                    return GenericType.BIGINT;
                }
                return GenericType.DECIMAL; // 更大或精度未知(0)时按 DECIMAL 兜底
            case "DECIMAL":
            case "NUMERIC":
            case "MONEY":
                return GenericType.DECIMAL;
            // ---- 浮点 ----
            case "REAL":
            case "SMALLFLOAT":
            case "FLOAT4":
            case "BINARY_FLOAT":
                return GenericType.FLOAT;
            case "FLOAT":
            case "DOUBLE":
            case "FLOAT8":
            case "BINARY_DOUBLE":
                return GenericType.DOUBLE;
            // ---- 字符 ----
            case "CHAR":
            case "NCHAR":
            case "BPCHAR":
            case "CHARACTER":
                return GenericType.CHAR;
            case "VARCHAR":
            case "VARCHAR2":
            case "NVARCHAR":
            case "NVARCHAR2":
            case "LVARCHAR":
                return GenericType.VARCHAR;
            case "TEXT": // 含 Informix TEXT
            case "TINYTEXT":
            case "MEDIUMTEXT":
            case "LONGTEXT":
                return GenericType.TEXT;
            case "CLOB":
            case "NCLOB":
            case "LONG":
                return GenericType.CLOB;
            // ---- 日期时间 ----
            case "DATE":
                // Oracle/Dameng 的 DATE 含时分秒，归一为 DATETIME
                return isOracleFamily(sourceDbType) ? GenericType.DATETIME : GenericType.DATE;
            // ---- 二进制 ----
            case "BINARY":
            case "VARBINARY":
            case "RAW":
                return GenericType.BINARY;
            case "BLOB":
            case "BYTEA":
            case "BFILE":
            case "TINYBLOB":
            case "MEDIUMBLOB":
            case "LONGBLOB":
            case "BYTE": // Informix BYTE 大对象
                return GenericType.BLOB;
            // ---- 布尔 ----
            case "BIT":
                // MySQL BIT(n) n>1 是位串而非布尔
                if (is(sourceDbType, "MYSQL") && column.getColLength() > 1) {
                    return GenericType.BINARY;
                }
                return GenericType.BOOLEAN;
            case "BOOLEAN":
            case "BOOL":
                return GenericType.BOOLEAN;
            // ---- JSON ----
            case "JSON":
            case "JSONB":
                return GenericType.JSON;
            default:
                return GenericType.OTHER; // ENUM、SET、数组等
        }
    }

    // ------------------------------------------------------------------
    // toTargetType：GenericType -> 目标平台类型 DDL 片段
    // ------------------------------------------------------------------

    /** 取列名用于 warning 文案，空则返回占位符。 */
    private static String colName(ColumnsInfo column) {
        String name = column == null ? null : column.getColName();
        return (name == null || name.isEmpty()) ? "?" : name;
    }

    /**
     * 全局类型覆盖匹配：取列的源类型基名在 globalTypeOverrides 中大小写不敏感查找，
     * 先全串匹配（如 Informix 的 DATETIME YEAR TO SECOND），不匹配再退到第一个词。
     * 命中返回 trim 后的目标类型文本，未命中或覆盖值为空白返回 null。
     */
    private static String globalOverrideFor(Map<String, String> globalTypeOverrides, String colType) {
        if (globalTypeOverrides == null || globalTypeOverrides.isEmpty()) {
            return null;
        }
        String base = baseTypeName(colType);
        if (base.isEmpty()) {
            return null;
        }
        String hit = getIgnoreCase(globalTypeOverrides, base);
        if (hit == null) {
            int space = base.indexOf(' ');
            if (space > 0) {
                hit = getIgnoreCase(globalTypeOverrides, base.substring(0, space));
            }
        }
        return hit == null || hit.isBlank() ? null : hit.trim();
    }

    /** 源类型基名：大写、去 {@code (...)} 括号段、去 UNSIGNED/ZEROFILL/IDENTITY 等修饰词、trim。 */
    private static String baseTypeName(String colType) {
        if (colType == null) {
            return "";
        }
        String base = colType.trim().toUpperCase(Locale.ROOT);
        int paren = base.indexOf('(');
        if (paren >= 0) {
            base = base.substring(0, paren).trim();
        }
        StringBuilder sb = new StringBuilder();
        for (String word : base.split("\\s+")) {
            if (word.equals("UNSIGNED") || word.equals("ZEROFILL")
                    || word.equals("IDENTITY") || word.equals("AUTO_INCREMENT")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(word);
        }
        return sb.toString();
    }

    /** Map 按键大小写不敏感取值。 */
    private static String getIgnoreCase(Map<String, String> map, String key) {
        String exact = map.get(key);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 字符/二进制列的有效长度：优先 typeP，其次 colLength。 */
    private static int charLen(ColumnsInfo column) {
        int p = column == null ? 0 : column.getTypeP();
        return p > 0 ? p : (column == null ? 0 : column.getColLength());
    }

    /**
     * 生成目标平台的列类型 DDL 片段（含长度/精度）。
     * 无法精确映射时给出兜底类型并向 warnings 追加中文说明。
     *
     * @param targetDbType 目标平台 dbtype 字面量（ORACLE/MYSQL/...，忽略大小写）
     * @param type         归一化后的通用类型
     * @param column       源列元数据（取长度/精度用）
     * @param warnings     收集中文映射警告，可为空列表
     */
    public static String toTargetType(String targetDbType, GenericType type, ColumnsInfo column,
            List<String> warnings) {
        int n = charLen(column);
        int p = column == null ? 0 : column.getTypeP();
        int s = column == null ? 0 : column.getTypeS();
        String col = colName(column);
        String target = targetDbType == null ? "" : targetDbType.toUpperCase(Locale.ROOT);

        if (isOracleFamily(targetDbType)) {
            switch (type) {
                case TINYINT:
                    return "NUMBER(3)";
                case SMALLINT:
                    return "NUMBER(5)";
                case INTEGER:
                    return "NUMBER(10)";
                case BIGINT:
                    return "NUMBER(19)";
                case DECIMAL:
                    if (p > 0 && s > 0) {
                        return "NUMBER(" + p + "," + s + ")";
                    }
                    if (p > 0) {
                        return "NUMBER(" + p + ")";
                    }
                    return "NUMBER"; // 无精度时不限定
                case FLOAT:
                    return "BINARY_FLOAT";
                case DOUBLE:
                    return "BINARY_DOUBLE";
                case CHAR:
                    return "CHAR(" + (n > 0 ? n : 1) + ")";
                case VARCHAR:
                    if (n <= 0) {
                        return "VARCHAR2(255)";
                    }
                    if (n > 4000) {
                        warnings.add("目标平台 " + target + " 不支持 VARCHAR 长度 " + n
                                + "（>4000），列 " + col + " 已改为 CLOB");
                        return "CLOB";
                    }
                    return "VARCHAR2(" + n + ")";
                case TEXT:
                case CLOB:
                    return "CLOB";
                case JSON:
                    warnings.add("目标平台 " + target + " 无原生 JSON 类型，列 " + col + " 已改为 CLOB");
                    return "CLOB";
                case DATE:
                    return "DATE";
                case TIME:
                    warnings.add("目标平台 " + target + " 无独立 TIME 类型，列 " + col
                            + " 已改为 VARCHAR2(8)（HH24:MI:SS 文本）");
                    return "VARCHAR2(8)";
                case DATETIME:
                    // Oracle DATE 含时分秒，可覆盖 DATETIME；带小数秒的源列也可改用 TIMESTAMP
                    return "DATE";
                case TIMESTAMP:
                    return "TIMESTAMP";
                case BINARY:
                    if (n > 0 && n <= 2000) {
                        return "RAW(" + n + ")";
                    }
                    warnings.add("目标平台 " + target + " 的 RAW 最长 2000 字节，列 " + col
                            + " 已改为 BLOB");
                    return "BLOB";
                case BLOB:
                    return "BLOB";
                case BOOLEAN:
                    warnings.add("目标平台 " + target + " 无原生布尔类型，列 " + col
                            + " 已改为 NUMBER(1)（1/0）");
                    return "NUMBER(1)";
                default:
                    warnings.add("列 " + col + " 的源类型无法映射到 " + target
                            + "，已兜底为 VARCHAR2(255)");
                    return "VARCHAR2(255)";
            }
        }

        if (is(targetDbType, "MYSQL")) {
            switch (type) {
                case TINYINT:
                    return "TINYINT";
                case SMALLINT:
                    return "SMALLINT";
                case INTEGER:
                    return "INT";
                case BIGINT:
                    return "BIGINT";
                case DECIMAL:
                    if (p > 0) {
                        return "DECIMAL(" + p + "," + Math.max(s, 0) + ")";
                    }
                    return "DECIMAL";
                case FLOAT:
                    return "FLOAT";
                case DOUBLE:
                    return "DOUBLE";
                case CHAR:
                    return "CHAR(" + (n > 0 ? n : 1) + ")";
                case VARCHAR:
                    if (n <= 0) {
                        return "VARCHAR(255)";
                    }
                    if (n > 16383) {
                        warnings.add("目标平台 MYSQL 的 VARCHAR 长度 " + n
                                + " 超限（>16383），列 " + col + " 已改为 TEXT");
                        return "TEXT";
                    }
                    return "VARCHAR(" + n + ")";
                case TEXT:
                    return "TEXT";
                case CLOB:
                    return "LONGTEXT";
                case DATE:
                    return "DATE";
                case TIME:
                    return "TIME";
                case DATETIME:
                    return "DATETIME";
                case TIMESTAMP:
                    return "TIMESTAMP";
                case BINARY:
                    if (n <= 0) {
                        return "VARBINARY(255)";
                    }
                    if (n > 65535) {
                        warnings.add("目标平台 MYSQL 的 VARBINARY 长度 " + n
                                + " 超限（>65535），列 " + col + " 已改为 BLOB");
                        return "BLOB";
                    }
                    return "VARBINARY(" + n + ")";
                case BLOB:
                    return "LONGBLOB";
                case BOOLEAN:
                    return "TINYINT(1)";
                case JSON:
                    return "JSON";
                default:
                    warnings.add("列 " + col + " 的源类型无法映射到 MYSQL，已兜底为 VARCHAR(255)");
                    return "VARCHAR(255)";
            }
        }

        if (is(targetDbType, "POSTGRESQL")) {
            switch (type) {
                case TINYINT: // PG 无 TINYINT，SMALLINT 完全覆盖其范围
                case SMALLINT:
                    return "SMALLINT";
                case INTEGER:
                    return "INTEGER";
                case BIGINT:
                    return "BIGINT";
                case DECIMAL:
                    if (p > 0) {
                        return "NUMERIC(" + p + "," + Math.max(s, 0) + ")";
                    }
                    return "NUMERIC";
                case FLOAT:
                    return "REAL";
                case DOUBLE:
                    return "DOUBLE PRECISION";
                case CHAR:
                    return "CHAR(" + (n > 0 ? n : 1) + ")";
                case VARCHAR:
                    if (n <= 0 || n > 10485760) {
                        return "TEXT"; // PG 的 TEXT 无长度限制，直接兼容
                    }
                    return "VARCHAR(" + n + ")";
                case TEXT:
                case CLOB:
                    return "TEXT";
                case DATE:
                    return "DATE";
                case TIME:
                    return "TIME";
                case DATETIME:
                case TIMESTAMP:
                    return "TIMESTAMP";
                case BINARY:
                case BLOB:
                    return "BYTEA";
                case BOOLEAN:
                    return "BOOLEAN";
                case JSON:
                    return "JSON";
                default:
                    warnings.add("列 " + col + " 的源类型无法映射到 POSTGRESQL，已兜底为 TEXT");
                    return "TEXT";
            }
        }

        if (isGbaseFamily(targetDbType)) {
            switch (type) {
                case TINYINT: // Informix 系无 TINYINT
                case SMALLINT:
                    return "SMALLINT";
                case INTEGER:
                    return "INTEGER";
                case BIGINT:
                    return "BIGINT";
                case DECIMAL:
                    if (p > 0 && s > 0) {
                        return "DECIMAL(" + p + "," + s + ")";
                    }
                    if (p > 0) {
                        return "DECIMAL(" + p + ")";
                    }
                    return "DECIMAL";
                case FLOAT:
                    return "SMALLFLOAT";
                case DOUBLE:
                    return "FLOAT";
                case CHAR:
                    return "CHAR(" + (n > 0 ? n : 1) + ")";
                case VARCHAR:
                    if (n <= 0) {
                        return "VARCHAR(255)";
                    }
                    if (n > 32739) {
                        warnings.add("目标平台 " + target + " 的 LVARCHAR 长度 " + n
                                + " 超限（>32739），列 " + col + " 已改为 TEXT");
                        return "TEXT";
                    }
                    if (n > 255) {
                        return "LVARCHAR(" + n + ")";
                    }
                    return "VARCHAR(" + n + ")";
                case TEXT:
                case CLOB:
                    return "TEXT";
                case DATE:
                    return "DATE";
                case TIME:
                    warnings.add("目标平台 " + target + " 无独立 TIME 类型，列 " + col
                            + " 已改为 DATETIME HOUR TO SECOND");
                    return "DATETIME HOUR TO SECOND";
                case DATETIME:
                    return "DATETIME YEAR TO SECOND";
                case TIMESTAMP:
                    return "DATETIME YEAR TO FRACTION(3)";
                case BINARY:
                case BLOB:
                    return "BYTE";
                case BOOLEAN:
                    return "BOOLEAN";
                case JSON:
                    warnings.add("目标平台 " + target + " 无原生 JSON 类型，列 " + col + " 已改为 TEXT");
                    return "TEXT";
                default:
                    warnings.add("列 " + col + " 的源类型无法映射到 " + target
                            + "，已兜底为 VARCHAR(255)");
                    return "VARCHAR(255)";
            }
        }

        if (is(targetDbType, "SQLITE")) {
            // SQLite 采用动态类型，只按亲和性归并
            switch (type) {
                case TINYINT:
                case SMALLINT:
                case INTEGER:
                case BIGINT:
                    return "INTEGER";
                case DECIMAL:
                case FLOAT:
                case DOUBLE:
                    return "REAL";
                case BINARY:
                case BLOB:
                    return "BLOB";
                default:
                    return "TEXT";
            }
        }

        // GENERAL JDBC 或未知目标：ANSI 近似，不确定项加 warning
        switch (type) {
            case TINYINT:
                warnings.add("目标平台 " + (target.isEmpty() ? "?" : target)
                        + " 按 ANSI 近似映射，列 " + col + " 的 TINYINT 已改为 SMALLINT");
                return "SMALLINT";
            case SMALLINT:
                return "SMALLINT";
            case INTEGER:
                return "INTEGER";
            case BIGINT:
                return "BIGINT";
            case DECIMAL:
                if (p > 0) {
                    return "DECIMAL(" + p + "," + Math.max(s, 0) + ")";
                }
                return "DECIMAL";
            case FLOAT:
                return "FLOAT";
            case DOUBLE:
                return "DOUBLE PRECISION";
            case CHAR:
                return "CHAR(" + (n > 0 ? n : 1) + ")";
            case VARCHAR:
                return "VARCHAR(" + (n > 0 ? n : 255) + ")";
            case TEXT:
            case CLOB:
                return "CLOB";
            case DATE:
                return "DATE";
            case TIME:
                return "TIME";
            case DATETIME:
                warnings.add("目标平台 " + (target.isEmpty() ? "?" : target)
                        + " 按 ANSI 近似映射，列 " + col + " 的 DATETIME 已改为 TIMESTAMP");
                return "TIMESTAMP";
            case TIMESTAMP:
                return "TIMESTAMP";
            case BINARY:
                warnings.add("目标平台 " + (target.isEmpty() ? "?" : target)
                        + " 按 ANSI 近似映射，列 " + col + " 的二进制类型已改为 BLOB");
                return "BLOB";
            case BLOB:
                return "BLOB";
            case BOOLEAN:
                warnings.add("目标平台 " + (target.isEmpty() ? "?" : target)
                        + " 按 ANSI 近似映射，列 " + col + " 使用 BOOLEAN，具体支持取决于驱动");
                return "BOOLEAN";
            case JSON:
                warnings.add("目标平台 " + (target.isEmpty() ? "?" : target)
                        + " 按 ANSI 近似映射，列 " + col + " 的 JSON 已改为 CLOB");
                return "CLOB";
            default:
                warnings.add("列 " + col + " 的源类型无法映射，已兜底为 VARCHAR(255)");
                return "VARCHAR(255)";
        }
    }

    // ------------------------------------------------------------------
    // buildCreateTableScript：生成目标方言建表脚本
    // ------------------------------------------------------------------

    /** 转义 SQL 字符串字面量中的单引号。 */
    private static String escapeQuotes(String text) {
        return text.replace("'", "''");
    }

    /**
     * 处理列默认值：只保留简单字面量（纯数字、单引号字符串、NULL、TRUE/FALSE、
     * CURRENT_TIMESTAMP/CURRENT_DATE/SYSDATE），其余丢弃并加中文 warning。
     *
     * @return 可直接拼进 DDL 的字面量文本，或 null 表示不输出 DEFAULT 子句
     */
    private static String mapDefault(String def, String targetDbType, String col,
            List<String> warnings) {
        if (def == null) {
            return null;
        }
        String d = def.trim();
        if (d.isEmpty()) {
            return null;
        }
        // 纯数字（含负数、小数）
        if (d.matches("-?\\d+(\\.\\d+)?")) {
            return d;
        }
        // 单引号字符串字面量：源元数据里已是合法 SQL 字面量（内部 '' 已转义），原样保留
        if (d.length() >= 2 && d.startsWith("'") && d.endsWith("'")) {
            return d;
        }
        String upper = d.toUpperCase(Locale.ROOT);
        switch (upper) {
            case "NULL":
                return "NULL";
            case "TRUE":
            case "FALSE":
                // 仅 PostgreSQL 原生支持布尔字面量，其余目标映射为 1/0
                if (is(targetDbType, "POSTGRESQL")) {
                    return upper;
                }
                return "TRUE".equals(upper) ? "1" : "0";
            case "CURRENT_TIMESTAMP":
            case "CURRENT_DATE":
                return upper;
            case "SYSDATE":
                // SYSDATE 仅 Oracle 系可用，其他目标改为 CURRENT_TIMESTAMP
                return isOracleFamily(targetDbType) ? "SYSDATE" : "CURRENT_TIMESTAMP";
            default:
                warnings.add("列 " + col + " 的默认值 '" + def + "' 不是简单字面量，已丢弃");
                return null;
        }
    }

    /**
     * 生成目标方言建表脚本：CREATE TABLE（列、NOT NULL、简单字面量默认值、自增、
     * 内联 PRIMARY KEY）+ 按目标方言语法的表/列注释语句。
     * 多条语句用 ";\n" 分隔、以 ";" 结尾；不支持的特性跳过并向 warnings 追加中文说明。
     *
     * <p>表名/列名按元数据原样输出，不加引号。本方法不感知源平台，
     * 列类型按源平台未知做归一化（见类 Javadoc）。</p>
     *
     * @param targetDbType      目标平台 dbtype 字面量
     * @param tableName         目标表名
     * @param columns           列元数据列表
     * @param primaryKeyColumns 主键列名列表，可为 null/空
     * @param tableComment      表注释，可为 null/空
     * @param warnings          收集中文警告
     */
    public static String buildCreateTableScript(String targetDbType, String tableName,
            List<ColumnsInfo> columns, List<String> primaryKeyColumns, String tableComment,
            List<String> warnings) {
        return buildCreateTableScript(null, targetDbType, tableName, columns, primaryKeyColumns,
                tableComment, warnings);
    }

    /**
     * 源平台感知版本：与 {@link #buildCreateTableScript(String, String, List, List, String, List)}
     * 相同，但列类型归一化使用源平台语义（例如 Oracle/Dameng 的 DATE 含时分秒，
     * 归一为 DATETIME 而非 DATE，避免迁移到仅有日期语义的类型时丢失时间部分）。
     *
     * @param sourceDbType      源平台 dbtype 字面量，可为 null（等同不感知源平台）
     */
    public static String buildCreateTableScript(String sourceDbType, String targetDbType,
            String tableName, List<ColumnsInfo> columns, List<String> primaryKeyColumns,
            String tableComment, List<String> warnings) {
        return buildCreateTableScript(sourceDbType, targetDbType, tableName, columns,
                primaryKeyColumns, tableComment, warnings, null);
    }

    /**
     * 源平台感知 + 自定义数据映射版本：{@code mapping} 非空时，
     * 排除列不生成（同时从主键剔除，主键变空则不生成 PRIMARY KEY）；
     * 覆盖列的目标类型文本原样使用（跳过归一化/类型映射，并向 warnings 追加中文说明）。
     * 排除后无任何列时抛 {@link IllegalArgumentException}（"all columns excluded"）。
     *
     * @param mapping 自定义数据映射，可为 null（等同无映射）
     */
    public static String buildCreateTableScript(String sourceDbType, String targetDbType,
            String tableName, List<ColumnsInfo> columns, List<String> primaryKeyColumns,
            String tableComment, List<String> warnings, TableMapping mapping) {
        return buildCreateTableScript(sourceDbType, targetDbType, tableName, columns,
                primaryKeyColumns, tableComment, warnings, mapping, null);
    }

    /**
     * 源平台感知 + 逐表映射 + 全局类型映射版本。逐列决定类型文本的优先级：
     * per-table 覆盖（{@code mapping.overrideType}）&gt; 全局类型覆盖
     * （{@code globalTypeOverrides}，按源类型基名大小写不敏感匹配）&gt; 默认归一化/映射。
     * 全局命中同样跳过归一化/类型映射（含 SERIAL 替换），warnings 追加
     * 「列 x 使用全局自定义目标类型 …」。
     *
     * @param globalTypeOverrides 全局类型映射（源类型基名 → 目标类型文本），可为 null/空
     */
    public static String buildCreateTableScript(String sourceDbType, String targetDbType,
            String tableName, List<ColumnsInfo> columns, List<String> primaryKeyColumns,
            String tableComment, List<String> warnings, TableMapping mapping,
            Map<String, String> globalTypeOverrides) {
        boolean mysql = is(targetDbType, "MYSQL");
        boolean pg = is(targetDbType, "POSTGRESQL");
        boolean oracleFamily = isOracleFamily(targetDbType);
        boolean gbaseFamily = isGbaseFamily(targetDbType);
        boolean sqlite = is(targetDbType, "SQLITE");
        boolean ansi = !(mysql || pg || oracleFamily || gbaseFamily || sqlite);
        if (ansi && !is(targetDbType, "GENERAL JDBC")) {
            warnings.add("未知目标平台 " + targetDbType + "，按 ANSI 近似语法生成建表脚本");
        }

        // 应用列级排除；同时从主键列表剔除被排除的列
        if (mapping != null && !mapping.isEmpty()) {
            List<ColumnsInfo> kept = new ArrayList<>();
            for (ColumnsInfo column : columns) {
                if (!mapping.isExcluded(colName(column))) {
                    kept.add(column);
                }
            }
            if (kept.isEmpty()) {
                throw new IllegalArgumentException("all columns excluded");
            }
            columns = kept;
            if (primaryKeyColumns != null) {
                List<String> keptPk = new ArrayList<>();
                for (String pkColumn : primaryKeyColumns) {
                    if (!mapping.isExcluded(pkColumn)) {
                        keptPk.add(pkColumn);
                    }
                }
                primaryKeyColumns = keptPk;
            }
        }

        List<String> lines = new ArrayList<>();
        List<ColumnsInfo> commentedColumns = new ArrayList<>();
        for (ColumnsInfo column : columns) {
            String col = colName(column);
            GenericType gt = normalize(sourceDbType, column);
            boolean auto = column.isIsAutoincrement();
            String override = mapping == null ? null : mapping.overrideType(col);
            String globalOverride = override == null || override.isBlank()
                    ? globalOverrideFor(globalTypeOverrides, column.getColType())
                    : null;

            // 自增列的类型替换（PG / Informix 系用 SERIAL 系列类型表达自增）
            String typeSql;
            boolean autoHandledByType = false;
            if (override != null && !override.isBlank()) {
                // 逐表自定义目标类型：原样使用，跳过归一化/类型映射（含 SERIAL 替换）
                typeSql = override.trim();
                warnings.add("列 " + col + " 使用自定义目标类型 " + typeSql);
            } else if (globalOverride != null) {
                // 全局自定义目标类型：原样使用，跳过归一化/类型映射（含 SERIAL 替换）
                typeSql = globalOverride;
                warnings.add("列 " + col + " 使用全局自定义目标类型 " + typeSql);
            } else if (auto && (pg || gbaseFamily)) {
                if (gt == GenericType.INTEGER) {
                    typeSql = "SERIAL";
                    autoHandledByType = true;
                } else if (gt == GenericType.BIGINT) {
                    typeSql = "BIGSERIAL";
                    autoHandledByType = true;
                } else {
                    warnings.add("列 " + col + " 标记为自增但不是整型，"
                            + (pg ? "POSTGRESQL" : targetDbType.toUpperCase(Locale.ROOT))
                            + " 仅整型支持 SERIAL，已按普通列处理");
                    typeSql = toTargetType(targetDbType, gt, column, warnings);
                }
            } else {
                typeSql = toTargetType(targetDbType, gt, column, warnings);
            }

            StringBuilder line = new StringBuilder("  " + col + " " + typeSql);
            if (!column.isIsNullable()) {
                line.append(" NOT NULL");
            }
            String def = mapDefault(column.getColDef(), targetDbType, col, warnings);
            if (def != null) {
                line.append(" DEFAULT ").append(def);
            }

            // 自增子句（类型替换已处理的除外）
            if (auto && !autoHandledByType) {
                if (mysql) {
                    line.append(" AUTO_INCREMENT");
                } else if (oracleFamily) {
                    // Oracle 12c+ 标准写法；Dameng 也可用 IDENTITY(1,1)，此处统一用前者
                    line.append(" GENERATED BY DEFAULT ON NULL AS IDENTITY");
                } else if (sqlite) {
                    warnings.add("列 " + col + " 标记为自增，但 SQLITE 仅 INTEGER PRIMARY KEY 列"
                            + " 支持自增，已跳过自增设置");
                } else {
                    warnings.add("列 " + col + " 标记为自增，目标平台 "
                            + (targetDbType == null ? "?" : targetDbType.toUpperCase(Locale.ROOT))
                            + " 不支持/不适用自增子句，已跳过");
                }
            }

            // MySQL 列注释内联
            if (mysql && column.getColComm() != null && !column.getColComm().isEmpty()) {
                line.append(" COMMENT '").append(escapeQuotes(column.getColComm())).append("'");
            }
            if (column.getColComm() != null && !column.getColComm().isEmpty()) {
                commentedColumns.add(column);
            }
            lines.add(line.toString());
        }

        // 内联主键
        if (primaryKeyColumns != null && !primaryKeyColumns.isEmpty()) {
            lines.add("  PRIMARY KEY (" + String.join(", ", primaryKeyColumns) + ")");
        }

        StringBuilder create = new StringBuilder();
        create.append("CREATE TABLE ").append(tableName).append(" (\n");
        create.append(String.join(",\n", lines));
        create.append("\n)");
        // MySQL 表注释跟在表选项里
        if (mysql && tableComment != null && !tableComment.isEmpty()) {
            create.append(" COMMENT='").append(escapeQuotes(tableComment)).append("'");
        }

        List<String> statements = new ArrayList<>();
        statements.add(create.toString());

        boolean hasTableComment = tableComment != null && !tableComment.isEmpty();
        boolean hasAnyComment = hasTableComment || !commentedColumns.isEmpty();
        if (hasAnyComment) {
            if (oracleFamily || pg) {
                // Oracle/Dameng/PostgreSQL：独立 COMMENT ON 语句
                if (hasTableComment) {
                    statements.add("COMMENT ON TABLE " + tableName + " IS '"
                            + escapeQuotes(tableComment) + "'");
                }
                for (ColumnsInfo c : commentedColumns) {
                    statements.add("COMMENT ON COLUMN " + tableName + "." + c.getColName()
                            + " IS '" + escapeQuotes(c.getColComm()) + "'");
                }
            } else if (!mysql) {
                // GBASE 8S / INFORMIX / SQLITE / GENERAL JDBC 不支持注释语法
                warnings.add("目标平台 "
                        + (targetDbType == null ? "?" : targetDbType.toUpperCase(Locale.ROOT))
                        + " 不支持表/列注释语法，注释已跳过");
            }
        }

        // 多语句用 ";\n" 分隔，整体以 ";" 结尾
        return String.join(";\n", statements) + ";";
    }
}
