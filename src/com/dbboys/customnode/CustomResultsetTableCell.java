package com.dbboys.customnode;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.dbboys.i18n.I18n;
import com.dbboys.vo.UpdateResult;
import javafx.application.Platform;
import javafx.scene.Cursor;
import javafx.scene.control.TableCell;
import javafx.scene.control.TextArea;
//只有执行批量sql或者非查询的sql使用此类，以标记不同结果行的颜色，表的查询结果集使用CustomTableCell
public class CustomResultsetTableCell<S, T> extends CustomTableCell<S, T> {
    private static final Logger log = LogManager.getLogger(CustomResultsetTableCell.class);
    @Override
    protected void updateItem(T item, boolean empty) {
        super.updateItem(item, empty);
        getStyleClass().removeAll("execute_result_error", "execute_result_ignore");
        if (!empty)

            try {
                if (getTableRow() != null && getTableRow().getItem() != null && ((UpdateResult) getTableRow().getItem()).getResult() != null) {
                    String result = ((UpdateResult) getTableRow().getItem()).getResult();
                    String successPrefix = I18n.t("sql.exec.success");
                    if (!result.startsWith(successPrefix)) {
                        //setStyle("-fx-text-fill: red");
                        getStyleClass().add("execute_result_error");
                }
                //else if (((UpdateResult) getTableRow().getItem()).getResult().substring(0, 4).equals("忽略执行")) {
                    //setStyle("-fx-text-fill: #aaa");
                  //  getStyleClass().add("execute_result_ignore");
                //}

            }
        } catch (Exception e) {
            log.error("Operation failed", e);
        }




    }

}
