package com.dbboys.service.migration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 单表的自定义数据映射：迁移时排除的列 + 目标类型覆盖。
 *
 * <p>任务级 JSON（存 t_migration_task.c_mappings）形如：
 * <pre>{"t1":{"exclude":["c1"],"types":{"c2":"VARCHAR(200)"}}}</pre>
 * 键为源表名（大小写不敏感匹配）。映射仅对 TABLE 对象生效：
 * 建表脚本排除列不生成、覆盖列直接使用给定目标类型；数据复制跳过排除列。</p>
 */
public record TableMapping(Set<String> excludedColumns, Map<String, String> typeOverrides) {

    /** 全局类型映射在任务 mappings 中的保留表名键：其 typeOverrides 对所有表生效。 */
    public static final String GLOBAL_TABLE_KEY = "*";

    public TableMapping {
        // 排除列统一归一小写，保证 isExcluded 的大小写不敏感语义对直接构造同样成立
        if (excludedColumns == null) {
            excludedColumns = Set.of();
        } else {
            Set<String> normalized = new LinkedHashSet<>();
            for (String column : excludedColumns) {
                if (column != null && !column.isBlank()) {
                    normalized.add(column.trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
            excludedColumns = Set.copyOf(normalized);
        }
        typeOverrides = typeOverrides == null ? Map.of() : Map.copyOf(typeOverrides);
    }

    public boolean isEmpty() {
        return excludedColumns.isEmpty() && typeOverrides.isEmpty();
    }

    public boolean isExcluded(String columnName) {
        return columnName != null && excludedColumns.contains(columnName.toLowerCase(java.util.Locale.ROOT));
    }

    /** 列名大小写不敏感取覆盖类型，无覆盖返回 null。 */
    public String overrideType(String columnName) {
        if (columnName == null) {
            return null;
        }
        String exact = typeOverrides.get(columnName);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> entry : typeOverrides.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(columnName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    // ---- 任务级 JSON 编解码（键=表名） ----

    public static String toJson(Map<String, TableMapping> mappings) {
        JSONObject root = new JSONObject();
        if (mappings != null) {
            for (Map.Entry<String, TableMapping> entry : mappings.entrySet()) {
                TableMapping mapping = entry.getValue();
                if (entry.getKey() == null || mapping == null || mapping.isEmpty()) {
                    continue;
                }
                JSONObject obj = new JSONObject();
                if (!mapping.excludedColumns().isEmpty()) {
                    obj.put("exclude", new JSONArray(mapping.excludedColumns()));
                }
                if (!mapping.typeOverrides().isEmpty()) {
                    obj.put("types", new JSONObject(mapping.typeOverrides()));
                }
                root.put(entry.getKey(), obj);
            }
        }
        return root.toString();
    }

    /** 宽松解析：坏条目跳过，整体异常返回空 Map。 */
    public static Map<String, TableMapping> fromJson(String json) {
        Map<String, TableMapping> result = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            JSONObject root = new JSONObject(json);
            for (String table : root.keySet()) {
                JSONObject obj = root.optJSONObject(table);
                if (obj == null) {
                    continue;
                }
                Set<String> exclude = new LinkedHashSet<>();
                JSONArray excludeArray = obj.optJSONArray("exclude");
                if (excludeArray != null) {
                    for (int i = 0; i < excludeArray.length(); i++) {
                        String col = excludeArray.optString(i, null);
                        if (col != null && !col.isBlank()) {
                            exclude.add(col.trim().toLowerCase(java.util.Locale.ROOT));
                        }
                    }
                }
                Map<String, String> types = new LinkedHashMap<>();
                JSONObject typesObj = obj.optJSONObject("types");
                if (typesObj != null) {
                    for (String col : typesObj.keySet()) {
                        String type = typesObj.optString(col, null);
                        if (type != null && !type.isBlank()) {
                            types.put(col, type.trim());
                        }
                    }
                }
                result.put(table, new TableMapping(exclude, types));
            }
        } catch (Exception ignored) {
            // 容忍损坏数据，返回已解析部分
        }
        return result;
    }

    /** 表名大小写不敏感取映射，无则返回 null。 */
    public static TableMapping forTable(Map<String, TableMapping> mappings, String tableName) {
        if (mappings == null || tableName == null) {
            return null;
        }
        TableMapping exact = mappings.get(tableName);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, TableMapping> entry : mappings.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(tableName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 取全局类型覆盖（保留键 {@link #GLOBAL_TABLE_KEY} 条目的 typeOverrides），无则空 Map。 */
    public static Map<String, String> globalTypeOverrides(Map<String, TableMapping> mappings) {
        if (mappings == null) {
            return Map.of();
        }
        TableMapping global = mappings.get(GLOBAL_TABLE_KEY);
        return global == null ? Map.of() : global.typeOverrides();
    }
}
