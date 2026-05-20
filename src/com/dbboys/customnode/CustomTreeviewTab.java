package com.dbboys.customnode;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.dbboys.app.AppState;
import com.dbboys.app.Main;
import com.dbboys.util.ConfigManagerUtil;
import com.dbboys.util.MarkdownUtil;
import com.dbboys.vo.Markdown;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Group;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;

public class CustomTreeviewTab extends Tab {
    private static final Logger log = LogManager.getLogger(CustomTreeviewTab.class);
    private static final Color DEFAULT_ICON_COLOR = Color.valueOf("#666");

    public ToggleButton titleToggle = new ToggleButton();
    public ContextMenu contextMenu=new ContextMenu();
    public SVGPath titleToggleIcon;

    public CustomTreeviewTab() {
        getStyleClass().add("treeview-tab");
        titleToggle.setRotate(-90);
        //header.setRotate(-90);
        //header.setSpacing(5);
        
        // 默认灰色图标；移除 svg-icon 样式类，避免被全局 SVG 白色规则覆盖
        titleToggleIcon = IconFactory.create(IconPaths.DATABASE_CONNECT_TOGGLE, 0.6, DEFAULT_ICON_COLOR);
        titleToggle.setGraphic(new Group(titleToggleIcon));
        titleToggle.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        // 仅图标，无背景无边框
        titleToggle.getStyleClass().add("treeview-tab-toggle");
        titleToggle.setFocusTraversable(false);
        titleToggle.setTooltip(new Tooltip("数据库连接"));
        setText("TEST");

        // 设置图标保证响应点击/双击等事件
        setGraphic(titleToggle);
        titleToggle.selectedProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue) {
                Platform.runLater(this::updateTitleToggleIconColor);
                getTabPane().getSelectionModel().select(this);
                if (getTabPane() != null) {
                    int idx = getTabPane().getTabs().indexOf(this);
                    if (idx >= 0) {
                        ConfigManagerUtil.setProperty("DEFAULT_LISTVIEW_TAB", String.valueOf(idx));
                    }
                }
                for (Tab tab : getTabPane().getTabs()) {
                    if (!((CustomTreeviewTab) tab).getTitle().equals(getTitle())) {
                        ((CustomTreeviewTab) tab).titleToggle.setSelected(false);
                        Platform.runLater(() -> {
                            ((CustomTreeviewTab) tab).updateTitleToggleIconColor();
                        });
                    }
                }
                javafx.scene.control.SplitPane mainSplitPane = AppState.getMainSplitPane();
                if (mainSplitPane != null) {
                    mainSplitPane.setDividerPositions(AppState.getSplit1Pos());
                    AppState.setSqlEditCodeAreaIsMax(0);
                }

            } else {
                javafx.scene.control.SplitPane mainSplitPane = AppState.getMainSplitPane();
                if (mainSplitPane != null) {
                    mainSplitPane.setDividerPositions(0);
                }
                AppState.setSqlEditCodeAreaIsMax(1);
                Platform.runLater(this::updateTitleToggleIconColor);
            }
        });

        // 鼠标悬停时图标变白，移出时根据选中状态恢复
        titleToggle.setOnMouseEntered(e -> updateTitleToggleIconColor());
        titleToggle.setOnMouseExited(e -> updateTitleToggleIconColor());
        /*

        SVGPath newRootFolderItemIcon = new SVGPath();
        newRootFolderItemIcon.setContent("M18.4375 12.0234 Q19.6875 12.0234 20.7812 12.6484 Q21.8906 13.2734 22.5156 14.3828 Q23.1406 15.4766 23.1406 16.7266 Q23.1406 17.9766 22.5156 19.0859 Q21.8906 20.1953 20.7812 20.8203 Q19.6875 21.4297 18.4375 21.4297 Q16.4688 21.4297 15.0938 20.0703 Q13.7344 18.6953 13.7344 16.7266 Q13.7344 14.7578 15.0938 13.3984 Q16.4688 12.0234 18.4375 12.0234 ZM18.4375 13.7578 L18.3438 13.7578 Q18.0469 13.8047 18 14.0859 L18 16.3047 L15.7969 16.3047 Q15.5 16.3516 15.4531 16.6328 L15.4531 16.8203 Q15.5 17.1172 15.7969 17.1641 L18 17.1641 L18 19.3672 Q18.0469 19.6641 18.3438 19.7109 L18.5312 19.7109 Q18.8125 19.6641 18.8594 19.3672 L18.8594 17.1641 L21.0781 17.1641 Q21.3594 17.1172 21.4062 16.8203 L21.4062 16.6328 Q21.3594 16.3516 21.0781 16.3047 L18.8594 16.3047 L18.8594 14.0859 Q18.8125 13.8047 18.5312 13.7578 L18.4375 13.7578 ZM8.7344 2.5703 Q9.3594 2.5703 9.8438 2.9609 L12 4.7266 L20.3594 4.7266 Q21.0781 4.7266 21.625 5.2109 Q22.1719 5.6953 22.2656 6.4141 L22.2656 12.6953 Q21.7031 12.1797 21.0312 11.7891 L21.0312 6.6484 Q20.9688 6.4141 20.8281 6.2422 Q20.6875 6.0703 20.4531 6.0234 L12 6.0234 L9.9844 7.7109 Q9.5 8.0859 8.9219 8.1328 L3.0312 8.1328 L3.0312 17.7891 Q3.0312 18.0234 3.1719 18.2266 Q3.3125 18.4141 3.5469 18.4609 L13.1094 18.4609 Q13.3438 19.1328 13.7344 19.7578 L3.6406 19.7578 Q2.875 19.7578 2.3281 19.2266 Q1.7812 18.6953 1.7344 17.9297 L1.7344 4.5391 Q1.7344 3.7734 2.2344 3.2266 Q2.7344 2.6641 3.5 2.6172 L8.7344 2.5703 ZM8.7344 3.8672 L3.6406 3.8672 Q3.4062 3.8672 3.2188 4.0391 Q3.0312 4.2109 3.0312 4.4453 L3.0312 6.8984 L8.7344 6.8984 Q8.9219 6.8984 9.0781 6.7891 L10.7969 5.3516 L9.1719 4.0078 Q9.0312 3.9141 8.8281 3.8672 L8.7344 3.8672 Z");
        newRootFolderItemIcon.setScaleX(0.7);
        newRootFolderItemIcon.setScaleY(0.7);
        newRootFolderItemIcon.setFill(Color.valueOf("#074675"));
        MenuItem newRootFolderItem = new MenuItem("新建文件夹 ( New Folder )",new Group(newRootFolderItemIcon));
        newRootFolderItem.setOnAction(e -> {
            MarkdownUtil.createNewFile(MarkdownUtil.treeView.getRoot(), true);
        });

        SVGPath newFileItemIcon = new SVGPath();
        newFileItemIcon.setContent("M12 9.7656 Q12.3125 9.7656 12.5156 9.9688 Q12.7188 10.1562 12.7188 10.4844 L12.7188 12.7188 L15.0469 12.7188 Q15.2812 12.7188 15.5156 12.9688 Q15.7656 13.2031 15.7656 13.5312 Q15.7656 13.8438 15.5156 14.0469 Q15.2812 14.2344 15.0469 14.2344 L12.7188 14.2344 L12.7188 16.4844 Q12.7188 16.7969 12.5156 17.0469 Q12.3125 17.2812 12 17.2812 Q11.6875 17.2812 11.4844 17.0469 Q11.2812 16.7969 11.2812 16.4844 L11.2812 14.2344 L9.0469 14.2344 Q8.7188 14.2344 8.4688 14.0469 Q8.2344 13.8438 8.2344 13.5312 Q8.2344 13.2031 8.4688 12.9688 Q8.7188 12.7188 9.0469 12.7188 L11.2812 12.7188 L11.2812 10.4844 Q11.2812 10.1562 11.4844 9.9688 Q11.6875 9.7656 12 9.7656 ZM21.0469 6.7188 L21.0469 20.9531 Q21.0469 22.2344 20.1562 23.125 Q19.2812 24 18 24 L6 24 Q4.7188 24 3.8281 23.125 Q2.9531 22.2344 2.9531 21.0469 L2.9531 2.9531 Q3.0469 1.7656 3.875 0.8906 Q4.7188 0 6 0 L14.2344 0 L21.0469 6.7188 ZM16.4844 6.7188 Q15.5938 6.7188 14.9062 6.0781 Q14.2344 5.4375 14.2344 4.4844 L14.2344 1.5156 L6 1.5156 Q5.3594 1.5156 4.9219 1.9688 Q4.4844 2.4062 4.4844 2.9531 L4.4844 20.9531 Q4.4844 21.5938 4.9219 22.0469 Q5.3594 22.4844 6 22.4844 L18 22.4844 Q18.6406 22.4844 19.0781 22.0469 Q19.5156 21.5938 19.5156 20.9531 L19.5156 6.7188 L16.4844 6.7188 Z");
        newFileItemIcon.setScaleX(0.65);
        newFileItemIcon.setScaleY(0.6);
        newFileItemIcon.setFill(Color.valueOf("#074675"));
        MenuItem newFileItem = new MenuItem("新建文件 ( New File )",new Group(newFileItemIcon));
        newFileItem.setOnAction(e -> {
            MarkdownUtil.createNewFile(MarkdownUtil.treeView.getRoot(), false);
        });

        SVGPath copyItemIcon = new SVGPath();
        copyItemIcon.setScaleX(0.65);
        copyItemIcon.setScaleY(0.65);
        copyItemIcon.setContent("M5.5156 4.6094 L5.5156 6.7656 L5.5156 17.2344 Q5.5156 18.625 6.4531 19.5625 Q7.3906 20.5 8.7344 20.5 L17.375 20.5 Q17.1406 21.1719 16.5312 21.5781 Q15.9375 21.9844 15.2656 21.9844 L8.7344 21.9844 Q7.8281 21.9844 6.9375 21.625 Q6.0469 21.2656 5.375 20.625 Q4.7031 19.9688 4.3438 19.0781 Q3.9844 18.1875 3.9844 17.2344 L3.9844 6.7656 Q3.9844 6 4.4062 5.4219 Q4.8438 4.8438 5.5156 4.6094 ZM17.7656 2.0156 Q18.6719 2.0156 19.3438 2.6719 Q20.0156 3.3125 20.0156 4.2656 L20.0156 17.2344 Q20.0156 18.1875 19.3438 18.8438 Q18.6719 19.4844 17.7656 19.4844 L8.7344 19.4844 Q7.8281 19.4844 7.1562 18.8438 Q6.4844 18.1875 6.4844 17.2344 L6.4844 4.2656 Q6.4844 3.3125 7.1562 2.6719 Q7.8281 2.0156 8.7344 2.0156 L17.7656 2.0156 ZM17.7656 3.5 L8.7344 3.5 Q8.4531 3.5 8.2344 3.7188 Q8.0156 3.9375 8.0156 4.2656 L8.0156 17.2344 Q8.0156 17.5625 8.2344 17.7812 Q8.4531 18 8.7344 18 L17.7656 18 Q18.0469 18 18.2656 17.7812 Q18.4844 17.5625 18.4844 17.2344 L18.4844 4.2656 Q18.4844 3.9375 18.2656 3.7188 Q18.0469 3.5 17.7656 3.5 Z");
        copyItemIcon.setFill(Color.valueOf("#074675"));

        SVGPath refreshItemIcon = new SVGPath();
        refreshItemIcon.setContent("M17.6719 6.3281 L20.0156 3.9844 L20.0156 11.0156 L12.9844 11.0156 L16.2188 7.7812 Q15.375 6.9375 14.2969 6.4688 Q13.2188 6 12 6 Q10.3594 6 8.9688 6.7969 Q7.5938 7.5938 6.7969 8.9844 Q6 10.3594 6 12 Q6 13.6406 6.7969 15.0312 Q7.5938 16.4062 8.9688 17.2031 Q10.3594 18 12 18 L12 18 Q13.7344 18 15.3906 16.8281 Q17.0625 15.6562 17.6719 14.0156 L19.7344 14.0156 Q19.0312 16.6406 16.8906 18.3281 Q14.7656 20.0156 12 20.0156 Q9.8438 20.0156 7.9844 18.9375 Q6.1406 17.8594 5.0781 16.0156 Q4.0312 14.1562 4.0312 12 Q4.0312 9.8438 5.0781 8 Q6.1406 6.1406 7.9844 5.0625 Q9.8438 3.9844 12 3.9844 L12 3.9844 Q13.3594 3.9844 15.0156 4.6875 Q16.6875 5.3906 17.6719 6.3281 Z");
        refreshItemIcon.setScaleX(0.7);
        refreshItemIcon.setScaleY(0.7);
        refreshItemIcon.setFill(Color.valueOf("#074675"));

        SVGPath pasteItemIcon = new SVGPath();
        pasteItemIcon.setContent("M18.9844 21.0234 L18.9844 4.9922 L17.0156 4.9922 L17.0156 7.9922 L6.9844 7.9922 L6.9844 4.9922 L5.0156 4.9922 L5.0156 21.0234 L18.9844 21.0234 ZM12.7031 3.3047 Q12.4219 3.0234 12 3.0234 Q11.5781 3.0234 11.2969 3.3047 Q11.0156 3.5859 11.0156 4.0078 Q11.0156 4.4297 11.2969 4.7109 Q11.5781 4.9922 12 4.9922 Q12.4219 4.9922 12.7031 4.7109 Q12.9844 4.4297 12.9844 4.0078 Q12.9844 3.5859 12.7031 3.3047 ZM18.9844 3.0234 Q19.7812 3.0234 20.3906 3.6172 Q21 4.1953 21 4.9922 L21 21.0234 Q21 21.8203 20.3906 22.4141 Q19.7812 22.9922 18.9844 22.9922 L5.0156 22.9922 Q4.2188 22.9922 3.6094 22.4141 Q3 21.8203 3 21.0234 L3 4.9922 Q3 4.1953 3.6094 3.6172 Q4.2188 3.0234 5.0156 3.0234 L9.1875 3.0234 Q9.5156 2.1328 10.2656 1.5703 Q11.0156 1.0078 12 1.0078 Q12.9844 1.0078 13.7344 1.5703 Q14.4844 2.1328 14.8125 3.0234 L18.9844 3.0234 Z");
        pasteItemIcon.setScaleX(0.65);
        pasteItemIcon.setScaleY(0.65);
        pasteItemIcon.setFill(Color.valueOf("#074675"));

        MenuItem copyItem = new MenuItem("复制根目录 ( Copy Root Dir )",new Group(copyItemIcon));
        MenuItem pasteItem = new MenuItem("粘贴 ( Paste )",new Group(pasteItemIcon));
        MenuItem refreshItem = new MenuItem("刷新 ( Refresh)",new Group(refreshItemIcon));

        copyItem.setOnAction(event -> {
            ObservableList<TreeItem<Markdown>> observableList= FXCollections.observableArrayList();
            observableList.add(MarkdownUtil.treeView.getRoot());
            MarkdownUtil.copyFiles(observableList);
        });
        refreshItem.setOnAction(
                event -> {
                    MarkdownUtil.refreshNode(MarkdownUtil.treeView.getRoot());
                }
        );
        pasteItem.setOnAction(event -> {
            MarkdownUtil.pasteFiles(MarkdownUtil.treeView.getRoot());
        });
        contextMenu.getItems().addAll(newFileItem,newRootFolderItem,copyItem,pasteItem,refreshItem);


         */
        /*
        titleLabel.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                if(Main.sqledit_codearea_is_max==1){
                    Main.mainController.mainSplitPane.setDividerPositions(Main.split1Pos);
                    Main.sqledit_codearea_is_max=0;
                }else{
                    Main.mainController.mainSplitPane.setDividerPositions(0);
                    Main.sqledit_codearea_is_max=1;
                }
            }
        });

         */


    }
    public String getTitle(){
        return getText();
    }
    public void setTitle(String title){
        //titleToggle.setText(title);
        setText(title);
        //setGraphic(header);
    }

    private void updateTitleToggleIconColor() {
        boolean active = titleToggle.isSelected() || titleToggle.isHover();
        titleToggleIcon.getStyleClass().removeAll("icon-primary");
        if (active) {
            titleToggleIcon.getStyleClass().add("icon-primary");
            titleToggleIcon.setFill(null);
        } else {
            titleToggleIcon.setFill(DEFAULT_ICON_COLOR);
        }
    }

}
