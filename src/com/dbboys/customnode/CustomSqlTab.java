package com.dbboys.customnode;

import com.dbboys.app.AppState;
import com.dbboys.app.Main;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.dbboys.ctrl.SqlTabController;
import com.dbboys.i18n.I18n;
import com.dbboys.util.AlertUtil;
import com.dbboys.util.TabpaneUtil;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CustomSqlTab extends CustomTab{
    //sql编辑框以上控件
    public SqlTabController sqlTabController;

    public CustomSqlTab(String title) {
        super(title);
        refreshTooltip();

        //加载图形界面
        VBox contentVBox = new VBox();
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/dbboys/fxml/SqlTab.fxml"));
        try {
            contentVBox = loader.load();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        setContent(contentVBox);

        //获取控制器实例
        sqlTabController = loader.getController();
        sqlTabController.sqlEditCodeArea.setOnSaveRequest(this::requestSave);
        sqlTabController.sqlEditCodeArea.setOnContentDirty(this::markDirty);
        sqlTabController.sqlEditCodeArea.setSaveDisabledSupplier(() -> !isDirty());

        I18n.localeProperty().addListener((obs, oldLocale, newLocale) -> refreshTooltip());

        //增加最大化时SQL编辑分隔栏到最底下，addEventHandler不会覆盖父类事件响应，而是累加
        getTitleLabel().addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                if (Main.sqledit_codearea_is_max == 1) {
                    sqlTabController.sqlSplitPane.setDividerPositions(1);
                } else {
                    sqlTabController.sqlSplitPane.setDividerPositions(sqlTabController.sqlSplitPaneDividerPosition);
                }
            }
        });

        //关闭窗口事件响应

        setOnCloseRequest(event1 -> {
            /*避免关闭后双击无响应*/
            if (AppState.getSqlTabPane().getTabs().size() == 1) {
                AppState.getSqlTabPane().setOnMouseClicked(event -> {
                    if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                        TabpaneUtil.addCustomSqlTab(null);
                    }
                });
            }
            if(isDirty()){
                if (AlertUtil.CustomAlertConfirm(
                        I18n.t("sql.tab.close_title", "关闭文件"),
                        String.format(I18n.t("sql.tab.close_confirm", "文件【%s】未保存，确定要关闭吗？"), getBaseTitle())
                )) {
                    sqlTabController.closeConn();
                }else{
                    event1.consume(); // 取消关闭
                }
            }else{
                sqlTabController.closeConn();
                // sql_tabpane.getTabs().remove(newtab);
            }

        });

    }
    //打开sql文件
    public void openSqlFile() {
        try {
            sqlTabController.sqlEditCodeArea.replaceText(Files.readString(Path.of(filePath)));
            markSaved();
            refreshTooltip();
        } catch (IOException e) {
           // log.error("Operation failed", e);
            AlertUtil.CustomAlert(I18n.t("common.error", "错误"), e.getMessage());
        }
    }

    @Override
    public void requestSave() {
        String content = sqlTabController.sqlEditCodeArea.getText();
        if (filePath.isEmpty()) {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle(I18n.t("sql.tab.save_dialog_title", "保存文件"));
            fileChooser.setInitialFileName(getBaseTitle());
            File file = fileChooser.showSaveDialog(AppState.getWindow());
            if (file != null) { //用户选择了确认
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write(content);
                    setTitle(file.getName());
                    filePath = file.getAbsolutePath();
                    markSaved();
                    refreshTooltip();
                } catch (IOException e) {
                    AlertUtil.CustomAlert(I18n.t("common.error", "错误"), e.getMessage());
                }
            }
        }else{
            try {
                Files.writeString(Paths.get(filePath), content);
                markSaved();
            } catch (IOException e) {
                AlertUtil.CustomAlert(I18n.t("common.error", "错误"), e.getMessage());
            }
        }
    }

    private void refreshTooltip() {
        setTooltip(new Tooltip(filePath.isEmpty()
                ? I18n.t("sql.tab.unsaved_path_tip", "新建脚本未保存到磁盘")
                : filePath));
    }

    private String getBaseTitle() {
        return getTitle().replace("*", "");
    }

}

