package com.dbboys.customnode;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

public class CustomInstallStepHbox extends HBox {
    public CheckBox checkBox=new CheckBox();
    public Label nameLabel=new Label();
    public Label iconLabel=new Label();
    public Label descLabel=new Label();
    public CustomInstallStepHbox(String name,String desc) {

        iconLabel.setGraphic(IconFactory.create(IconPaths.INSTALL_STEP_ARROW, 0.6, 0.6));
        iconLabel.setVisible(false);

        //setPadding(new Insets(0,0,0,20));
        setSpacing(2);
        checkBox.setSelected(true);
        checkBox.setFocusTraversable(false);
        nameLabel.setPrefWidth(90);
        nameLabel.setText(name);
        nameLabel.getStyleClass().add("install-step-name");
        // 单击名称时同步勾选/反选（尊重禁用态）
        nameLabel.setOnMouseClicked(event -> {
            if (!checkBox.isDisabled()) {
                checkBox.setSelected(!checkBox.isSelected());
            }
        });
        descLabel.setText(desc);
        descLabel.getStyleClass().add("install-step-desc");
        getChildren().addAll( iconLabel,checkBox,nameLabel,descLabel);
        checkBox.getStyleClass().add("table-check-box");
        setAlignment(Pos.CENTER_LEFT);

    }
}
