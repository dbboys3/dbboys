package com.dbboys.ui.controller;

import com.dbboys.app.AppExecutor;
import com.dbboys.app.AppState;
import com.dbboys.ui.component.*;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

public class SqlTabUiHelper {
    private static final Logger log = LogManager.getLogger(SqlTabUiHelper.class);
    /** Same slot as {@link com.dbboys.ui.component.CustomTreeCell} connect icons — stable toolbar alignment. */
    private static final double SQL_HEADER_ICON_SLOT = 16.0;
    private static final String SQL_HEADER_ICON_STYLE = "sql-header-icon";
    private static final String ICON_BUTTON_DEFAULT_STYLE = "icon-button-default";
    private static final String ICON_WARN_STYLE = "icon-warn";
    static final String SQL_HEADER_ICON_CONNECTED_STYLE = "sql-header-icon-connected";
    static final String SQL_HEADER_ICON_DISCONNECTED_STYLE = "sql-header-icon-disconnected";

    private final SqlTabController ctrl;

    public SqlTabUiHelper(SqlTabController ctrl) {
        this.ctrl = ctrl;
    }

    public void setupTransactionTooltips() {
        ctrl.commitButtonTooltip.textProperty().bind(ctrl.sqlTransactionText);
        ctrl.commitButtonTooltip.setShowDelay(Duration.millis(100));
        ctrl.transactionCommitButton.setTooltip(ctrl.commitButtonTooltip);
        ctrl.transactionRollbackButton.setTooltip(ctrl.commitButtonTooltip);
        ctrl.transactionCommitButton.getStyleClass().addAll("accent-pill-button", "accent-pill-success");
        ctrl.transactionRollbackButton.getStyleClass().addAll("accent-pill-button", "accent-pill-failure");
    }

    public void setupSqlTabIcons() {
        ctrl.sqlExecuteLoadingLabel.setGraphic(IconFactory.imageView(IconPaths.LOADING_GIF, 11, 11, true));
        ctrl.sqlRunButton.setGraphic(IconFactory.group(IconPaths.SQL_RUN, 0.85, Color.valueOf("#51dd66")));
        ctrl.sqlExplainButton.setGraphic(IconFactory.group(IconPaths.SQL_EXPLAIN, 0.45));
        ctrl.sqlStopButton.setGraphic(IconFactory.groupFixedColor(IconPaths.SQL_STOP, 0.7, IconFactory.stopColor()));
        ctrl.sqlRecordButton.setGraphic(IconFactory.group(IconPaths.SQL_HISTORY, 0.8));
        SVGPath readOnlyIcon = IconFactory.create(IconPaths.SQL_READONLY, 0.5, 0.5);
        readOnlyIcon.getStyleClass().remove(ICON_BUTTON_DEFAULT_STYLE);
        readOnlyIcon.getStyleClass().add(ICON_WARN_STYLE);
        ctrl.sqlReadOnlyLabel.setGraphic(new Group(readOnlyIcon));

        BooleanBinding sqlHeaderDbUserDimmed = Bindings.createBooleanBinding(
                () -> ctrl.sqlConnectChoiceBox.isDisable() || ctrl.isGeneralJdbcToolbarSelection(),
                ctrl.sqlConnectChoiceBox.disableProperty(),
                ctrl.sqlConnectChoiceBox.valueProperty());
        if (ctrl.sqlDbIconPane != null) {
            ctrl.sqlDbIconPane.setMinSize(SQL_HEADER_ICON_SLOT, SQL_HEADER_ICON_SLOT);
            ctrl.sqlDbIconPane.setPrefSize(SQL_HEADER_ICON_SLOT, SQL_HEADER_ICON_SLOT);
            ctrl.sqlDbIconPane.setMaxSize(SQL_HEADER_ICON_SLOT, SQL_HEADER_ICON_SLOT);
            ctrl.sqlDbIconPane.setAlignment(Pos.CENTER);
            ctrl.sqlDbIconPath = IconFactory.create(IconPaths.SQL_DATABASE, 0.4, 0.4);
            applySqlHeaderIconState(ctrl.sqlDbIconPath, false);
            ctrl.sqlDbIconPane.getChildren().setAll(new Group(ctrl.sqlDbIconPath));
            ctrl.sqlDbIconPane.opacityProperty().bind(
                    Bindings.when(sqlHeaderDbUserDimmed).then(0.4).otherwise(1.0)
            );
        }
        if (ctrl.sqlUserIconPane != null) {
            ctrl.sqlUserIconPath = IconFactory.create(IconPaths.SQL_USER, 0.55, 0.55);
            applySqlHeaderIconState(ctrl.sqlUserIconPath, false);
            ctrl.sqlUserIconPane.getChildren().setAll(new Group(ctrl.sqlUserIconPath));
            ctrl.sqlUserIconPane.opacityProperty().bind(
                    Bindings.when(sqlHeaderDbUserDimmed).then(0.4).otherwise(1.0)
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

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/dbboys/ui/fxml/ResultSetTab.fxml"));
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
        ctrl.resultSuccessFilterButton = createResultFilterButton(SqlTabController.RESULT_SUCCESS_BUTTON_COLOR);
        ctrl.resultFailureFilterButton = createResultFilterButton(SqlTabController.RESULT_FAILURE_BUTTON_COLOR);
        ctrl.resultSuccessFilterButton.setOnAction(event -> ctrl.toggleResultFilterSuccess());
        ctrl.resultFailureFilterButton.setOnAction(event -> ctrl.toggleResultFilterFailure());
        ctrl.resultFilterButtonBox = new HBox(8, ctrl.resultSuccessFilterButton, ctrl.resultFailureFilterButton);
        ctrl.resultFilterButtonBox.setAlignment(Pos.BOTTOM_RIGHT);
        ctrl.resultFilterButtonBox.setPickOnBounds(false);
        ctrl.resultsetStackPane.getChildren().add(ctrl.resultFilterButtonBox);
        StackPane.setAlignment(ctrl.resultFilterButtonBox, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(ctrl.resultFilterButtonBox, new Insets(0, 12, 10, 0));
        ctrl.refreshResultFilterButtons();
        I18n.localeProperty().addListener((obs, oldLocale, newLocale) -> ctrl.refreshResultFilterButtons());
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

    private Button createResultFilterButton(String backgroundColor) {
        Button button = new Button();
        button.setFocusTraversable(false);
        button.getStyleClass().add("result-filter-button");
        button.getStyleClass().add(SqlTabController.RESULT_SUCCESS_BUTTON_COLOR.equals(backgroundColor)
                ? "result-filter-success"
                : "result-filter-failure");
        Region dot = new Region();
        dot.getStyleClass().add("result-filter-dot");
        dot.getStyleClass().add(SqlTabController.RESULT_SUCCESS_BUTTON_COLOR.equals(backgroundColor)
                ? "result-filter-dot-success"
                : "result-filter-dot-failure");
        button.setGraphic(dot);
        button.setContentDisplay(ContentDisplay.LEFT);
        return button;
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

        ctrl.sqlConnectIconPath = IconFactory.create(IconPaths.CONNECTION_LINK, 0.6, 0.6);
        applySqlHeaderIconState(ctrl.sqlConnectIconPath, false);
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
    }

    static void applySqlHeaderIconState(SVGPath icon, boolean connected) {
        if (icon == null) {
            return;
        }
        if (!icon.getStyleClass().contains(SQL_HEADER_ICON_STYLE)) {
            icon.getStyleClass().add(SQL_HEADER_ICON_STYLE);
        }
        icon.getStyleClass().removeAll(SQL_HEADER_ICON_CONNECTED_STYLE, SQL_HEADER_ICON_DISCONNECTED_STYLE);
        icon.getStyleClass().add(connected ? SQL_HEADER_ICON_CONNECTED_STYLE : SQL_HEADER_ICON_DISCONNECTED_STYLE);
    }
}
