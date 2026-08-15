package com.dbboys.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 迁移对象引用：定位源端一个待迁移对象，或某个库/模式节点下的全部对象（通配）。
 *
 * <p>持久化为 JSON 数组存于 t_migration_task.c_objects，单个对象格式：
 * <pre>{"catalog":"...","schema":"...","kind":"TABLE","name":"...","where":"...","parent":"..."}</pre>
 * {@code kind=ALL} 时 name 为空，表示该 catalog(/schema) 节点下全部支持的对象，
 * 运行时展开（能捡到保存后新建的表）；{@code kind != ALL && name == null} 表示
 * 类型级通配（该节点下此类型的全部对象），同样运行时展开。
 * {@code where} 仅 TABLE 用（数据过滤条件）；{@code parent} 仅 INDEX/FOREIGN_KEY 用（宿主表名，
 * DROP/DDL 生成需要，MySQL 的 DROP INDEX 与外键的 ALTER TABLE 都离不开宿主表）。</p>
 */
public record MigrationObjectRef(String catalog, String schema, Kind kind, String name, String where,
                                 String parent) {

    public enum Kind { TABLE, VIEW, SEQUENCE, SYNONYM, TRIGGER, FUNCTION, PROCEDURE, PACKAGE, INDEX, FOREIGN_KEY, ALL }

    public MigrationObjectRef {
        catalog = normalize(catalog);
        schema = normalize(schema);
        name = normalize(name);
        where = normalize(where);
        parent = normalize(parent);
    }

    /** 兼容旧调用：无 WHERE 条件、无宿主表。 */
    public MigrationObjectRef(String catalog, String schema, Kind kind, String name) {
        this(catalog, schema, kind, name, null, null);
    }

    /** 兼容旧调用：无宿主表。 */
    public MigrationObjectRef(String catalog, String schema, Kind kind, String name, String where) {
        this(catalog, schema, kind, name, where, null);
    }

    /** 整节点通配：catalog(/schema) 下全部支持的对象。 */
    public static MigrationObjectRef wildcard(String catalog, String schema) {
        return new MigrationObjectRef(catalog, schema, Kind.ALL, null);
    }

    /** 类型级通配：catalog(/schema) 下指定类型的全部对象。 */
    public static MigrationObjectRef kindWildcard(String catalog, String schema, Kind kind) {
        if (kind == null || kind == Kind.ALL) {
            return wildcard(catalog, schema);
        }
        return new MigrationObjectRef(catalog, schema, kind, null);
    }

    public boolean isWildcard() {
        return kind == Kind.ALL;
    }

    /** 是否通配条目（整节点或类型级），需要运行前展开成显式对象。 */
    public boolean needsExpansion() {
        return name == null;
    }

    /** 树/日志展示用：schema.name 或 catalog.name。 */
    public String displayName() {
        StringBuilder sb = new StringBuilder();
        if (catalog != null) {
            sb.append(catalog);
        }
        if (schema != null) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(schema);
        }
        if (name != null) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(name);
        }
        return sb.toString();
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        if (catalog != null) {
            obj.put("catalog", catalog);
        }
        if (schema != null) {
            obj.put("schema", schema);
        }
        obj.put("kind", kind.name());
        if (name != null) {
            obj.put("name", name);
        }
        if (where != null) {
            obj.put("where", where);
        }
        if (parent != null) {
            obj.put("parent", parent);
        }
        return obj;
    }

    public static MigrationObjectRef fromJson(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        Kind kind;
        try {
            kind = Kind.valueOf(obj.optString("kind", "TABLE").trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        return new MigrationObjectRef(
                obj.has("catalog") ? obj.optString("catalog", null) : null,
                obj.has("schema") ? obj.optString("schema", null) : null,
                kind,
                obj.has("name") ? obj.optString("name", null) : null,
                obj.has("where") ? obj.optString("where", null) : null,
                obj.has("parent") ? obj.optString("parent", null) : null);
    }

    public static String toJsonArray(List<MigrationObjectRef> refs) {
        JSONArray array = new JSONArray();
        if (refs != null) {
            for (MigrationObjectRef ref : refs) {
                if (ref != null) {
                    array.put(ref.toJson());
                }
            }
        }
        return array.toString();
    }

    /** 宽松解析：坏条目跳过，整体异常返回空列表。 */
    public static List<MigrationObjectRef> parseJsonArray(String json) {
        List<MigrationObjectRef> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                MigrationObjectRef ref = fromJson(array.optJSONObject(i));
                if (ref != null) {
                    result.add(ref);
                }
            }
        } catch (Exception ignored) {
            // 容忍损坏数据，返回已解析部分
        }
        return result;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
