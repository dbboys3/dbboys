package com.dbboys.ctrl;

import com.dbboys.app.AppExecutor;
import com.dbboys.app.AppState;
import com.dbboys.customnode.*;
import com.dbboys.i18n.I18n;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
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
    /** Same slot as {@link com.dbboys.customnode.CustomTreeCell} connect icons — stable toolbar alignment. */
    private static final double SQL_HEADER_ICON_SLOT = 16.0;

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
        ctrl.sqlExecuteLoadingLabel.setGraphic(IconFactory.imageView(IconPaths.LOADING_GIF, 11, 11, true));
        ctrl.sqlRunButton.setGraphic(IconFactory.group(IconPaths.SQL_RUN, 0.8, Color.valueOf("#51dd66")));
        ctrl.sqlExplainButton.setGraphic(IconFactory.group(IconPaths.SQL_EXPLAIN, 0.45));
        ctrl.sqlStopButton.setGraphic(IconFactory.groupFixedColor(IconPaths.SQL_STOP, 0.7, IconFactory.stopColor()));
        ctrl.sqlRecordButton.setGraphic(IconFactory.group(IconPaths.SQL_HISTORY, 0.8));
        ctrl.sqlReadOnlyLabel.setGraphic(IconFactory.group(IconPaths.SQL_READONLY, 0.5, IconFactory.stopColor()));

        if (ctrl.sqlDbIconPane != null) {
            ctrl.sqlDbIconPane.setMinSize(SQL_HEADER_ICON_SLOT, SQL_HEADER_ICON_SLOT);
            ctrl.sqlDbIconPane.setPrefSize(SQL_HEADER_ICON_SLOT, SQL_HEADER_ICON_SLOT);
            ctrl.sqlDbIconPane.setMaxSize(SQL_HEADER_ICON_SLOT, SQL_HEADER_ICON_SLOT);
            ctrl.sqlDbIconPane.setAlignment(Pos.CENTER);
            ctrl.sqlDbIconPane.getChildren().setAll(IconFactory.group(IconPaths.SQL_DATABASE, 0.4, Color.valueOf("rgb(220,220,220)")));
            ctrl.sqlDbIconPane.opacityProperty().bind(
                    Bindings.when(ctrl.sqlConnectChoiceBox.disableProperty()).then(0.4).otherwise(1.0)
            );
        }
        if (ctrl.sqlUserIconPane != null) {
            ctrl.sqlUserIconPane.getChildren().setAll(IconFactory.group(IconPaths.SQL_USER, 0.55, Color.valueOf("rgb(220,220,220)")));
            ctrl.sqlUserIconPane.opacityProperty().bind(
                    Bindings.when(ctrl.sqlConnectChoiceBox.disableProperty()).then(0.4).otherwise(1.0)
            );
        }
    }

    public void setupSearchReplacePanel() {
        ctrl.searchReplaceBox.setMaxWidth(CustomSearchReplaceVbox.EDIT_PANEL_WIDTH);
        ctrl.searchReplaceBox.setMaxHeight(Region.USE_PREF_SIZE);
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
        Group connectGraphic = new Group(ctrl.sqlConnectIconPath);
        StackPane connectSlot = new StackPane(connectGraphic);
        connectSlot.setMinSize(SQL_HEADER_ICON_SLOT, SQL_HEADER_ICON_SLOT);
        connectSlot.setPrefSize(SQL_HEADER_ICON_SLOT, SQL_HEADER_ICON_SLOT);
        connectSlot.setMaxSize(SQL_HEADER_ICON_SLOT, SQL_HEADER_ICON_SLOT);
        connectSlot.setAlignment(Pos.CENTER);
        ctrl.sqlConnectChoiceBoxDbIcon.setGraphic(connectSlot);

        ImageView loadingIcon = IconFactory.loadingImageView(0.7);
        ctrl.sqlConnectChoiceBoxLoadingIcon.setGraphic(loadingIcon);
        Tooltip tooltip = new Tooltip();
        tooltip.textProperty().bind(I18n.bind("sql.tooltip.connecting"));
        tooltip.setShowDelay(Duration.millis(100));
        ctrl.sqlConnectChoiceBoxLoadingIcon.setTooltip(tooltip);

        ctrl.sqlConnectIconPath.setFill(Paint.valueOf("rgb(220,220,220)"));
    }
}
