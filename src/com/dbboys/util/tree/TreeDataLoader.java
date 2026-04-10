package com.dbboys.util.tree;

import com.dbboys.api.DatabasePlatform;
import com.dbboys.api.DatabasePlatformResolver;
import com.dbboys.i18n.I18n;
import com.dbboys.api.MetaObjectService;
import com.dbboys.app.AppContext;
import com.dbboys.app.AppErrorHandler;
import com.dbboys.impl.DatabasePlatforms;
import com.dbboys.util.PopupWindowUtil;
import com.dbboys.util.SqlParserUtil;
import com.dbboys.vo.*;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.scene.control.TreeItem;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;


public class TreeDataLoader {

    public enum ObjectFolderKind {
        SYSTEM_TABLE_VIEW,
        TABLES,
        VIEWS,
        INDEXES,
        SEQUENCES,
        SYNONYMS,
        TRIGGERS,
        FUNCTIONS,
        PROCEDURES,
        PACKAGES,
        TYPES,
        QUEUES,
        JOBS,
        RECYCLE_BIN,
        UNKNOWN
    }

    //增加子节点
    public static void  treeItemAddChildrens(TreeItem<TreeData> treeItem){
        if(treeItem.getValue() instanceof Connect){
            treeItem.getChildren().add(TreeViewBuilder.createLeafTreeItem(new Connecting("Connecting")));
        }else{
            treeItem.getChildren().add(TreeViewBuilder.createLeafTreeItem(new Loading("Loading")));
        }
        if(treeItem.getValue() instanceof Connect){
            Connect connect =(Connect)treeItem.getValue();
            TreeNavigator.getMetaConnect(treeItem).executeSqlTask(
                    () -> {
                        try{
                              // 获取已经按当前数据库类型初始化过会话的连接
                              connect.setConn(TreeViewUtil.metadataService.getConnectionWithSessionInit(connect));


                            //TreeItem<TreeData> scanItem=createTreeItem(checkTreeData);
                            //TreeItem<TreeData> monItem=createTreeItem(monTreeData);

                            //查询到结果后删除loading节点
                            Platform.runLater(() -> {
                                DatabaseFolder databaseTreeData = new DatabaseFolder();
                                DatabasePlatformResolver resolver = resolvePlatformResolver();
                                var platform = resolver.requirePlatform(connect);
                                bindFolderName(
                                        databaseTreeData,
                                        platform.getDatabaseFolderI18nKey(),
                                        platform.getDatabaseFolderDefaultText()
                                );
                                TreeItem<TreeData> databaseItem = TreeViewBuilder.createTreeItem(databaseTreeData);
                                UserFolder userTreeData = new UserFolder();
                                bindFolderName(userTreeData, "metadata.folder.users", "用户");
                                TreeItem<TreeData> userItem = TreeViewBuilder.createTreeItem(userTreeData);
                                treeItem.getChildren().clear();
                                //查询到的结果添加到数据库条目下
                                treeItem.getChildren().add(databaseItem);
                                if (TreeViewUtil.userService.supportsUsers(connect)) {
                                    treeItem.getChildren().add(userItem);
                                }
                                // treeItem.getChildren().add(scanItem);
                                //treeItem.getChildren().add(monItem);
                                //addExpandedPropertyListen(treeItem);
                            });
                        } catch (SQLException e) {
                            Platform.runLater(() -> {
                                treeItem.getChildren().clear();
                                treeItem.setExpanded(false);
                            });
                            AppErrorHandler.handle(e);
                        }
                        catch (Exception e) {
                            Platform.runLater(() -> {
                                treeItem.getChildren().clear();
                                treeItem.setExpanded(false);
                            });
                            AppErrorHandler.handle(e);
                        }
                    });
        }else if(treeItem.getValue() instanceof DatabaseFolder){
            //创建子线程加载数据库
            TreeNavigator.getMetaConnect(treeItem).executeSqlTask(
                    () -> {
                          final List<Catalog> databases = new ArrayList<>();
                          try {
                              Connect connect = TreeNavigator.getMetaConnect(treeItem);
                              databases.addAll(resolvePlatformResolver().metadata(connect).getMetadataDatabases(connect.getConn()));
                          } catch (SQLException e) {
                              AppErrorHandler.handle(e);
                          }
                        //查询到结果后删除loading节点
                        if(databases.size()>0){
                            Platform.runLater(() -> {
                                treeItem.getChildren().clear();
                                //查询到的结果添加到数据库条目下
                                for (Catalog database : databases) {
                                    TreeItem<TreeData> item = TreeViewBuilder.createTreeItem(database);
                                    treeItem.getChildren().add(item);
                                }
                            });
                        }else{
                            Platform.runLater(() -> {
                                treeItem.getChildren().clear();
                                treeItem.setExpanded(false);
                            });
                        }
                    });
        }else if(treeItem.getValue() instanceof UserFolder){
            //创建子线程加载数据库
            TreeNavigator.getMetaConnect(treeItem).executeSqlTask(
                    () -> {
                          final List<User> users = new ArrayList<>();
                          try {
                              users.addAll(TreeViewUtil.userService.getUsers(TreeNavigator.getMetaConnect(treeItem), TreeNavigator.getMetaConnect(treeItem).getConn()));
                          } catch (SQLException e) {
                              AppErrorHandler.handle(e);
                          }
                        //查询到结果后删除loading节点
                        if(users.size()>0){
                            Platform.runLater(() -> {
                                treeItem.getChildren().clear();
                                //查询到的结果添加到数据库条目下
                                for (User user : users) {
                                    TreeItem<TreeData> item = TreeViewBuilder.createLeafTreeItem(user);
                                    treeItem.getChildren().add(item);
                                }
                            });
                        }else{
                            Platform.runLater(() -> {
                                treeItem.getChildren().clear();
                                treeItem.setExpanded(false);
                            });
                        }
                    });
        }
        else if(treeItem.getValue() instanceof Catalog){
            TreeNavigator.getMetaConnect(treeItem).executeSqlTask(
                    () -> {
                        ObjectList objectList;
                        try {
                            Catalog database = TreeNavigator.getCurrentDatabase(treeItem);
                            objectList = TreeViewUtil.databaseService.loadObjects(TreeNavigator.getMetaConnect(treeItem), database);
                        } catch (Exception e) {
                            AppErrorHandler.handle(e);
                            Platform.runLater(() -> {
                                treeItem.getChildren().clear();
                                treeItem.setExpanded(false);
                            });
                            return;
                        }

                        if(objectList.getSuccess()) {

                            if(objectList.getInfo()==null){
                                Platform.runLater(() -> {
                                    com.dbboys.util.NotificationUtil.showMainNotification(
                                            I18n.t("metadata.notice.database_not_found", "未找到当前数据库，数据库已被删除！"));
                                    treeItem.getParent().getChildren().remove(treeItem);
                                });
                            }else {
                                //查询到结果后删除loading节点
                                Platform.runLater(() -> {
                                    treeItem.setValue((Catalog) objectList.getInfo());
                                    treeItem.getChildren().clear();
                                    DatabasePlatform p = TreeNavigator.resolvePlatform(treeItem);
                                    List<String> items = objectList.getItems();
                                    int i = 0;
                                    ObjectFolder objectFolder = createObjectFolder(ObjectFolderKind.SYSTEM_TABLE_VIEW, p);
                                    objectFolder.setDescription(items.get(i++).toString());
                                    treeItem.getChildren().add(TreeViewBuilder.createTreeItem(objectFolder));
                                    objectFolder = createObjectFolder(ObjectFolderKind.TABLES);
                                    objectFolder.setDescription(items.get(i++).toString());
                                    treeItem.getChildren().add(TreeViewBuilder.createTreeItem(objectFolder));
                                    objectFolder = createObjectFolder(ObjectFolderKind.VIEWS);
                                    objectFolder.setDescription(items.get(i++).toString());
                                    treeItem.getChildren().add(TreeViewBuilder.createTreeItem(objectFolder));
                                    objectFolder = createObjectFolder(ObjectFolderKind.INDEXES);
                                    objectFolder.setDescription(items.get(i++).toString());
                                    treeItem.getChildren().add(TreeViewBuilder.createTreeItem(objectFolder));
                                    objectFolder = createObjectFolder(ObjectFolderKind.SEQUENCES);
                                    objectFolder.setDescription(items.get(i++).toString());
                                    treeItem.getChildren().add(TreeViewBuilder.createTreeItem(objectFolder));
                                    objectFolder = createObjectFolder(ObjectFolderKind.SYNONYMS);
                                    objectFolder.setDescription(items.get(i++).toString());
                                    treeItem.getChildren().add(TreeViewBuilder.createTreeItem(objectFolder));
                                    objectFolder = createObjectFolder(ObjectFolderKind.TRIGGERS);
                                    objectFolder.setDescription(items.get(i++).toString());
                                    treeItem.getChildren().add(TreeViewBuilder.createTreeItem(objectFolder));
                                    objectFolder = createObjectFolder(ObjectFolderKind.FUNCTIONS);
                                    objectFolder.setDescription(items.get(i++).toString());
                                    treeItem.getChildren().add(TreeViewBuilder.createTreeItem(objectFolder));
                                    objectFolder = createObjectFolder(ObjectFolderKind.PROCEDURES);
                                    objectFolder.setDescription(items.get(i++).toString());
                                    treeItem.getChildren().add(TreeViewBuilder.createTreeItem(objectFolder));
                                    if (p.supportsPackages()) {
                                        objectFolder = createObjectFolder(ObjectFolderKind.PACKAGES);
                                        objectFolder.setDescription(items.get(i++).toString());
                                        treeItem.getChildren().add(TreeViewBuilder.createTreeItem(objectFolder));
                                    }
                                    if (p.supportsObjectTypesFolder()) {
                                        objectFolder = createObjectFolder(ObjectFolderKind.TYPES);
                                        objectFolder.setDescription(items.get(i++).toString());
                                        treeItem.getChildren().add(TreeViewBuilder.createTreeItem(objectFolder));
                                    }
                                    if (p.supportsObjectQueuesFolder()) {
                                        objectFolder = createObjectFolder(ObjectFolderKind.QUEUES);
                                        objectFolder.setDescription(items.get(i++).toString());
                                        treeItem.getChildren().add(TreeViewBuilder.createTreeItem(objectFolder));
                                    }
                                    if (p.supportsSchedulerJobsFolder()) {
                                        objectFolder = createObjectFolder(ObjectFolderKind.JOBS);
                                        objectFolder.setDescription(items.get(i++).toString());
                                        treeItem.getChildren().add(TreeViewBuilder.createTreeItem(objectFolder));
                                    }
                                    if (p.supportsRecycleBinFolder()) {
                                        objectFolder = createObjectFolder(ObjectFolderKind.RECYCLE_BIN);
                                        objectFolder.setDescription(items.get(i++).toString());
                                        treeItem.getChildren().add(TreeViewBuilder.createTreeItem(objectFolder));
                                    }
                                });
                            }
                        }else{
                            Platform.runLater(() -> {
                                treeItem.getChildren().clear();
                                treeItem.setExpanded(false);
                            });
                        }
                    });
        }else if (isObjectFolder(treeItem, ObjectFolderKind.SYSTEM_TABLE_VIEW)) {
            TreeNavigator.getMetaConnect(treeItem).executeSqlTask(
                    () -> {
                        ObjectList objectList;
                        try {
                            objectList = TreeViewUtil.tableService.loadSystemTables(TreeNavigator.getMetaConnect(treeItem), TreeNavigator.getCurrentDatabase(treeItem));
                        } catch (Exception e) {
                            AppErrorHandler.handle(e);
                            Platform.runLater(() -> {
                                treeItem.getChildren().clear();
                                treeItem.setExpanded(false);
                            });
                            return;
                        }
                        if(objectList.getInfo()!=null&&!objectList.getItems().isEmpty()) {
                            Platform.runLater(() -> {
                                ((ObjectFolder) treeItem.getValue()).setDescription((String)objectList.getInfo());
                                treeItem.getChildren().clear();
                                List<SysTable> systables = objectList.getItems();
                                for (SysTable tabname : systables) {
                                    TreeItem<TreeData> item = TreeViewBuilder.createLeafTreeItem(tabname);
                                    treeItem.getChildren().add(item);
                                }
                            });
                        }else{
                            Platform.runLater(() -> {
                                treeItem.getChildren().clear();
                                treeItem.setExpanded(false);
                            });
                        }
                    });
        }else if (isLoadableObjectFolder(treeItem)) {
            TreeNavigator.getMetaConnect(treeItem).executeSqlTask(
                    () -> {
                        ObjectList objectList;
                        ObjectFolderKind kind = getObjectFolderKind(treeItem);
                        MetaObjectService service = getMetaObjectService(kind);
                        if (service == null) {
                            return;
                        }
                        try {
                            objectList = service.loadObjects(TreeNavigator.getMetaConnect(treeItem), TreeNavigator.getCurrentDatabase(treeItem));
                        } catch (Exception e) {
                            AppErrorHandler.handle(e);
                            Platform.runLater(() -> {
                                treeItem.getChildren().clear();
                                treeItem.setExpanded(false);
                            });
                            return;
                        }
                        if (objectList.getInfo() != null ) {
                            Platform.runLater(() -> {
                                ((ObjectFolder) treeItem.getValue()).setDescription((String) objectList.getInfo());
                                treeItem.getChildren().clear();
                                appendObjectFolderChildren(treeItem, kind, objectList);
                            });
                        } else {
                            Platform.runLater(() -> {
                                treeItem.getChildren().clear();
                                treeItem.setExpanded(false);
                            });
                        }
                    });
        }else if(treeItem.getValue() instanceof DBPackage){
            TreeNavigator.getMetaConnect(treeItem).executeSqlTask(
                    () -> {
                        String packageDDL = "";
                        try {
                            //Object parentValue = treeItem.getParent() == null ? null : treeItem.getParent().getValue();
                            packageDDL = TreeViewUtil.packageService.getDDL(TreeNavigator.getMetaConnect(treeItem), TreeNavigator.getCurrentDatabase(treeItem),treeItem.getValue().getName());
                    
                        } catch (Exception e) {
                            AppErrorHandler.handle(e);
                        }
                        if (!packageDDL.isEmpty()) {
                            ((DBPackage) treeItem.getValue()).setDDL(packageDDL);

                            List<SqlParserUtil.PackageMember> members = SqlParserUtil.parsePackageMembers(packageDDL);

                            Platform.runLater(() -> {
                                treeItem.getChildren().clear();
                            });

                            for (SqlParserUtil.PackageMember member : members) {
                                if ("FUNC".equals(member.getType())) {
                                    String functionname = member.getName();
                                    PackageFunction packageFunction = new PackageFunction(functionname);
                                    packageFunction.setDescription("FUNC");
                                    TreeItem<TreeData> item = TreeViewBuilder.createLeafTreeItem(packageFunction);
                                    Platform.runLater(() -> {
                                        treeItem.getChildren().add(item);
                                    });
                                }
                                if ("PROC".equals(member.getType())) {
                                    String functionname = member.getName();
                                    PackageProcedure packageProcedure = new PackageProcedure(functionname);
                                    packageProcedure.setDescription("PROC");
                                    TreeItem<TreeData> item = TreeViewBuilder.createLeafTreeItem(packageProcedure);
                                    Platform.runLater(() -> {
                                        treeItem.getChildren().add(item);
                                    });
                                }
                            }

                            if (((DBPackage) treeItem.getValue()).getShowDDL()) {
                                String finalPackageDDL = packageDDL;
                                Platform.runLater(() -> {
                                    PopupWindowUtil.openDDLWindow(finalPackageDDL);
                                    Platform.runLater(() -> {
                                        ((DBPackage) treeItem.getValue()).setShowDDL(false);
                                    });
                                });
                            }
                        } else {
                            Platform.runLater(() -> {
                                treeItem.getChildren().clear();
                                treeItem.setExpanded(false);
                            });
                        }

                    });

        }



    }

    @SuppressWarnings("unchecked")
    public static void appendObjectFolderChildren(TreeItem<TreeData> treeItem, ObjectFolderKind kind, ObjectList objectList) {
        switch (kind) {
            case TABLES -> {
                List<Table> tables = objectList.getItems();
                for (Table tab : tables) {
                    treeItem.getChildren().add(TreeViewBuilder.createLeafTreeItem(tab));
                }
            }
            case VIEWS -> {
                List<View> views = objectList.getItems();
                for (View view : views) {
                    treeItem.getChildren().add(TreeViewBuilder.createLeafTreeItem(view));
                }
            }
            case INDEXES -> {
                List<Index> indexes = objectList.getItems();
                for (Index index : indexes) {
                    treeItem.getChildren().add(TreeViewBuilder.createLeafTreeItem(index));
                }
            }
            case SEQUENCES -> {
                List<Sequence> sequences = objectList.getItems();
                for (Sequence sequence : sequences) {
                    treeItem.getChildren().add(TreeViewBuilder.createLeafTreeItem(sequence));
                }
            }
            case SYNONYMS -> {
                List<Synonym> synonyms = objectList.getItems();
                for (Synonym synonym : synonyms) {
                    treeItem.getChildren().add(TreeViewBuilder.createLeafTreeItem(synonym));
                }
            }
            case TRIGGERS -> {
                List<Trigger> triggers = objectList.getItems();
                for (Trigger trigger : triggers) {
                    treeItem.getChildren().add(TreeViewBuilder.createLeafTreeItem(trigger));
                }
            }
            case FUNCTIONS -> {
                List<Function> functions = objectList.getItems();
                for (Function function : functions) {
                    treeItem.getChildren().add(TreeViewBuilder.createLeafTreeItem(function));
                }
            }
            case PROCEDURES -> {
                List<Procedure> procedures = objectList.getItems();
                for (Procedure procedure : procedures) {
                    treeItem.getChildren().add(TreeViewBuilder.createLeafTreeItem(procedure));
                }
            }
            case PACKAGES -> {
                List<DBPackage> packages = objectList.getItems();
                for (DBPackage pkg : packages) {
                    treeItem.getChildren().add(TreeViewBuilder.createTreeItem(pkg));
                }
            }
            case TYPES -> {
                List<Type> types = objectList.getItems();
                for (Type row : types) {
                    treeItem.getChildren().add(TreeViewBuilder.createLeafTreeItem(row));
                }
            }
            case QUEUES -> {
                List<Queue> queues = objectList.getItems();
                for (Queue row : queues) {
                    treeItem.getChildren().add(TreeViewBuilder.createLeafTreeItem(row));
                }
            }
            case JOBS -> {
                List<SchedulerJob> jobs = objectList.getItems();
                for (SchedulerJob row : jobs) {
                    treeItem.getChildren().add(TreeViewBuilder.createLeafTreeItem(row));
                }
            }
            case RECYCLE_BIN -> {
                List<RecycleBinObject> rows = objectList.getItems();
                for (RecycleBinObject row : rows) {
                    treeItem.getChildren().add(TreeViewBuilder.createLeafTreeItem(row));
                }
            }
            default -> {
            }
        }
    }

    public static ObjectFolder createObjectFolder(ObjectFolderKind kind, DatabasePlatform platform) {
        ObjectFolder objectFolder = createObjectFolder(kind);
        if (kind == ObjectFolderKind.SYSTEM_TABLE_VIEW && platform != null) {
            bindFolderName(objectFolder, platform.getSystemTableFolderI18nKey(), platform.getSystemTableFolderDefaultText());
        }
        return objectFolder;
    }

    public static ObjectFolder createObjectFolder(ObjectFolderKind kind) {
        ObjectFolder objectFolder = new ObjectFolder();
        if (kind != null && kind != ObjectFolderKind.UNKNOWN) {
            objectFolder.setKindTag(kind.name());
        }
        switch (kind) {
            case SYSTEM_TABLE_VIEW -> bindFolderName(objectFolder, "metadata.folder.system_table_view", "系统表/视图");
            case TABLES -> bindFolderName(objectFolder, "metadata.folder.tables", "表");
            case VIEWS -> bindFolderName(objectFolder, "metadata.folder.views", "视图");
            case INDEXES -> bindFolderName(objectFolder, "metadata.folder.indexes", "索引");
            case SEQUENCES -> bindFolderName(objectFolder, "metadata.folder.sequences", "序列");
            case SYNONYMS -> bindFolderName(objectFolder, "metadata.folder.synonyms", "同义词");
            case TRIGGERS -> bindFolderName(objectFolder, "metadata.folder.triggers", "触发器");
            case FUNCTIONS -> bindFolderName(objectFolder, "metadata.folder.functions", "函数");
            case PROCEDURES -> bindFolderName(objectFolder, "metadata.folder.procedures", "存储过程");
            case PACKAGES -> bindFolderName(objectFolder, "metadata.folder.packages", "包");
            case TYPES -> bindFolderName(objectFolder, "metadata.folder.types", "类型");
            case QUEUES -> bindFolderName(objectFolder, "metadata.folder.queues", "队列");
            case JOBS -> bindFolderName(objectFolder, "metadata.folder.jobs", "作业");
            case RECYCLE_BIN -> bindFolderName(objectFolder, "metadata.folder.recycle_bin", "回收站");
            default -> objectFolder.setName("");
        }
        return objectFolder;
    }

    public static void bindFolderName(TreeData treeData, String key, String defaultText) {
        treeData.nameProperty().unbind();
        treeData.nameProperty().bind(Bindings.createStringBinding(
                () -> I18n.t(key, defaultText),
                I18n.localeProperty()
        ));
    }

    public static MetaObjectService getMetaObjectService(ObjectFolderKind kind) {
        return switch (kind) {
            case TABLES -> TreeViewUtil.tableService;
            case VIEWS -> TreeViewUtil.viewService;
            case INDEXES -> TreeViewUtil.indexService;
            case SEQUENCES -> TreeViewUtil.sequenceService;
            case SYNONYMS -> TreeViewUtil.synonymService;
            case TRIGGERS -> TreeViewUtil.triggerService;
            case FUNCTIONS -> TreeViewUtil.functionService;
            case PROCEDURES -> TreeViewUtil.procedureService;
            case PACKAGES -> TreeViewUtil.packageService;
            case TYPES -> TreeViewUtil.objectTypeService;
            case QUEUES -> TreeViewUtil.queueService;
            case JOBS -> TreeViewUtil.schedulerJobService;
            case RECYCLE_BIN -> TreeViewUtil.recycleBinService;
            default -> null;
        };
    }

    public static ObjectFolderKind getObjectFolderKind(TreeItem<TreeData> treeItem) {
        if (treeItem == null || !(treeItem.getValue() instanceof ObjectFolder)) {
            return ObjectFolderKind.UNKNOWN;
        }
        ObjectFolder folder = (ObjectFolder) treeItem.getValue();
        String key = folder.getKindTag();
        if (key != null && !key.isBlank()) {
            try {
                return ObjectFolderKind.valueOf(key);
            } catch (IllegalArgumentException e) {
                return ObjectFolderKind.UNKNOWN;
            }
        }
        TreeItem<TreeData> parent = treeItem.getParent();
        if (parent == null) {
            return ObjectFolderKind.UNKNOWN;
        }
        int index = parent.getChildren().indexOf(treeItem);
        return switch (index) {
            case 0 -> ObjectFolderKind.SYSTEM_TABLE_VIEW;
            case 1 -> ObjectFolderKind.TABLES;
            case 2 -> ObjectFolderKind.VIEWS;
            case 3 -> ObjectFolderKind.INDEXES;
            case 4 -> ObjectFolderKind.SEQUENCES;
            case 5 -> ObjectFolderKind.SYNONYMS;
            case 6 -> ObjectFolderKind.TRIGGERS;
            case 7 -> ObjectFolderKind.FUNCTIONS;
            case 8 -> ObjectFolderKind.PROCEDURES;
            case 9 -> ObjectFolderKind.PACKAGES;
            default -> ObjectFolderKind.UNKNOWN;
        };
    }

    public static boolean isObjectFolder(TreeItem<TreeData> treeItem, ObjectFolderKind kind) {
        return treeItem != null
                && treeItem.getValue() instanceof ObjectFolder
                && getObjectFolderKind(treeItem) == kind;
    }

    public static boolean isLoadableObjectFolder(TreeItem<TreeData> treeItem) {
        ObjectFolderKind kind = getObjectFolderKind(treeItem);
        return kind == ObjectFolderKind.TABLES
                || kind == ObjectFolderKind.VIEWS
                || kind == ObjectFolderKind.INDEXES
                || kind == ObjectFolderKind.SEQUENCES
                || kind == ObjectFolderKind.SYNONYMS
                || kind == ObjectFolderKind.TRIGGERS
                || kind == ObjectFolderKind.FUNCTIONS
                || kind == ObjectFolderKind.PROCEDURES
                || kind == ObjectFolderKind.PACKAGES
                || kind == ObjectFolderKind.TYPES
                || kind == ObjectFolderKind.QUEUES
                || kind == ObjectFolderKind.JOBS
                || kind == ObjectFolderKind.RECYCLE_BIN;
    }

    private static DatabasePlatformResolver resolvePlatformResolver() {
        try {
            return AppContext.get(DatabasePlatformResolver.class);
        } catch (Exception e) {
            return DatabasePlatforms.createDefault();
        }
    }
}
