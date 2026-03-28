package com.dbboys.customnode;

import com.dbboys.ctrl.ResultSetTabController;
import com.dbboys.vo.Connect;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Tab;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;

public class CustomResultsetTab extends Tab {
    public ResultSetTabController resultSetTabController;
    public VBox resultSetVBox;

    public CustomResultsetTab(Connect sqlConnect, StackPane sqlExecuteProcessStackPane) throws IOException {
        //设置标题保证标题溢出下拉正常显示标题

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/dbboys/fxml/ResultSetTab.fxml"));

        loader.setControllerFactory(clazz -> {
            if (clazz == ResultSetTabController.class) {
                return new ResultSetTabController(sqlConnect,sqlExecuteProcessStackPane);
            } else {
                try {
                    return clazz.getDeclaredConstructor().newInstance();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        resultSetVBox=loader.load();
        setContent(resultSetVBox);
        resultSetTabController=loader.getController();
        resultSetTabController.lastSqlRefreshButton.setVisible(false);
        setStyle("-fx-pref-height: 18;");
        setClosable(true);
        //关闭窗口事件响应
        setOnCloseRequest(event -> resultSetTabController.closeResultSet());
    }


}
