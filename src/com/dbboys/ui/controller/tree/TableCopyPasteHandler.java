package com.dbboys.ui.controller.tree;

import com.dbboys.app.AppState;
import com.dbboys.app.AppExecutor;
import com.dbboys.core.ConnectionServiceImpl;
import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.DatabasePlatformResolver;
import com.dbboys.core.PlatformResolvers;
import com.dbboys.core.SqlParser;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.util.SqlParserUtil;
import com.dbboys.model.CatalogNode;
import com.dbboys.model.ColumnsInfo;
import com.dbboys.model.Connect;
import com.dbboys.model.Index;
import com.dbboys.model.MigrationObjectRef;
import com.dbboys.model.Schema;
import com.dbboys.model.Sql;
import com.dbboys.model.TreeData;
import com.dbboys.service.BackgroundSqlService;
import com.dbboys.service.migration.TableMigrationService;
import com.dbboys.service.migration.TypeMapper;
import com.dbboys.ui.component.CustomInlineCssTextArea;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.ui.notification.NotificationUtil;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 表复制/粘贴：树菜单在单个表节点上执行"复制"时记录源表；
 * 在库（两层模型）/模式节点上"粘贴"时弹窗编辑转换后的建表 DDL、确认后建表并可选择后台迁移数据；
 * 在表节点上"粘贴"时弹窗输入 WHERE 条件后，把源表数据后台追加到该表。
 * 数据复制复用 {@link TableMigrationService}（migrateDdl=false，目标表名走 targetTableNames 映射）。
 */
public final class TableCopyPasteHandler {

    // ---- 复制的表记录 ----
    private static Connect sourceConnect;   // 副本，catalog/sessionCatalog 已定位到表所在库/模式
    private static String sourceCatalog;    // MigrationObjectRef.catalog（按源平台 catalogModel 约定）
    private static String sourceSchema;     // MigrationObjectRef.schema（仅 DATABASE_SCHEMA 模型）
    private static String sourceTable;

    private TableCopyPasteHandler() {
    }

    /** 记录复制的表（树菜单在单个表节点上执行复制时调用）。 */
    public static void recordCopy(TreeItem<TreeData> tableItem) {
        Connect connect = TreeObjectCrudHandler.buildObjectConnect(tableItem, false);
        if (connect == null) {
            return;
        }
        String[] catalogSchema = catalogSchemaOf(tableItem, platform(connect));
        sourceConnect = connect;
        sourceCatalog = catalogSchema[0];
        sourceSchema = catalogSchema[1];
        sourceTable = tableItem.getValue().getName();
    }

    public static boolean hasCopied() {
        return sourceConnect != null && sourceTable != null && !sourceTable.isBlank();
    }

    /** 库/模式节点上粘贴：弹窗编辑转换后的 DDL，确认后建表并后台迁移数据。 */
    public static void pasteToCatalogNode(TreeItem<TreeData> item) {
        if (!hasCopied()) {
            return;
        }
        Connect target = TreeObjectCrudHandler.buildObjectConnect(item, false);
        if (target == null) {
            return;
        }
        DatabasePlatform targetPlatform = platform(target);
        String[] cs = catalogSchemaOf(item, targetPlatform);
        // buildTargetSessionConnect 的语义：DATABASE→targetDatabase；SCHEMA→targetSchema；DATABASE_SCHEMA→两者
        String targetDatabase = targetPlatform.catalogModel() == DatabasePlatform.CatalogModel.SCHEMA ? null : cs[0];
        String targetSchema = switch (targetPlatform.catalogModel()) {
            case SCHEMA -> cs[0];
            case DATABASE_SCHEMA -> cs[1];
            default -> null;
        };
        Connect src = sourceConnect;
        String table = sourceTable;
        AppExecutor.runAsync(() -> {
            String originalDdl;
            String convertedDdl;
            String indexDdl;
            try {
                originalDdl = fetchOriginalDdl(src, table);
                convertedDdl = buildConvertedDdl(src, target, table, originalDdl);
                indexDdl = buildIndexDdl(src, target, table, table, convertedDdl);
            } catch (Exception e) {
                String msg = e.getMessage();
                Platform.runLater(() -> NotificationUtil.showMainNotification(msg));
                return;
            }
            String original = originalDdl;
            String converted = convertedDdl;
            String indexes = indexDdl;
            String tdb = targetDatabase, tsch = targetSchema;
            Platform.runLater(() -> showDdlDialog(item, src, target, tdb, tsch, table, original, converted, indexes));
        });
    }

    /** 表节点上粘贴：弹窗输入 WHERE 条件后，把复制的表数据后台追加到该表。 */
    public static void pasteToTable(TreeItem<TreeData> item) {
        if (!hasCopied()) {
            return;
        }
        Connect target = TreeObjectCrudHandler.buildObjectConnect(item, false);
        if (target == null) {
            return;
        }
        DatabasePlatform targetPlatform = platform(target);
        String[] cs = catalogSchemaOf(item, targetPlatform);
        String targetDatabase = targetPlatform.catalogModel() == DatabasePlatform.CatalogModel.SCHEMA ? null : cs[0];
        String targetSchema = switch (targetPlatform.catalogModel()) {
            case SCHEMA -> cs[0];
            case DATABASE_SCHEMA -> cs[1];
            default -> null;
        };
        showDataMigrationDialog(item, sourceConnect, target, targetDatabase, targetSchema,
                sourceTable, item.getValue().getName());
    }

    // ------------------------------------------------------------------
    // DDL 弹窗
    // ------------------------------------------------------------------

    private static void showDdlDialog(TreeItem<TreeData> item, Connect src, Connect dst,
                                      String targetDatabase, String targetSchema,
                                      String table, String originalDdl, String convertedDdl,
                                      String indexDdl) {
        CheckBox migrateDataCheck = new CheckBox(I18n.t("tablecopy.dialog.migrate_data", "迁移数据"));
        migrateDataCheck.setSelected(true);

        // WHERE 条件输入（可选，过滤迁移的数据）；目标表名从建表语句中提取
        TextField whereField = new TextField();
        whereField.setPrefWidth(360);
        whereField.setPromptText(I18n.t("tablecopy.dialog.where", "WHERE 条件（可选，例如 id > 100）"));
        whereField.disableProperty().bind(migrateDataCheck.selectedProperty().not());

        HBox optionsRow = new HBox(8, migrateDataCheck, whereField);
        HBox.setHgrow(whereField, Priority.ALWAYS);

        Label noteLabel = new Label(I18n.t("tablecopy.dialog.original_ddl", "原表DDL（注释参考，不会执行）") + ":");

        CustomInlineCssTextArea sqlArea = new CustomInlineCssTextArea();
        sqlArea.setEditable(true); // 粘贴弹窗的表结构可编辑（该类默认只读）
        sqlArea.replaceText(commented(originalDdl) + "\n" + convertedDdl
                + (indexDdl == null || indexDdl.isBlank() ? "" : "\n" + indexDdl));
        VBox content = new VBox(8, optionsRow, noteLabel, sqlArea);
        VBox.setVgrow(sqlArea, Priority.ALWAYS);

        ButtonType okType = new ButtonType(I18n.t("createconnect.button.confirm", "确认"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType(I18n.t("createconnect.button.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                I18n.t("tablecopy.dialog.title", "粘贴表-建表DDL"), content, 860, 560, okType, cancelType);
        if (dialog.showAndWait() != okType) {
            return;
        }
        String ddlText = stripCommentLines(sqlArea.getText());
        // 目标表名从（可能被用户修改过的）建表语句中提取
        String newName = parseCreateTableName(ddlText);
        boolean migrateData = migrateDataCheck.isSelected();
        String where = migrateData && whereField.getText() != null ? whereField.getText().trim() : "";
        if (newName.isEmpty()) {
            AlertUtil.CustomAlert(I18n.t("common.error", "错误"),
                    I18n.t("tablecopy.error.no_create_table", "未从建表语句中解析到目标表名"));
            return;
        }
        if (ddlText.isBlank()) {
            return;
        }
        String finalDdlText = rewriteIndexStatementsForTarget(ddlText, table, newName);
        AppExecutor.runAsync(() -> {
            try {
                executeDdl(dst, finalDdlText);
            } catch (Exception e) {
                // 建表失败：弹出错误信息（不用系统通知）
                String msg = e.getMessage();
                Platform.runLater(() -> AlertUtil.CustomAlert(I18n.t("common.error", "错误"),
                        I18n.t("tablecopy.error.create_failed", "建表失败：%s").formatted(msg)));
                return;
            }
            Platform.runLater(() -> {
                if (migrateData) {
                    NotificationUtil.showMainNotification(
                            I18n.t("tablecopy.notice.created", "表\"%s\"已创建，开始后台迁移数据").formatted(newName));
                } else {
                    NotificationUtil.showMainNotification(
                            I18n.t("tablecopy.notice.created_no_migrate", "表\"%s\"已创建（未迁移数据）").formatted(newName));
                }
                refreshTableList(item, newName);
            });
            if (migrateData) {
                submitDataMigration(src, dst, targetDatabase, targetSchema, table, newName, where, item);
            }
        });
    }

    /** 表节点粘贴：弹窗选择是否迁移数据并输入 WHERE 条件。 */
    private static void showDataMigrationDialog(TreeItem<TreeData> item, Connect src, Connect dst,
                                                String targetDatabase, String targetSchema,
                                                String srcTable, String dstTable) {
        CheckBox migrateDataCheck = new CheckBox(I18n.t("tablecopy.dialog.migrate_data", "迁移数据"));
        migrateDataCheck.setSelected(true);
        migrateDataCheck.setDisable(true);

        TextField whereField = new TextField();
        whereField.setPrefWidth(360);
        whereField.setPromptText(I18n.t("tablecopy.dialog.where", "WHERE 条件（可选，例如 id > 100）"));
        whereField.disableProperty().bind(migrateDataCheck.selectedProperty().not());

        HBox optionsRow = new HBox(8, migrateDataCheck, whereField);
        HBox.setHgrow(whereField, Priority.ALWAYS);

        ButtonType okType = new ButtonType(I18n.t("createconnect.button.confirm", "确认"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType(I18n.t("createconnect.button.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                I18n.t("tablecopy.dialog.title_data", "粘贴表-迁移数据"), optionsRow, 520, 160, okType, cancelType);
        if (dialog.showAndWait() != okType) {
            return;
        }
        if (!migrateDataCheck.isSelected()) {
            NotificationUtil.showMainNotification(
                    I18n.t("tablecopy.notice.migrate_skipped", "未勾选迁移数据，跳过数据迁移"));
            return;
        }
        String where = whereField.getText() == null ? "" : whereField.getText().trim();
        submitDataMigration(src, dst, targetDatabase, targetSchema, srcTable, dstTable, where, item);
    }

    // ------------------------------------------------------------------
    // DDL 获取 / 转换 / 执行
    // ------------------------------------------------------------------

    /** 原表 DDL（源方言 printTable，连接会话已定位到表所在库/模式）。 */
    private static String fetchOriginalDdl(Connect src, String table) throws Exception {
        try (Connection conn = new ConnectionServiceImpl().getConnectionWithSessionInit(src)) {
            return PlatformResolvers.get().ddl(src).printTable(conn, table);
        }
    }

    /** 同方言：原 DDL 原样回放；跨方言：TypeMapper 按列元数据重建目标方言建表 DDL。 */
    private static String buildConvertedDdl(Connect src, Connect dst, String table, String originalDdl) throws Exception {
        if (src.getDbtype() != null && src.getDbtype().equalsIgnoreCase(dst.getDbtype())) {
            return originalDdl;
        }
        DatabasePlatform sourcePlatform = platform(src);
        try (Connection conn = new ConnectionServiceImpl().getConnectionWithSessionInit(src)) {
            ArrayList<ColumnsInfo> columns = sourcePlatform.metadata().getColumns(conn, table);
            List<String> primaryKeyColumns = sourcePlatform.metadata().getPrimaryKeyColumns(conn, table);
            String tableComment = null;
            try {
                tableComment = sourcePlatform.metadata().getTableComment(conn, table);
            } catch (Exception ignored) {
            }
            List<String> warnings = new ArrayList<>();
            String script = TypeMapper.buildCreateTableScript(
                    src.getDbtype(), dst.getDbtype(), table, columns, primaryKeyColumns, tableComment, warnings);
            if (!warnings.isEmpty()) {
                // 类型回退说明作为注释行放在最前，确认执行时会被剔除
                StringBuilder sb = new StringBuilder();
                for (String warning : warnings) {
                    sb.append("-- ").append(warning).append('\n');
                }
                script = sb + script;
            }
            return script;
        }
    }

    /** 剔除注释行后，按目标平台 parser 切分并逐条执行（参照 TableMigrationService.executeScript）。 */
    private static void executeDdl(Connect dst, String ddlText) throws Exception {
        SqlParser parser = platform(dst).parser();
        try (Connection conn = new ConnectionServiceImpl().getConnectionWithSessionInit(dst);
             Statement stmt = conn.createStatement()) {
            Sql currentSql = new Sql();
            for (SqlParserUtil.Segment segment : SqlParserUtil.split(ddlText)) {
                String remainingChunk = segment.getText();
                while (remainingChunk != null && !remainingChunk.isBlank()) {
                    currentSql = parser.modifySql(currentSql, remainingChunk);
                    if (!currentSql.getSqlEnd()) {
                        break;
                    }
                    String statement = currentSql.getSqlstr();
                    if (SqlParserUtil.isExecutableStatement(statement)) {
                        executeStatement(stmt, statement);
                    }
                    remainingChunk = currentSql.getSqlRemainder();
                    currentSql = new Sql();
                }
            }
            if (SqlParserUtil.isExecutableStatement(currentSql.getSqlstr())) {
                executeStatement(stmt, currentSql.getSqlstr());
            }
        }
    }

    /** 执行单条 DDL：去掉末尾分号（Oracle 等驱动拒绝语句尾的分号；BEGIN/DECLARE 块保留）。 */
    private static void executeStatement(Statement stmt, String statement) throws Exception {
        String execSql = statement == null ? "" : statement.trim();
        String upper = execSql.toUpperCase(java.util.Locale.ROOT);
        if (!(upper.startsWith("BEGIN") || upper.startsWith("DECLARE")) && execSql.endsWith(";")) {
            execSql = execSql.substring(0, execSql.length() - 1).trim();
        }
        if (!execSql.isEmpty()) {
            stmt.execute(execSql);
        }
    }

    /** 从建表语句文本提取目标表名：第一个 CREATE TABLE 后的名字，去引号/反引号/方括号，取最后一段（去模式前缀）。 */
    private static String parseCreateTableName(String ddlText) {
        if (ddlText == null) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)\\bCREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([\"'`\\[\\]\\w.]+)")
                .matcher(ddlText);
        if (!m.find()) {
            return "";
        }
        String name = m.group(1).replaceAll("[\"'`\\[\\]]", "");
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    // ------------------------------------------------------------------
    // 数据迁移
    // ------------------------------------------------------------------

    /** 提交后台数据迁移：只迁数据（表结构由调用方保证），源表名→目标表名走 targetTableNames 映射。 */
    private static void submitDataMigration(Connect src, Connect dst,
                                            String targetDatabase, String targetSchema,
                                            String srcTable, String dstTable, String where,
                                            TreeItem<TreeData> treeItem) {
        // where 条件过滤源表数据（可带或不带 WHERE 关键字，由迁移引擎归一化）
        MigrationObjectRef ref = new MigrationObjectRef(sourceCatalog, sourceSchema,
                MigrationObjectRef.Kind.TABLE, srcTable, where);
        TableMigrationService.MigrationRequest request = new TableMigrationService.MigrationRequest(
                src, dst, targetDatabase, targetSchema, List.of(ref),
                false, true, false, false, 1, 1, Map.of(),
                Map.of(srcTable, dstTable));
        var task = new TableMigrationService().createTask(request, null);
        task.setOnSucceeded(e -> {
            TableMigrationService.MigrationSummary summary = task.getValue();
            if (summary == null) {
                return;
            }
            if (summary.cancelled()) {
                NotificationUtil.showMainNotification(I18n.t("migration.log.cancelled", "迁移已取消"));
                return;
            }
            // 条目级失败（插入报错等）不抛异常而是收进结果集：必须检查并弹出错误信息
            List<TableMigrationService.ItemResult> failed = summary.results().stream()
                    .filter(r -> r.status() == TableMigrationService.ItemStatus.FAILED)
                    .toList();
            if (!failed.isEmpty()) {
                TableMigrationService.ItemResult r = failed.get(0);
                String detail = r.message() + (r.errorSql() == null ? "" : "\n" + r.errorSql());
                AlertUtil.CustomAlert(I18n.t("common.error", "错误"),
                        I18n.t("tablecopy.error.paste_failed", "表数据迁移失败：%s").formatted(detail));
                return;
            }
            long rows = summary.results().isEmpty() ? 0 : summary.results().get(0).rowsCopied();
            // 数据迁移完成后先执行统计更新，再刷新这张表的元数据，保证行数等是最新的
            String schemaName = treeItem == null ? targetSchema : TreeNavigator.getCurrentDatabase(treeItem).getName();
            String doneMessage = I18n.t("tablecopy.notice.paste_done", "表数据迁移完成：%s（%d 行）")
                    .formatted(dstTable, rows);
            TreeViewUtil.tableService.updateStatisticsForTable(dst, dstTable, platform(dst), schemaName,
                    () -> refreshTableAfterMigration(treeItem, dstTable, doneMessage));
        });
        task.setOnFailed(e -> NotificationUtil.showMainNotification(
                I18n.t("tablecopy.error.paste_failed", "表数据迁移失败：%s")
                        .formatted(task.getException() == null ? "" : task.getException().getMessage())));
        BackgroundSqlService.backSqlExecutor.submit(task);
    }

    /** 统计更新完成后选中目标表，并复用右键刷新的刷新逻辑，不整体刷新表列表。 */
    private static void refreshTableAfterMigration(TreeItem<TreeData> treeItem, String tableName, String doneMessage) {
        TreeItem<TreeData> tableItem = findTableItem(treeItem, tableName);
        if (tableItem == null) {
            NotificationUtil.showMainNotification(doneMessage);
            return;
        }
        AppState.getDatabaseMetaTreeView().getSelectionModel().select(tableItem);
        if (TreeViewUtil.refreshItem == null) {
            // 与右键刷新一致的兜底逻辑
            tableItem.getValue().setRunning(true);
            TreeViewUtil.tableService.refreshTableMeta(
                    TreeNavigator.getMetaConnect(tableItem),
                    TreeNavigator.getCurrentDatabase(tableItem),
                    tableName,
                    tableItem::setValue,
                    () -> {
                        tableItem.getValue().setRunning(false);
                        NotificationUtil.showMainNotification(doneMessage);
                    });
            return;
        }
        final boolean[] fired = {false};
        final ChangeListener<Boolean>[] listenerRef = new ChangeListener[1];
        listenerRef[0] = (obs, oldValue, newValue) -> {
            if (fired[0] && Boolean.TRUE.equals(oldValue) && !newValue) {
                tableItem.getValue().runningProperty().removeListener(listenerRef[0]);
                NotificationUtil.showMainNotification(doneMessage);
            }
        };
        tableItem.getValue().runningProperty().addListener(listenerRef[0]);
        fired[0] = true;
        TreeViewUtil.refreshItem.fire();
    }

    private static TreeItem<TreeData> findTableItem(TreeItem<TreeData> node, String tableName) {
        if (node == null || tableName == null || tableName.isBlank()) {
            return null;
        }
        if (node.getValue() instanceof com.dbboys.model.Table table
                && tableName.equalsIgnoreCase(table.getName())) {
            return node;
        }
        TreeItem<TreeData> catalogItem = node;
        while (catalogItem != null && !(catalogItem.getValue() instanceof CatalogNode)) {
            catalogItem = catalogItem.getParent();
        }
        if (catalogItem == null) {
            return null;
        }
        for (TreeItem<TreeData> child : catalogItem.getChildren()) {
            if (child.getValue() instanceof com.dbboys.ui.treemodel.ObjectFolder
                    && TreeDataLoader.getObjectFolderKind(child) == TreeDataLoader.ObjectFolderKind.TABLES) {
                for (TreeItem<TreeData> tableItem : child.getChildren()) {
                    if (tableItem.getValue() instanceof com.dbboys.model.Table table
                            && tableName.equalsIgnoreCase(table.getName())) {
                        return tableItem;
                    }
                }
            }
        }
        return null;
    }

    /** 生成源表索引 DDL，供建表弹窗编辑并在建表时一并执行；跳过主键支撑索引（已在建表 DDL 内联创建）。
     *  改名粘贴时索引名改为 <目标表名>_<原索引名> 避免同模式冲突。 */
    private static String buildIndexDdl(Connect src, Connect dst, String srcTable, String dstTable,
                                        String convertedDdl) {
        boolean nativeDdl = src.getDbtype() != null && src.getDbtype().equalsIgnoreCase(dst.getDbtype());
        if (convertedDdl != null && tableDdlAlreadyHasIndexes(convertedDdl, dst.getDbtype(), nativeDdl)) {
            return "";
        }
        String dbName = sourceSchema != null && !sourceSchema.isBlank() ? sourceSchema : sourceCatalog;
        List<Index> indexes;
        List<String> pkColumns;
        DatabasePlatform sourcePlatform = platform(src);
        try (Connection conn = new ConnectionServiceImpl().getConnectionWithSessionInit(src)) {
            indexes = sourcePlatform.metadata().getIndexes(conn, dbName);
            pkColumns = sourcePlatform.metadata().getPrimaryKeyColumns(conn, srcTable);
        } catch (Exception e) {
            return "-- " + I18n.t("tablecopy.error.index_generate_failed", "索引DDL生成失败：%s")
                    .formatted(e.getMessage()) + "\n";
        }
        if (indexes == null || indexes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Index index : indexes) {
            if (index == null) {
                continue;
            }
            String idxTable = index.getTableName() != null && !index.getTableName().isBlank()
                    ? index.getTableName() : index.getTabname();
            if (idxTable == null || !idxTable.equalsIgnoreCase(srcTable)) {
                continue; // 只处理源表的索引
            }
            String indexName = index.getName();
            String columns = index.getCols() != null && !index.getCols().isBlank()
                    ? index.getCols()
                    : (index.getIndexCols() == null ? "" : index.getIndexCols());
            if (indexName == null || indexName.isBlank() || columns.isBlank()) {
                continue;
            }
            boolean unique = "U".equalsIgnoreCase(index.getIdxtype())
                    || "UNIQUE".equalsIgnoreCase(index.getIdxtype());
            if ("PRIMARY".equalsIgnoreCase(indexName)
                    || (unique && sameColumns(columns, pkColumns))) {
                continue; // 主键及其支撑唯一索引已在建表 DDL 中内联创建
            }
            String targetIndexName = srcTable.equalsIgnoreCase(dstTable)
                    ? indexName
                    : dstTable + "_" + indexName;
            sb.append("CREATE ").append(unique ? "UNIQUE " : "").append("INDEX ").append(targetIndexName)
                    .append(" ON ").append(dstTable).append(" (").append(columns).append(");\n");
        }
        return sb.toString();
    }

    /** 用户在弹窗中改名建表语句时，同步修正生成索引语句里的目标表名/索引名。 */
    private static String rewriteIndexStatementsForTarget(String ddlText, String oldTable, String newTable) {
        if (ddlText == null || ddlText.isBlank()
                || oldTable == null || newTable == null || oldTable.equalsIgnoreCase(newTable)) {
            return ddlText;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : ddlText.split("\n", -1)) {
            String upper = line.toUpperCase(java.util.Locale.ROOT);
            if (upper.contains("CREATE") && upper.contains("INDEX")) {
                line = line.replaceAll("(?i)\\bON\\s+(?:[\"'`\\[\\]\\w.]+\\.)?[\"'`\\[\\]]*"
                                + java.util.regex.Pattern.quote(oldTable) + "[\"'`\\[\\]]*\\b",
                        " ON " + java.util.regex.Matcher.quoteReplacement(newTable));
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("(?i)^\\s*CREATE\\s+(?:UNIQUE\\s+|CLUSTER\\s+)?INDEX\\s+([^\\s(]+)")
                        .matcher(line);
                if (m.find()) {
                    String indexName = m.group(1);
                    String bare = indexName.replaceAll("[\"'`\\[\\]]", "");
                    if (!bare.regionMatches(true, 0, newTable + "_", 0, newTable.length() + 1)) {
                        line = line.substring(0, m.start(1)) + newTable + "_" + indexName
                                + line.substring(m.end(1));
                    }
                }
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** 建表 DDL 已含索引定义时不再追加索引语句（例如 MySQL 的 SHOW CREATE TABLE、Informix 系 printTable）。 */
    private static boolean tableDdlAlreadyHasIndexes(String ddl, String dbType, boolean nativeDdl) {
        if (ddl == null || ddl.isBlank()) {
            return false;
        }
        if (java.util.regex.Pattern.compile("(?is)\\bCREATE\\s+(?:UNIQUE\\s+|CLUSTER\\s+)?INDEX\\b")
                    .matcher(ddl).find()) {
            return true;
        }
        return nativeDdl && "MYSQL".equalsIgnoreCase(dbType)
                && java.util.regex.Pattern.compile("(?is)\\b(?:KEY|INDEX)\\b").matcher(ddl).find();
    }

    /** 索引列与主键列是否为同一组（大小写/顺序/引号不敏感）：是则说明该唯一索引是主键支撑索引。 */
    private static boolean sameColumns(String indexColumns, List<String> pkColumns) {
        if (pkColumns == null || pkColumns.isEmpty()) {
            return false;
        }
        String[] idxCols = indexColumns.split(",");
        if (idxCols.length != pkColumns.size()) {
            return false;
        }
        java.util.Set<String> idxSet = new java.util.HashSet<>();
        for (String c : idxCols) {
            idxSet.add(c.trim().replaceAll("[\"'`\\[\\]]", "").toLowerCase(java.util.Locale.ROOT));
        }
        for (String pk : pkColumns) {
            if (!idxSet.contains(pk == null ? "" : pk.trim().toLowerCase(java.util.Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static DatabasePlatform platform(Connect connect) {
        return resolver().requirePlatform(connect);
    }

    private static DatabasePlatformResolver resolver() {
        return PlatformResolvers.get();
    }

    /** [catalog, schema]：DATABASE→[库名,null]；SCHEMA→[模式名,null]；DATABASE_SCHEMA→[库名,模式名]。 */
    private static String[] catalogSchemaOf(TreeItem<TreeData> item, DatabasePlatform platform) {
        CatalogNode node = TreeNavigator.getCurrentDatabase(item);
        String name = node == null ? null : node.getName();
        String parentDb = node instanceof Schema schema ? schema.getParentDb() : null;
        return switch (platform.catalogModel()) {
            case DATABASE, SCHEMA -> new String[]{name, null};
            case DATABASE_SCHEMA -> parentDb != null && !parentDb.isBlank()
                    ? new String[]{parentDb, name}
                    : new String[]{name, null};
        };
    }

    /** 刷新目标库/模式下的"表"文件夹（不折叠目标节点本身；表文件夹未加载时不动，展开时会新加载），并等待选中新表。 */
    private static void refreshTableList(TreeItem<TreeData> node, String selectTableName) {
        TreeItem<TreeData> catalogItem = node;
        while (catalogItem != null && !(catalogItem.getValue() instanceof CatalogNode)) {
            catalogItem = catalogItem.getParent();
        }
        if (catalogItem == null) {
            return;
        }
        for (TreeItem<TreeData> child : catalogItem.getChildren()) {
            if (child.getValue() instanceof com.dbboys.ui.treemodel.ObjectFolder
                    && TreeDataLoader.getObjectFolderKind(child) == TreeDataLoader.ObjectFolderKind.TABLES) {
                child.getChildren().clear();
                child.setExpanded(false);
                child.setExpanded(true);
                if (selectTableName != null && !selectTableName.isBlank()) {
                    waitAndSelectCreatedTableNode(catalogItem, selectTableName);
                }
                return;
            }
        }
    }

    /** 等待"表"文件夹异步加载完成后，在树中选中刚粘贴创建的表。 */
    private static void waitAndSelectCreatedTableNode(TreeItem<TreeData> catalogItem, String tableName) {
        TreeItem<TreeData> tableFolder = findTableFolder(catalogItem);
        if (tableFolder == null) {
            return;
        }
        final int[] retries = {100};
        final ListChangeListener<TreeItem<TreeData>>[] listenerRef = new ListChangeListener[1];
        final Runnable[] trySelectRef = new Runnable[1];
        trySelectRef[0] = () -> {
            TreeItem<TreeData> found = findTableItem(catalogItem, tableName);
            if (found != null) {
                if (listenerRef[0] != null) {
                    tableFolder.getChildren().removeListener(listenerRef[0]);
                }
                AppState.getDatabaseMetaTreeView().getSelectionModel().clearSelection();
                AppState.getDatabaseMetaTreeView().getSelectionModel().select(found);
                return;
            }
            if (retries[0]-- > 0) {
                javafx.animation.PauseTransition delay =
                        new javafx.animation.PauseTransition(javafx.util.Duration.millis(100));
                delay.setOnFinished(e -> trySelectRef[0].run());
                delay.play();
            } else if (listenerRef[0] != null) {
                tableFolder.getChildren().removeListener(listenerRef[0]);
            }
        };
        listenerRef[0] = change -> Platform.runLater(trySelectRef[0]);
        tableFolder.getChildren().addListener(listenerRef[0]);
        Platform.runLater(trySelectRef[0]);
    }

    private static TreeItem<TreeData> findTableFolder(TreeItem<TreeData> catalogItem) {
        for (TreeItem<TreeData> child : catalogItem.getChildren()) {
            if (child.getValue() instanceof com.dbboys.ui.treemodel.ObjectFolder
                    && TreeDataLoader.getObjectFolderKind(child) == TreeDataLoader.ObjectFolderKind.TABLES) {
                return child;
            }
        }
        return null;
    }

    /** 每行加 "-- " 前缀，把原 DDL 变成注释参考块。 */
    private static String commented(String ddl) {
        StringBuilder sb = new StringBuilder();
        for (String line : (ddl == null ? "" : ddl).split("\n", -1)) {
            sb.append("-- ").append(line).append('\n');
        }
        return sb.toString();
    }

    /** 剔除注释行（弹窗确认后只执行非注释内容）。 */
    private static String stripCommentLines(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : (text == null ? "" : text).split("\n", -1)) {
            if (!line.trim().startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
