package com.dbboys.ui.component;

import com.dbboys.app.AppState;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.infra.util.MenuItemUtil;
import com.dbboys.infra.util.TabpaneUtil;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;

public class CustomTab extends Tab {
    private static final String DIRTY_PREFIX = "*";
    private static boolean doubleClickHandlerInstalled = false;
    public String filePath="";
    private boolean dirty = false;

    public CustomTab(String title) {
        //如果有面板，去掉双击响应事件
        if (AppState.getSqlTabPane() != null) {
            AppState.getSqlTabPane().setOnMouseClicked(null);
        }
        //super(title);
        //设置标题保证标题溢出下拉正常显示标题
        setText(title);
        // 安装 SQL 标签页区域的双击最大化处理（全局只装一次）
        installSqlTabPaneDoubleClickHandler();

        ContextMenu tabMenu = new ContextMenu();
        setContextMenu(tabMenu);
        CustomShortcutMenuItem closeAllItem = MenuItemUtil.createMenuItemI18n(
                "customtab.menu.close_all",
                IconFactory.group(IconPaths.TAB_CLOSE_MENU_ITEM, 0.5, 0.5, Color.valueOf("#b33029"))
        );
        CustomShortcutMenuItem closeOthersItem = MenuItemUtil.createMenuItemI18n(
                "customtab.menu.close_others",
                IconFactory.group(IconPaths.TAB_CLOSE_MENU_ITEM, 0.5, 0.5, Color.valueOf("#b33029"))
        );

        tabMenu.getItems().addAll(closeOthersItem,closeAllItem);



        closeAllItem.setOnAction(event -> {
            TabpaneUtil.closeAllTabs();
        });

        closeOthersItem.setOnAction(event -> {
            TabpaneUtil.closeOtherTabs(this);
        });


        setOnCloseRequest(event1 -> {
            /*避免关闭后双击无响应*/
            if (AppState.getSqlTabPane() != null && AppState.getSqlTabPane().getTabs().size() == 1) {
                AppState.getSqlTabPane().setOnMouseClicked(event -> {
                    if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                        TabpaneUtil.addCustomSqlTab(null);
                    }
                });
            }
        });


    }
    private static void installSqlTabPaneDoubleClickHandler() {
        if (doubleClickHandlerInstalled) {
            return;
        }
        TabPane sqlTabPane = AppState.getSqlTabPane();
        if (sqlTabPane == null) {
            return;
        }
        doubleClickHandlerInstalled = true;
        sqlTabPane.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() != MouseButton.PRIMARY || event.getClickCount() != 2) {
                return;
            }
            // 只在点击 Tab 头部时响应
            if (!(event.getTarget() instanceof javafx.scene.Node target)) {
                return;
            }
            javafx.scene.Node node = target;
            boolean inTabHeader = false;
            while (node != null && !(node instanceof TabPane)) {
                if (node.getStyleClass().contains("tab")) {
                    inTabHeader = true;
                    break;
                }
                node = node.getParent();
            }
            if (!inTabHeader) {
                return;
            }

            Tab selected = sqlTabPane.getSelectionModel().getSelectedItem();
            if (!(selected instanceof CustomTab customTab)) {
                return;
            }
            customTab.toggleMaximize();
        });
    }

    protected void toggleMaximize() {
        if(AppState.getSqlEditCodeAreaIsMax() == 0)
        {
            for(Tab tab:AppState.getTreeviewTabPane().getTabs()){
                if(((CustomTreeviewTab)tab).titleToggle.isSelected()){
                    ((CustomTreeviewTab)tab).titleToggle.setSelected(false);
                }
            }
            AppState.getMainSplitPane().setDividerPositions(0);
            AppState.setSqlEditCodeAreaIsMax(1);
        }else{
            for(Tab tab:AppState.getTreeviewTabPane().getTabs()){
                if(((CustomTreeviewTab)tab).isSelected()){
                    ((CustomTreeviewTab)tab).titleToggle.setSelected(true);
                }
            }
            AppState.getMainSplitPane().setDividerPositions(AppState.getSplit1Pos());
            AppState.setSqlEditCodeAreaIsMax(0);
        }
        if(this instanceof CustomSqlTab){
            Platform.runLater(()->{
                ((CustomSqlTab) this).sqlTabController.sqlEditCodeArea.requestFocus();
            });
        }
    }
    public String getTitle(){
        return getText();
    }
    public void setTitle(String title){
        String baseTitle = title == null ? "" : title.replace(DIRTY_PREFIX, "");
        String displayTitle = dirty ? DIRTY_PREFIX + baseTitle : baseTitle;
        setText(displayTitle);
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        setDirty(true);
    }

    public void markSaved() {
        setDirty(false);
    }

    protected void setDirty(boolean dirty) {
        if (this.dirty == dirty) {
            return;
        }
        this.dirty = dirty;
        setTitle(getTitle());
    }

    public void requestSave() {
        // Override in subclasses that support save.
    }


}
