package com.dbboys.ctrl;

import com.dbboys.customnode.CustomResultsetTableCell;
import com.dbboys.i18n.I18n;
import com.dbboys.vo.UpdateResult;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.TableColumn;
import javafx.scene.control.cell.PropertyValueFactory;

public class SqlResultHandler {
    private final SqlTabController ctrl;

    public SqlResultHandler(SqlTabController ctrl) {
        this.ctrl = ctrl;
    }

    public void setupResultsetTotalTable() {
        TableColumn<ObservableList<String>, Object> resultcol = new TableColumn<>();
        ctrl.i18nHelper.bindColumnText(resultcol, "sql.table.result");
        resultcol.setCellFactory(col -> new CustomResultsetTableCell<>());
        resultcol.setCellValueFactory(new PropertyValueFactory<>("result"));
        resultcol.setPrefWidth(120);
        resultcol.setReorderable(false);
        resultcol.setSortable(false);

        TableColumn<ObservableList<String>, Object> begin = new TableColumn<>();
        ctrl.i18nHelper.bindColumnText(begin, "sql.table.start_time");
        begin.setCellFactory(col -> new CustomResultsetTableCell<>());
        begin.setCellValueFactory(new PropertyValueFactory<>("startTime"));
        begin.setPrefWidth(190);
        begin.setReorderable(false);
        begin.setSortable(false);

        TableColumn<ObservableList<String>, Object> stop = new TableColumn<>();
        ctrl.i18nHelper.bindColumnText(stop, "sql.table.end_time");
        stop.setCellFactory(col -> new CustomResultsetTableCell<>());
        stop.setCellValueFactory(new PropertyValueFactory<>("endTime"));
        stop.setPrefWidth(190);
        stop.setReorderable(false);
        stop.setSortable(false);

        TableColumn<ObservableList<String>, Object> drution = new TableColumn<>();
        ctrl.i18nHelper.bindColumnText(drution, "sql.table.elapsed");
        drution.setCellFactory(col -> new CustomResultsetTableCell<>());
        drution.setCellValueFactory(new PropertyValueFactory<>("elapsedTime"));
        drution.setPrefWidth(100);
        drution.setReorderable(false);
        drution.setSortable(false);

        TableColumn<ObservableList<String>, Object> databasecol = new TableColumn<>();
        ctrl.i18nHelper.bindColumnText(databasecol, "sql.table.database");
        databasecol.setCellFactory(col -> new CustomResultsetTableCell<>());
        databasecol.setCellValueFactory(new PropertyValueFactory<>("database"));
        databasecol.setPrefWidth(100);
        databasecol.setReorderable(false);
        databasecol.setSortable(false);

        TableColumn<ObservableList<String>, Object> markcol = new TableColumn<>();
        ctrl.i18nHelper.bindColumnText(markcol, "sql.table.note");
        markcol.setCellFactory(col -> new CustomResultsetTableCell<>());
        markcol.setCellValueFactory(new PropertyValueFactory<>("mark"));
        markcol.setPrefWidth(120);
        markcol.setReorderable(false);
        markcol.setSortable(false);

        TableColumn<ObservableList<String>, Object> affect = new TableColumn<>();
        ctrl.i18nHelper.bindColumnText(affect, "sql.table.affected");
        affect.setCellFactory(col -> new CustomResultsetTableCell<>());
        affect.setCellValueFactory(new PropertyValueFactory<>("affectedRows"));
        affect.setPrefWidth(100);
        affect.setReorderable(false);
        affect.setSortable(false);

        TableColumn<ObservableList<String>, Object> sqlcol = new TableColumn<>();
        ctrl.i18nHelper.bindColumnText(sqlcol, "sql.table.sql");
        sqlcol.setCellFactory(col -> new CustomResultsetTableCell<>());
        sqlcol.setCellValueFactory(new PropertyValueFactory<>("updateSql"));
        sqlcol.setPrefWidth(300);
        sqlcol.setReorderable(false);
        sqlcol.setSortable(false);

        ctrl.resultsetTotalTableView.getColumns().addAll(resultcol, databasecol, sqlcol, affect, drution, begin, stop, markcol);
        ctrl.updateResults = FXCollections.observableArrayList();
        UpdateResult updateResult = new UpdateResult();
        updateResult.setResult(I18n.t("sql.table.sample.result"));
        updateResult.setDatabase(I18n.t("sql.table.sample.database"));
        updateResult.setUpdateSql(I18n.t("sql.table.sample.sql"));
        updateResult.setStartTime(I18n.t("sql.table.sample.start_time"));
        updateResult.setEndTime(I18n.t("sql.table.sample.end_time"));
        updateResult.setElapsedTime(I18n.t("sql.table.sample.elapsed"));
        updateResult.setAffectedRows(0);
        ctrl.resultsetTotalTableView.setItems(ctrl.updateResults);
    }
}
