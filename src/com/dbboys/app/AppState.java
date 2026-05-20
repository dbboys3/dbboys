package com.dbboys.app;

import com.dbboys.ctrl.MainController;
import com.dbboys.vo.Connect;
import com.dbboys.vo.Version;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import com.dbboys.customnode.CustomShortcutMenuItem;
import com.dbboys.customnode.CustomTreeviewTab;
import com.dbboys.customnode.CustomUserTextField;
import com.dbboys.util.ConfigManagerUtil;
import com.dbboys.vo.TreeData;

import java.util.List;

public final class AppState {
    private static final ObjectProperty<MainController> mainController = new SimpleObjectProperty<>();
    private static final ObjectProperty<Scene> scene = new SimpleObjectProperty<>();
    private static final DoubleProperty split1Pos = new SimpleDoubleProperty(0.2);
    private static final DoubleProperty split2Pos = new SimpleDoubleProperty(0.6);
    private static final ObjectProperty<Version> version = new SimpleObjectProperty<>();
    private static double sqlEditCodeAreaIsMax = 0;
    private static final ObjectProperty<ProgressBar> loadProgressBar = new SimpleObjectProperty<>(new ProgressBar(0.1));
    private static Connect lastInstallConnect;

    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";
    private static final String UI_THEME_KEY = "UI_THEME";
    private static final String DARK_STYLESHEET =
            AppState.class.getResource("/com/dbboys/css/cupertino-dark.css").toExternalForm();
    private static final String COMMON_STYLESHEET =
            AppState.class.getResource("/com/dbboys/css/cupertino-common.css").toExternalForm();
    private static final String LIGHT_STYLESHEET =
            AppState.class.getResource("/com/dbboys/css/cupertino-light.css").toExternalForm();

    private AppState() {}

    @Deprecated
    public static String getAppStylesheet() {
        return getCurrentThemeStylesheet();
    }

    public static List<String> getAppStylesheets() {
        return List.of(COMMON_STYLESHEET, getCurrentThemeStylesheet());
    }

    public static String getCurrentTheme() {
        String theme = ConfigManagerUtil.getProperty(UI_THEME_KEY, THEME_DARK);
        return THEME_LIGHT.equalsIgnoreCase(theme) ? THEME_LIGHT : THEME_DARK;
    }

    public static void setCurrentTheme(String theme) {
        ConfigManagerUtil.setProperty(UI_THEME_KEY, THEME_LIGHT.equalsIgnoreCase(theme) ? THEME_LIGHT : THEME_DARK);
        applyAppStylesheet(getScene());
    }

    private static String getCurrentThemeStylesheet() {
        return THEME_LIGHT.equals(getCurrentTheme()) ? LIGHT_STYLESHEET : DARK_STYLESHEET;
    }

    public static void applyAppStylesheet(Scene s) {
        if (s == null) {
            return;
        }
        s.getStylesheets().remove(DARK_STYLESHEET);
        s.getStylesheets().remove(COMMON_STYLESHEET);
        s.getStylesheets().remove(LIGHT_STYLESHEET);
        s.getStylesheets().addAll(getAppStylesheets());
    }

    public static void applyAppStylesheet(javafx.scene.control.Alert alert) {
        if (alert != null) applyAppStylesheet(alert.getDialogPane().getScene());
    }

    // --- MainController ---
    public static MainController getMainController() { return mainController.get(); }
    public static void setMainController(MainController ctrl) { mainController.set(ctrl); }

    // --- Scene ---
    public static Scene getScene() { return scene.get(); }
    public static void setScene(Scene s) { scene.set(s); }
    public static Window getWindow() {
        Scene s = getScene();
        return s == null ? null : s.getWindow();
    }

    // --- Split positions ---
    public static double getSplit1Pos() { return split1Pos.get(); }
    public static void setSplit1Pos(double v) { split1Pos.set(v); }
    public static DoubleProperty split1PosProperty() { return split1Pos; }

    public static double getSplit2Pos() { return split2Pos.get(); }
    public static void setSplit2Pos(double v) { split2Pos.set(v); }
    public static DoubleProperty split2PosProperty() { return split2Pos; }

    // --- Version ---
    public static Version getVersion() { return version.get(); }
    public static void setVersion(Version v) { version.set(v); }

    // --- Misc ---
    public static double getSqlEditCodeAreaIsMax() { return sqlEditCodeAreaIsMax; }
    public static void setSqlEditCodeAreaIsMax(double v) { sqlEditCodeAreaIsMax = v; }

    public static ProgressBar getLoadProgressBar() { return loadProgressBar.get(); }

    public static Connect getLastInstallConnect() { return lastInstallConnect; }
    public static void setLastInstallConnect(Connect c) { lastInstallConnect = c; }

    // --- Convenience delegates to MainController's public FXML fields ---
    public static StackPane getNoticePane() {
        return getMainController() == null ? null : getMainController().noticePane;
    }

    public static TabPane getSqlTabPane() {
        return getMainController() == null ? null : getMainController().sqlTabPane;
    }

    public static TreeView<TreeData> getDatabaseMetaTreeView() {
        return getMainController() == null ? null : getMainController().databaseMetaTreeView;
    }

    public static SplitPane getMainSplitPane() {
        return getMainController() == null ? null : getMainController().mainSplitPane;
    }

    public static TabPane getTreeviewTabPane() {
        return getMainController() == null ? null : getMainController().treeviewTabPane;
    }

    public static Button getStatusBackSqlStopButton() {
        return getMainController() == null ? null : getMainController().statusBackSqlStopButton;
    }

    public static Label getStatusBackSqlCountLabel() {
        return getMainController() == null ? null : getMainController().statusBackSqlCountLabel;
    }

    public static StackPane getDownloadStackPane() {
        return getMainController() == null ? null : getMainController().downloadStackPane;
    }

    public static CustomShortcutMenuItem getNewSqlFileMenuItem() {
        return getMainController() == null ? null : getMainController().newSqlFileMenuItem;
    }

    public static Button getRebuildMarkdownIndexButton() {
        return getMainController() == null ? null : getMainController().rebuildMarkdownIndexButton;
    }

    public static HBox getStatusHBox() {
        return getMainController() == null ? null : getMainController().statusHBox;
    }

    public static CustomUserTextField getConnectSearchTextField() {
        return getMainController() == null ? null : getMainController().connectSearchTextField;
    }

    public static CustomUserTextField getMarkdownSearchTextField() {
        return getMainController() == null ? null : getMainController().markdownSearchTextField;
    }

    public static Button getSnapshotRootButton() {
        return getMainController() == null ? null : getMainController().snapshotRootButton;
    }

    public static VBox getMarkdownTreeViewVBox() {
        return getMainController() == null ? null : getMainController().markdownTreeViewVBox;
    }

    public static CustomTreeviewTab getConnectTab() {
        return getMainController() == null ? null : getMainController().connectTab;
    }

    public static CustomTreeviewTab getMarkdownTab() {
        return getMainController() == null ? null : getMainController().markdownTab;
    }

    public static VBox getMainVBox() {
        return getMainController() == null ? null : getMainController().mainVBox;
    }

    public static ImageView getStatusBackSqlProgress() {
        return getMainController() == null ? null : getMainController().statusBackSqlProgress;
    }

    public static StackPane getRebuildMarkdownIndexButtonStackpane() {
        return getMainController() == null ? null : getMainController().rebuildMarkdownIndexButton_stackpane;
    }

    public static StackPane getMarkdownSearchIconStackPane() {
        return getMainController() == null ? null : getMainController().markdownSearchIconStackPane;
    }

    public static Button getCreateConnect() {
        return getMainController() == null ? null : getMainController().create_connect;
    }

    public static Button getConnectSearchButton() {
        return getMainController() == null ? null : getMainController().connectSearchButton;
    }

    public static Button getMarkdownSearchButton() {
        return getMainController() == null ? null : getMainController().markdownSearchButton;
    }

    public static Button getStatusBackSqlListButton() {
        return getMainController() == null ? null : getMainController().statusBackSqlListButton;
    }

    public static void checkVersion() {
        if (getMainController() != null) getMainController().checkVersion();
    }

    public static void createConnectLeaf() {
        if (getMainController() != null) getMainController().createConnectLeaf();
    }

    public static javafx.scene.Parent getSceneRoot() {
        Scene s = getScene();
        return s == null ? null : s.getRoot();
    }
}
