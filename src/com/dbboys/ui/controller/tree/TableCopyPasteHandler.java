package com.dbboys.ui.controller.tree;

import com.dbboys.app.AppState;
import com.dbboys.app.AppExecutor;
import com.dbboys.core.ConnectionServiceImpl;
import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.DatabasePlatformResolver;
import com.dbboys.core.DdlRepository;
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
import com.dbboys.service.migration.MigrationConnectInfo;
import com.dbboys.service.migration.TableMigrationService;
import com.dbboys.service.migration.TypeMapper;
import com.dbboys.ui.component.CustomInlineCssTextArea;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.ui.notification.NotificationUtil;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对象复制/粘贴：树菜单在单个对象节点上执行"复制"时记录源对象
 * （表/视图/索引/序列/同义词/触发器/函数/存储过程/包/类型/队列/作业）；
 * 在库（两层模型）/模式节点上"粘贴"时弹窗显示源对象位置并编辑 DDL，确认后创建对象；
 * 源对象为表时弹窗编辑转换后的建表 DDL、可勾选后台迁移数据，其他对象按原 DDL 回放（禁用迁移数据及 WHERE 条件）；
 * 在表节点上"粘贴"（仅源为表）时弹窗输入 WHERE 条件后，把源表数据后台追加到该表。
 * 数据复制复用 {@link TableMigrationService}（migrateDdl=false，目标表名走 targetTableNames 映射）。
 */
public final class TableCopyPasteHandler {

    private static final Logger LOG = LogManager.getLogger(TableCopyPasteHandler.class);

    // ---- 复制的对象记录 ----
    private static Connect sourceConnect;   // 副本，catalog/sessionCatalog 已定位到对象所在库/模式
    private static String sourceCatalog;    // MigrationObjectRef.catalog（按源平台 catalogModel 约定）
    private static String sourceSchema;     // MigrationObjectRef.schema（仅 DATABASE_SCHEMA 模型）
    private static String sourceName;
    private static CopyKind sourceKind;

    /** 可复制粘贴的对象类型（独立于 MigrationObjectRef.Kind：类型/队列/作业不参与迁移任务）。 */
    private enum CopyKind { TABLE, VIEW, SEQUENCE, SYNONYM, TRIGGER, FUNCTION, PROCEDURE, PACKAGE, INDEX, TYPE, QUEUE, JOB }

    private TableCopyPasteHandler() {
    }

    /** 记录复制的对象（树菜单在单个对象节点上执行复制时调用；不支持的节点类型保持原记录不变）。 */
    public static void recordCopy(TreeItem<TreeData> item) {
        CopyKind kind = kindOf(item.getValue());
        if (kind == null) {
            return;
        }
        Connect connect = TreeObjectCrudHandler.buildObjectConnect(item, false);
        if (connect == null) {
            return;
        }
        String[] catalogSchema = catalogSchemaOf(item, platform(connect));
        sourceConnect = connect;
        sourceCatalog = catalogSchema[0];
        sourceSchema = catalogSchema[1];
        sourceName = item.getValue().getName();
        sourceKind = kind;
    }

    public static boolean hasCopied() {
        return sourceConnect != null && sourceName != null && !sourceName.isBlank();
    }

    /** 当前复制的源对象是否为表（表节点粘贴=追加数据，仅源为表时可用）。 */
    public static boolean isCopiedTable() {
        return hasCopied() && sourceKind == CopyKind.TABLE;
    }

    /** 树节点类型 → 可复制对象类型；不支持的类型返回 null（回收站对象、包内函数/过程等不记录）。 */
    private static CopyKind kindOf(TreeData treeData) {
        if (treeData instanceof com.dbboys.model.Table) {
            return CopyKind.TABLE;
        }
        if (treeData instanceof com.dbboys.model.View) {
            return CopyKind.VIEW;
        }
        if (treeData instanceof com.dbboys.model.Index) {
            return CopyKind.INDEX;
        }
        if (treeData instanceof com.dbboys.model.Sequence) {
            return CopyKind.SEQUENCE;
        }
        if (treeData instanceof com.dbboys.model.Synonym) {
            return CopyKind.SYNONYM;
        }
        if (treeData instanceof com.dbboys.model.Trigger) {
            return CopyKind.TRIGGER;
        }
        if (treeData instanceof com.dbboys.model.Function) {
            return CopyKind.FUNCTION;
        }
        if (treeData instanceof com.dbboys.model.Procedure) {
            return CopyKind.PROCEDURE;
        }
        if (treeData instanceof com.dbboys.model.DBPackage) {
            return CopyKind.PACKAGE;
        }
        if (treeData instanceof com.dbboys.model.Type) {
            return CopyKind.TYPE;
        }
        if (treeData instanceof com.dbboys.model.Queue) {
            return CopyKind.QUEUE;
        }
        if (treeData instanceof com.dbboys.model.SchedulerJob) {
            return CopyKind.JOB;
        }
        return null;
    }

    /** 库/模式节点上粘贴：弹窗显示源对象位置并编辑 DDL，确认后创建对象（表可勾选后台迁移数据）。 */
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
        String name = sourceName;
        CopyKind kind = sourceKind;
        showDdlDialog(item, src, target, targetDatabase, targetSchema, kind, name);
    }

    /** 表节点上粘贴（仅源为表）：弹窗输入 WHERE 条件后，把复制的表数据后台追加到该表。 */
    public static void pasteToTable(TreeItem<TreeData> item) {
        if (!isCopiedTable()) {
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
                sourceName, item.getValue().getName());
    }

    // ------------------------------------------------------------------
    // DDL 弹窗
    // ------------------------------------------------------------------

    private static void showDdlDialog(TreeItem<TreeData> item, Connect src, Connect dst,
                                      String targetDatabase, String targetSchema,
                                      CopyKind kind, String name) {
        boolean isTable = kind == CopyKind.TABLE;
        ImageView loadingIcon = IconFactory.imageView(IconPaths.LOADING_GIF, 12, 12, true);

        // 源对象位置：连接名 / catalog.schema.对象名（类型）
        Label sourceLabel = new Label(sourceLocationText());

        CheckBox migrateDataCheck = new CheckBox(I18n.t("tablecopy.dialog.migrate_data", "迁移数据"));
        // 仅源为表时可迁移数据；其他对象禁用迁移数据及 WHERE 条件
        migrateDataCheck.setSelected(isTable);
        migrateDataCheck.setDisable(!isTable);

        // WHERE 条件输入（可选，过滤迁移的数据）；目标表名从建表语句中提取
        TextField whereField = new TextField();
        whereField.setPrefWidth(360);
        whereField.setPromptText(I18n.t("tablecopy.dialog.where", "WHERE 条件（可选，例如 id > 100）"));
        whereField.disableProperty().bind(migrateDataCheck.selectedProperty().not());

        HBox optionsRow = new HBox(8, migrateDataCheck, whereField);
        HBox.setHgrow(whereField, Priority.ALWAYS);

        Label noteLabel = new Label(I18n.t("tablecopy.dialog.original_ddl", "源对象DDL（注释参考，不会执行）") + ":");

        CustomInlineCssTextArea sqlArea = new CustomInlineCssTextArea();
        sqlArea.setEditable(true); // 粘贴弹窗的 DDL 可编辑（该类默认只读）
        // 默认边框（跟随主题边框色），把 DDL 编辑区与对话框背景区分开
        sqlArea.setStyle("-fx-border-color: -color-border-default;");
        VBox content = new VBox(8, sourceLabel, optionsRow, noteLabel, sqlArea);
        VBox.setVgrow(sqlArea, Priority.ALWAYS);
        StackPane contentStack = new StackPane(content, loadingIcon);
        contentStack.setAlignment(Pos.CENTER);

        ButtonType okType = new ButtonType(I18n.t("createconnect.button.confirm", "确认"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType(I18n.t("createconnect.button.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);
        String dialogTitle = isTable
                ? I18n.t("tablecopy.dialog.title", "粘贴表-建表DDL")
                : I18n.t("tablecopy.dialog.title_object", "粘贴%s-DDL").formatted(kindDisplayName(kind));
        AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                dialogTitle, contentStack, 860, 560, okType, cancelType);
        Button okButton = dialog.getButton(okType);
        okButton.setDisable(true);

        // 源 DDL 注释参考块：确认执行时按前缀原样剔除（非表对象不能用整行注释剔除，过程体可能含 "--" 注释）
        final String[] commentedBlockHolder = new String[1];

        AppExecutor.runAsync(() -> {
            String originalDdl;
            String convertedDdl;
            String indexDdl = null;
            try {
                if (isTable) {
                    originalDdl = fetchOriginalDdl(src, name);
                    convertedDdl = buildConvertedDdl(src, dst, name, originalDdl);
                    indexDdl = buildIndexDdl(src, dst, name, name, convertedDdl);
                } else {
                    // 其他对象：按源端 DDL 原样回放，不做类型转换（不兼容由目标库执行时报错）；
                    // 源 DDL 同时作为注释参考块显示（与表一致）
                    originalDdl = fetchObjectDdl(src, kind, name);
                    convertedDdl = originalDdl;
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                LOG.error("加载对象DDL失败: {}", msg, e);
                Platform.runLater(() -> {
                    if (!dialog.getStage().isShowing()) {
                        return;
                    }
                    AlertUtil.CustomAlert(I18n.t("common.error", "错误"),
                            I18n.t("tablecopy.error.load_ddl_failed", "加载DDL失败：%s").formatted(msg));
                    dialog.getStage().close();
                });
                return;
            }
            String original = originalDdl;
            String converted = commentSqlModeLine(convertedDdl);
            String indexes = indexDdl;
            String commentedBlock = commented(original) + "\n";
            commentedBlockHolder[0] = commentedBlock;
            Platform.runLater(() -> {
                if (!dialog.getStage().isShowing()) {
                    return;
                }
                sqlArea.replaceText(commentedBlock + converted
                        + (indexes == null || indexes.isBlank() ? "" : "\n" + indexes));
                loadingIcon.setVisible(false);
                loadingIcon.setManaged(false);
                okButton.setDisable(false);
            });
        });

        if (dialog.showAndWait() != okType) {
            return;
        }
        if (!isTable) {
            executeObjectDdl(item, dst, kind, name,
                    stripLeadingCommentedBlock(sqlArea.getText(), commentedBlockHolder[0]));
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
        String finalDdlText = rewriteIndexStatementsForTarget(ddlText, name, newName);
        AppExecutor.runAsync(() -> {
            try {
                executeDdl(dst, finalDdlText);
            } catch (Exception e) {
                // 建表失败：弹出错误信息（不用系统通知）
                String msg = e.getMessage();
                LOG.error("建表失败: {}", msg, e);
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
                submitDataMigration(src, dst, targetDatabase, targetSchema, name, newName, where, item);
            }
        });
    }

    /** 非表对象粘贴确认：剔除源 DDL 注释参考块后执行弹窗中的 DDL（其余注释行保留，存储过程/函数体可能含 "--" 注释），成功后刷新对应对象文件夹。 */
    private static void executeObjectDdl(TreeItem<TreeData> item, Connect dst,
                                         CopyKind kind, String name, String ddlText) {
        if (ddlText == null || ddlText.isBlank()) {
            return;
        }
        AppExecutor.runAsync(() -> {
            try {
                executeDdl(dst, ddlText);
            } catch (Exception e) {
                // 创建失败：弹出错误信息（不用系统通知）
                String msg = e.getMessage();
                LOG.error("创建对象失败: {}", msg, e);
                Platform.runLater(() -> AlertUtil.CustomAlert(I18n.t("common.error", "错误"),
                        I18n.t("tablecopy.error.create_object_failed", "创建对象失败：%s").formatted(msg)));
                return;
            }
            Platform.runLater(() -> {
                NotificationUtil.showMainNotification(
                        I18n.t("tablecopy.notice.object_created", "%s\"%s\"已创建")
                                .formatted(kindDisplayName(kind), name));
                refreshObjectFolder(item, folderKindOf(kind));
            });
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

        // 源对象位置：连接名 / catalog.schema.表名（表）
        Label sourceLabel = new Label(sourceLocationText());
        VBox content = new VBox(8, sourceLabel, optionsRow);

        ButtonType okType = new ButtonType(I18n.t("createconnect.button.confirm", "确认"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType(I18n.t("createconnect.button.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                I18n.t("tablecopy.dialog.title_data", "粘贴表-迁移数据"), content, 520, 190, okType, cancelType);
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

    /** 原表完整 DDL（与表右键导出一致，含索引/约束/触发器；连接会话已定位到表所在库/模式）。 */
    private static String fetchOriginalDdl(Connect src, String table) throws Exception {
        try (Connection conn = new ConnectionServiceImpl().getConnectionWithSessionInit(src)) {
            return PlatformResolvers.get().ddl(src).printTableWithDependencies(conn, table);
        }
    }

    /** 剔除弹窗内容前部的源 DDL 注释参考块（仅精确匹配前缀；用户改过注释块时不剔除，注释行由 SQL 解析器跳过）。 */
    private static String stripLeadingCommentedBlock(String text, String commentedBlock) {
        if (text != null && commentedBlock != null && !commentedBlock.isEmpty()
                && text.startsWith(commentedBlock)) {
            return text.substring(commentedBlock.length());
        }
        return text;
    }

    /** 非表对象源端 DDL（原样回放，不做类型转换；连接会话已定位到对象所在库/模式）。 */
    private static String fetchObjectDdl(Connect src, CopyKind kind, String name) throws Exception {
        try (Connection conn = new ConnectionServiceImpl().getConnectionWithSessionInit(src)) {
            DdlRepository ddl = PlatformResolvers.get().ddl(src);
            String ddlText = switch (kind) {
                case VIEW -> ddl.printView(conn, name);
                case SEQUENCE -> ddl.printSequence(conn, name);
                case SYNONYM -> ddl.printSynonym(conn, name);
                case TRIGGER -> ddl.printTrigger(conn, name);
                case FUNCTION -> ddl.printFunction(conn, name);
                case PROCEDURE -> ddl.printProcedure(conn, name);
                case PACKAGE -> ddl.printPackage(conn, name);
                case INDEX -> ddl.printIndex(conn, name);
                case TYPE -> ddl.printType(conn, name);
                case QUEUE -> ddl.printQueue(conn, name);
                case JOB -> ddl.printSchedulerJob(conn, name);
                default -> throw new IllegalArgumentException("unsupported copy kind: " + kind);
            };
            // 不支持导出该对象 DDL 的方言返回 "--" 占位（如 SQLite 的序列），按加载失败处理
            if (ddlText == null || ddlText.isBlank() || ddlText.trim().equals("--")) {
                throw new IllegalStateException(
                        I18n.t("tablecopy.error.ddl_unsupported", "该数据库不支持导出此对象的DDL"));
            }
            return ddlText;
        }
    }

    /** DDL 转换基准：GBase 仅 mysql sqlmode 按 MySQL 处理，oracle sqlmode 忽略、按数据库类型转换。 */
    private static String buildConvertedDdl(Connect src, Connect dst, String table, String originalDdl) throws Exception {
        DatabasePlatform sourcePlatform = platform(src);
        try (Connection conn = new ConnectionServiceImpl().getConnectionWithSessionInit(src)) {
            String sourceType = sourceMappingType(src, table, conn);
            String targetType = targetMappingType(dst);
            if (sourceType != null && sourceType.equalsIgnoreCase(targetType)) {
                return originalDdl;
            }
            ArrayList<ColumnsInfo> columns = sourcePlatform.metadata().getColumns(conn, table);
            List<String> primaryKeyColumns = sourcePlatform.metadata().getPrimaryKeyColumns(conn, table);
            String tableComment = null;
            try {
                tableComment = sourcePlatform.metadata().getTableComment(conn, table);
            } catch (Exception ignored) {
                LOG.debug("获取表注释失败: {}", ignored.getMessage(), ignored);
            }
            List<String> warnings = new ArrayList<>();
            String script = TypeMapper.buildCreateTableScript(
                    sourceType, targetType, table, columns, primaryKeyColumns, tableComment,
                    warnings, null, null, "MYSQL".equalsIgnoreCase(targetType));
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
        try (Connection conn = new ConnectionServiceImpl().createConnection(dst);
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
                LOG.error("表数据迁移失败: {}", detail);
                AlertUtil.CustomAlert(I18n.t("common.error", "错误"),
                        I18n.t("tablecopy.error.paste_failed", "表数据迁移失败：%s").formatted(detail));
                return;
            }
            long rows = summary.results().isEmpty() ? 0 : summary.results().get(0).rowsCopied();
            // 数据迁移完成后先执行统计更新，再刷新这张表的元数据，保证行数等是最新的
            String schemaName = treeItem == null ? targetSchema : TreeNavigator.getCurrentDatabase(treeItem).getName();
            String doneMessage = I18n.t("tablecopy.notice.paste_done", "表数据迁移完成：%s（%d 行）")
                    .formatted(dstTable, rows);
            NotificationUtil.showMainNotification(doneMessage);
            TreeViewUtil.tableService.updateStatisticsForTable(dst, dstTable, platform(dst), schemaName,
                    () -> refreshTableAfterMigration(treeItem, dstTable));
        });
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            LOG.error("表数据迁移任务失败", ex);
            NotificationUtil.showMainNotification(
                    I18n.t("tablecopy.error.paste_failed", "表数据迁移失败：%s")
                            .formatted(ex == null ? "" : ex.getMessage()));
        });
        BackgroundSqlService.backSqlExecutor.submit(task);
    }

    /** 统计更新完成后选中目标表，并复用右键刷新的刷新逻辑，不整体刷新表列表。 */
    private static void refreshTableAfterMigration(TreeItem<TreeData> treeItem, String tableName) {
        TreeItem<TreeData> tableItem = findTableItem(treeItem, tableName);
        if (tableItem == null) {
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
                    () -> tableItem.getValue().setRunning(false));
            return;
        }
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
        String targetType = targetMappingType(dst);
        String dbName = sourceSchema != null && !sourceSchema.isBlank() ? sourceSchema : sourceCatalog;
        List<Index> indexes;
        List<String> pkColumns;
        String sourceType;
        DatabasePlatform sourcePlatform = platform(src);
        try (Connection conn = new ConnectionServiceImpl().getConnectionWithSessionInit(src)) {
            sourceType = sourceMappingType(src, srcTable, conn);
            indexes = sourcePlatform.metadata().getIndexes(conn, dbName);
            pkColumns = sourcePlatform.metadata().getPrimaryKeyColumns(conn, srcTable);
        } catch (Exception e) {
            LOG.error("索引DDL生成失败: {}", e.getMessage(), e);
            return "-- " + I18n.t("tablecopy.error.index_generate_failed", "索引DDL生成失败：%s")
                    .formatted(e.getMessage()) + "\n";
        }
        boolean nativeDdl = sourceType != null && sourceType.equalsIgnoreCase(targetType);
        if (convertedDdl != null && tableDdlAlreadyHasIndexes(convertedDdl, targetType, nativeDdl)) {
            return "";
        }
        if (indexes == null || indexes.isEmpty()) {
            return "";
        }
        boolean quoteMysql = "MYSQL".equalsIgnoreCase(targetType);
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
                    || sameColumns(columns, pkColumns)) {
                continue; // 主键及其支撑唯一索引已在建表 DDL 中内联创建
            }
            String targetIndexName = srcTable.equalsIgnoreCase(dstTable)
                    ? indexName
                    : dstTable + "_" + indexName;
            if (quoteMysql) {
                targetIndexName = quoteMysqlName(targetIndexName);
                columns = quoteMysqlColumns(columns);
            }
            String targetTableName = quoteMysql ? quoteMysqlName(dstTable) : dstTable;
            sb.append("CREATE ").append(unique ? "UNIQUE " : "").append("INDEX ").append(targetIndexName)
                    .append(" ON ").append(targetTableName).append(" (").append(columns).append(");\n");
        }
        return sb.toString();
    }

    /** 粘贴转换用的源方言：GBase 表 sqlmode 仅 mysql 时用 MYSQL，oracle 等其他 sqlmode 忽略、按源连接 dbtype。 */
    private static String sourceMappingType(Connect src, String tableName, Connection conn) {
        String sqlMode = null;
        try {
            sqlMode = platform(src).metadata().getTableSqlMode(conn, tableName);
        } catch (Exception e) {
            LOG.debug("read table sqlmode failed: {}", tableName, e);
        }
        if (sqlMode != null && "mysql".equalsIgnoreCase(sqlMode)) {
            return "MYSQL";
        }
        return src == null ? null : src.getDbtype();
    }

    /** 粘贴目标方言：GBase 会话 sqlmode 仅 mysql 时用 MYSQL，oracle 等其他 sqlmode 忽略、按目标连接 dbtype。 */
    private static String targetMappingType(Connect dst) {
        if (dst == null) {
            return null;
        }
        String sqlMode = MigrationConnectInfo.probeSqlMode(dst);
        if (sqlMode != null && "mysql".equalsIgnoreCase(sqlMode.replace("sqlmode=", "").trim())) {
            return "MYSQL";
        }
        return dst.getDbtype();
    }

    /** MySQL 目标标识符：去引号后整体小写并用反引号包裹（与 TypeMapper 口径一致）。 */
    private static String quoteMysqlName(String name) {
        if (name == null || name.isBlank()) {
            return name == null ? "" : name;
        }
        String normalized = name.trim()
                .replace("`", "")
                .replace("\"", "")
                .replace("[", "")
                .replace("]", "");
        String[] parts = normalized.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append('`').append(parts[i].toLowerCase(java.util.Locale.ROOT)).append('`');
        }
        return sb.toString();
    }

    /** MySQL 目标索引列清单：逐个去引号、小写并反引号包裹。 */
    private static String quoteMysqlColumns(String columns) {
        if (columns == null || columns.isBlank()) {
            return columns == null ? "" : columns;
        }
        String[] parts = columns.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(quoteMysqlName(parts[i].trim()));
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

    /** 源对象位置展示：连接名 / catalog.schema.对象名（类型）。 */
    private static String sourceLocationText() {
        StringBuilder path = new StringBuilder();
        if (sourceCatalog != null) {
            path.append(sourceCatalog);
        }
        if (sourceSchema != null) {
            if (path.length() > 0) {
                path.append('.');
            }
            path.append(sourceSchema);
        }
        if (path.length() > 0) {
            path.append('.');
        }
        path.append(sourceName);
        String connName = sourceConnect == null || sourceConnect.getName() == null ? "" : sourceConnect.getName();
        return I18n.t("tablecopy.dialog.source", "源对象：%s")
                .formatted(connName + " / " + path + "（" + kindDisplayName(sourceKind) + "）");
    }

    /** 对象类型展示名（弹窗标题/源位置/通知用）。 */
    private static String kindDisplayName(CopyKind kind) {
        if (kind == null) {
            return "";
        }
        return switch (kind) {
            case TABLE -> I18n.t("tablecopy.kind.table", "表");
            case VIEW -> I18n.t("tablecopy.kind.view", "视图");
            case SEQUENCE -> I18n.t("tablecopy.kind.sequence", "序列");
            case SYNONYM -> I18n.t("tablecopy.kind.synonym", "同义词");
            case TRIGGER -> I18n.t("tablecopy.kind.trigger", "触发器");
            case FUNCTION -> I18n.t("tablecopy.kind.function", "函数");
            case PROCEDURE -> I18n.t("tablecopy.kind.procedure", "存储过程");
            case PACKAGE -> I18n.t("tablecopy.kind.package", "包");
            case INDEX -> I18n.t("tablecopy.kind.index", "索引");
            case TYPE -> I18n.t("tablecopy.kind.type", "类型");
            case QUEUE -> I18n.t("tablecopy.kind.queue", "队列");
            case JOB -> I18n.t("tablecopy.kind.job", "作业");
        };
    }

    /** 对象类型 → 树上的对象文件夹类型（粘贴成功后刷新用）。 */
    private static TreeDataLoader.ObjectFolderKind folderKindOf(CopyKind kind) {
        return switch (kind) {
            case VIEW -> TreeDataLoader.ObjectFolderKind.VIEWS;
            case SEQUENCE -> TreeDataLoader.ObjectFolderKind.SEQUENCES;
            case SYNONYM -> TreeDataLoader.ObjectFolderKind.SYNONYMS;
            case TRIGGER -> TreeDataLoader.ObjectFolderKind.TRIGGERS;
            case FUNCTION -> TreeDataLoader.ObjectFolderKind.FUNCTIONS;
            case PROCEDURE -> TreeDataLoader.ObjectFolderKind.PROCEDURES;
            case PACKAGE -> TreeDataLoader.ObjectFolderKind.PACKAGES;
            case INDEX -> TreeDataLoader.ObjectFolderKind.INDEXES;
            case TYPE -> TreeDataLoader.ObjectFolderKind.TYPES;
            case QUEUE -> TreeDataLoader.ObjectFolderKind.QUEUES;
            case JOB -> TreeDataLoader.ObjectFolderKind.JOBS;
            default -> TreeDataLoader.ObjectFolderKind.TABLES;
        };
    }

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

    /** 刷新目标库/模式下指定类型的对象文件夹（不折叠目标节点本身；文件夹未加载时不动，展开时会新加载）。 */
    private static void refreshObjectFolder(TreeItem<TreeData> node, TreeDataLoader.ObjectFolderKind kind) {
        TreeItem<TreeData> catalogItem = node;
        while (catalogItem != null && !(catalogItem.getValue() instanceof CatalogNode)) {
            catalogItem = catalogItem.getParent();
        }
        if (catalogItem == null) {
            return;
        }
        for (TreeItem<TreeData> child : catalogItem.getChildren()) {
            if (child.getValue() instanceof com.dbboys.ui.treemodel.ObjectFolder
                    && TreeDataLoader.getObjectFolderKind(child) == kind) {
                child.getChildren().clear();
                child.setExpanded(false);
                child.setExpanded(true);
                return;
            }
        }
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

    /** 把 SET ENVIRONMENT SQLMODE 'xxx'; 这类会话设置行注释掉，避免执行时切换目标会话模式。 */
    private static String commentSqlModeLine(String ddl) {
        if (ddl == null || ddl.isBlank()) {
            return ddl;
        }
        String[] lines = ddl.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.trim().matches("(?i)SET\\s+ENVIRONMENT\\s+SQLMODE\\s+.*")) {
                sb.append("-- ").append(line);
            } else {
                sb.append(line);
            }
            sb.append('\n');
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
