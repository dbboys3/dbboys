package com.dbboys.ctrl;

import com.dbboys.app.AppExecutor;
import com.dbboys.app.AppState;
import com.dbboys.customnode.*;
import com.dbboys.i18n.I18n;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.util.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

public class SqlTabUiHelper {
    private static final Logger log = LogManager.getLogger(SqlTabUiHelper.class);

    private final SqlTabController ctrl;

    public SqlTabUiHelper(SqlTabController ctrl) {
        this.ctrl = ctrl;
    }

    public void setupTransactionTooltips() {
        ctrl.commitButtonTooltip.textProperty().bind(ctrl.sqlTransactionText);
        ctrl.commitButtonTooltip.setShowDelay(Duration.millis(100));
        ctrl.transactionCommitButton.setTooltip(ctrl.commitButtonTooltip);
        ctrl.transactionRollbackButton.setTooltip(ctrl.commitButtonTooltip);
    }

    public void setupSqlTabIcons() {
        ctrl.sqlExecuteLoadingLabel.setGraphic(IconFactory.imageView(IconPaths.LOADING_GIF, 12, 12, true));
        ctrl.sqlRunButton.setGraphic(IconFactory.group(IconPaths.SQL_RUN, 0.7, Color.valueOf("#074675")));
        ctrl.sqlExplainButton.setGraphic(IconFactory.group(IconPaths.SQL_EXPLAIN, 0.7, Color.valueOf("#074675")));
        ctrl.sqlStopButton.setGraphic(IconFactory.groupFixedColor(IconPaths.SQL_STOP, 0.75, IconFactory.stopColor()));
        ctrl.sqlRecordButton.setGraphic(IconFactory.group(IconPaths.SQL_HISTORY, 0.8, Color.valueOf("#074675")));
        ctrl.sqlReadOnlyLabel.setGraphic(IconFactory.group(IconPaths.SQL_READONLY, 0.5, Color.valueOf("#9f453c")));

        if (ctrl.sqlDbIconPane != null) {
            ctrl.sqlDbIconPane.getChildren().setAll(IconFactory.group(IconPaths.SQL_DATABASE, 0.4, Color.valueOf("#888")));
        }
        if (ctrl.sqlUserIconPane != null) {
            ctrl.sqlUserIconPane.getChildren().setAll(IconFactory.group(IconPaths.SQL_USER, 0.55, Color.valueOf("#888")));
        }
    }

    public void setupSearchReplacePanel() {
        ctrl.searchReplaceBox.setMaxWidth(300);
        ctrl.searchReplaceBox.setMaxHeight(26);
        StackPane.setAlignment(ctrl.searchReplaceBox, Pos.TOP_RIGHT);
        StackPane.setMargin(ctrl.searchReplaceBox, new Insets(2, 17, 0, 0));
        ctrl.sqlEditStackPane.getChildren().add(ctrl.searchReplaceBox);
        ctrl.sqlEditCodeArea.setOnShowFindPanel(ctrl.searchReplaceBox::showFindPanel);
        ctrl.sqlEditCodeArea.setOnShowReplacePanel(ctrl.searchReplaceBox::showReplacePanel);
    }

    public void setupResultSetView() throws IOException {
        ctrl.sqlSqlModeChoiceBox.setVisible(false);

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/dbboys/fxml/ResultSetTab.fxml"));
        loader.setControllerFactory(clazz -> {
            if (clazz == ResultSetTabController.class) {
                return new ResultSetTabController(ctrl.sqlConnect, ctrl.sqlExecuteProcessStackPane);
            }
            try {
                return clazz.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                throw new RuntimeException(e);
            }
        });
        ctrl.resultSetVBox = loader.load();
        ctrl.resultsetStackPane.getChildren().add(ctrl.resultSetVBox);
        ctrl.currentResultSetTabController = loader.getController();
        ctrl.currentResultSetTabController.hiddenDisconnectedButton.setOnAction(event -> ctrl.connectionDisconnected());

        ctrl.explain_result_stackpane = new CustomInfoStackPane(new CustomInfoCodeArea());
        ctrl.explain_result_stackpane.setVisible(false);
        ctrl.resultsetStackPane.getChildren().add(ctrl.explain_result_stackpane);

        ctrl.currentResultSetTabController.lastSqlRefreshButton.setOnAction(event -> {
            ctrl.isSqlRefresh = true;
            ctrl.sqlRunButton.fire();
        });
    }

    public void setupSplitPaneBehavior() {
        ctrl.sqlSplitPane.setDividerPositions(AppState.getSqlEditCodeAreaIsMax() == 1 ? 1 : ctrl.sqlSplitPaneDividerPosition);
        ctrl.sqlSplitPane.heightProperty().addListener((obs, oldVal, newVal) -> {
            if (AppState.getSqlEditCodeAreaIsMax() == 1) {
                Platform.runLater(() -> ctrl.sqlSplitPane.setDividerPositions(1));
            } else {
                Platform.runLater(() -> ctrl.sqlSplitPane.setDividerPositions(ctrl.sqlSplitPaneDividerPosition));
            }
        });

        AppExecutor.runAsync(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            ctrl.sqlSplitPane.lookupAll(".split-pane-divider").forEach(divider -> {
                divider.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
                    ctrl.sqlSplitPaneDividerPosition = ctrl.sqlSplitPane.getDividers().get(0).getPosition();
                });
            });
        });
    }

    public void bindEditorSizeToPane() {
        ctrl.sqlEditCodeArea.prefWidthProperty().bind(ctrl.topPane.widthProperty());
        ctrl.sqlEditCodeArea.prefHeightProperty().bind(ctrl.topPane.heightProperty());
    }

    public void setupConnectIcons() {
        ctrl.sqlConnectChoiceBoxDbIcon = new Label();
        ctrl.sqlConnectChoiceBoxLoadingIcon = new Label();
        ctrl.sqlConnectChoiceBoxLoadingIcon.setVisible(false);
        ctrl.sqlConnectChoiceBoxIconStackPane.getChildren().addAll(ctrl.sqlConnectChoiceBoxDbIcon, ctrl.sqlConnectChoiceBoxLoadingIcon);

        ctrl.sqlConnectIconPath = IconFactory.create(IconPaths.CONNECTION_LINK, 0.6, 0.6, Color.valueOf("#888"));
        ctrl.sqlConnectChoiceBoxDbIcon.setGraphic(new Group(ctrl.sqlConnectIconPath));

        ImageView loadingIcon = IconFactory.loadingImageView(0.7);
        ctrl.sqlConnectChoiceBoxLoadingIcon.setGraphic(loadingIcon);
        Tooltip tooltip = new Tooltip();
        tooltip.textProperty().bind(I18n.bind("sql.tooltip.connecting"));
        tooltip.setShowDelay(Duration.millis(100));
        ctrl.sqlConnectChoiceBoxLoadingIcon.setTooltip(tooltip);

        ctrl.sqlConnectIconPath.setFill(Paint.valueOf("#888"));
    }
}
