package com.dbboys.customnode;

import com.dbboys.i18n.I18n;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import com.dbboys.util.MenuItemUtil;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.chart.*;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;

import java.util.*;

public class CustomSpaceChart extends BarChart<Number, String> {
    private static final String INTERACTION_INSTALLED_KEY = "custom.spacechart.interaction.installed";

    /* ===================== 数据模型 ===================== */
    public static class SpaceUsage {
        private  int number;
        private  String label;
        private  String name;
        private  int isExtendable;
        private  double used;
        private  double total;
        private int extents;
        private  int usedPages;
        private  int totalPages;
        private  double metaUsed;
        private  double metaTotal;
        private double limitSize;

        public SpaceUsage(int number,String label,String name, int isExtendable, double total, double used, int extents, int totalPages, int usedPages, double metaTotal, double metaUsed) {
            this.isExtendable = isExtendable;
            this.number=number;
            this.name = name;
            this.label=label;
            this.used = used;
            this.total = total;
            this.extents = extents;
            this.usedPages = usedPages;
            this.totalPages = totalPages;
            this.metaTotal=metaTotal;
            this.metaUsed=metaUsed;
        }

        public String getName() { return name; }
        public double getUsed() { return used; }
        public double getTotal() { return total; }
        public double getUnused() { return total - used; }
        public double getUsagePercent() {
            return total == 0 ? 0 : used / total * 100;
        }
        public int getUsedPages() { return usedPages; }
        public int getTotalPages() { return totalPages; }
        public int getIsExtendable() { return isExtendable; }
        public int getExtents() { return extents; }
        public String getLabel() { return label; }
        public int getNumber() {return number; }
        public double getMetaTotal() {return metaTotal;}
        public double getMetaUsed() {return metaUsed;}
        public void setLimitSize(double limitSize){
            this.limitSize=limitSize;
        }
        public double getLimitSize() {return limitSize;}
        public double getlimitSize() {return getLimitSize();}

    }


    /* ===================== 颜色常量 ===================== */
    private static final String COLOR_NORMAL = "#42b983";
    private static final String COLOR_WARNING = "#fcc30b";
    private static final String COLOR_DANGER = "#e84a43";
    private static final String COLOR_UNUSED = "rgb(220, 220, 220)";
    private static final String COLOR_EXTENDABLE = "#3f80b0";

    /* ===================== 缩放相关配置 ===================== */
    private static final double HOVER_SCALE = 1.05; // 悬停时缩放比例（1.05=放大5%）
    private static final double NORMAL_SCALE = 1.0; // 正常状态缩放比例
    private static final double SCALE_DURATION = 150; // 缩放动画时长（毫秒）

    private final XYChart.Series<Number, String> series = new XYChart.Series<>();
    private NumberAxis xAxis;
    private ColorMode colorMode = ColorMode.DBSPACE;
    private boolean menuItemsDisabled = false;  //用于外部控制菜单是否需要禁用，如connect是只读，需要禁用但显示

    // 右键菜单事件回调接口（用于外部处理菜单点击逻辑）
    public interface ContextMenuListener {
        void onCreateDbspace(SpaceUsage spaceUsage,boolean isAddFile); // 查看详情
        void onDropDbspace(SpaceUsage spaceUsage); // 刷新数据
        void onDropDatafile(SpaceUsage spaceUsage);
        void onExpandDatafile(SpaceUsage spaceUsage);
        void onUnExpandDatafile(SpaceUsage spaceUsage);
        void onUnlimitedSpaceSize(SpaceUsage spaceUsage);
    }

    private ContextMenuListener contextMenuListener; // 外部设置的回调监听器

    // 提供setter方法，让外部设置菜单点击回调
    public void setContextMenuListener(ContextMenuListener listener) {
        this.contextMenuListener = listener;
    }

    public enum ColorMode {
        DBSPACE,   // 按使用率 %
        CHUNK,      // 按已使用值
        DATABASE,
        TABLE      // 按总容量
    }

    /* ===================== 构造器 ===================== */
    public CustomSpaceChart(List<SpaceUsage> data, ColorMode colorMode) {
        super(new NumberAxis(), createYAxis(data));

        this.xAxis = (NumberAxis) getXAxis();
        this.colorMode = colorMode;
        setAnimated(false);
        setBarGap(10);
        setCategoryGap(10);
        setLegendVisible(false);

        getData().add(series);

        updateXAxis(data);
        render(data);
    }

    private void updateXAxis(List<SpaceUsage> data) {
        double max = data.stream()
                .mapToDouble(SpaceUsage::getTotal)
                .max()
                .orElse(1);

        double niceMax = niceMax(max);
        double tick = niceTick(niceMax);

        xAxis.setAutoRanging(false);
        xAxis.setLowerBound(0);
        xAxis.setUpperBound(niceMax);
        xAxis.setTickUnit(tick);

        xAxis.setTickLabelFormatter(new NumberAxis.DefaultFormatter(xAxis) {
            @Override
            public String toString(Number n) {
                return formatAxisLabel(n.doubleValue());
            }
        });
    }

    private String resolveUsedColor(SpaceUsage u) {
        return switch (colorMode) {
            case DBSPACE -> colorForSpaces(u);
            case CHUNK -> colorForChunks(u);
            case DATABASE->colorForDatabases(u);
            case TABLE -> colorForTable(u);
        };
    }

    private String colorForSpaces(SpaceUsage u) {
        if (u.getIsExtendable() > 0) {
            return COLOR_EXTENDABLE;
        }
        if (u.extents == 0 &&!u.getLabel().contains("[B]")) {  //blobspace的extents是0，需要排除
            return COLOR_NORMAL;
        }
        if (u.getUsagePercent() >= 90) return COLOR_DANGER;
        if (u.getUsagePercent() >= 80) return COLOR_WARNING;
        return COLOR_NORMAL;
    }
    private String colorForDatabases(SpaceUsage u) {
        return COLOR_NORMAL;
    }

    private String colorForTable(SpaceUsage u) {
        if (u.getUsedPages() >= 12000000) return COLOR_DANGER;
        if (u.getUsedPages() >= 10000000) return COLOR_WARNING;
        return COLOR_NORMAL;
    }

    private String colorForChunks(SpaceUsage u) {
        if (u.getIsExtendable() > 0) return COLOR_EXTENDABLE;
        return COLOR_NORMAL;                  // 小盘
    }

    private void updateYAxis(List<SpaceUsage> data) {
        List<String> names = new ArrayList<>(
                data.stream().map(SpaceUsage::getLabel).toList()
        );
        Collections.reverse(names);

        CategoryAxis yAxis = (CategoryAxis) getYAxis();
        yAxis.setAutoRanging(false);
        yAxis.getCategories().clear(); // 清空旧分类，避免空白行

        yAxis.setCategories(FXCollections.observableArrayList(names));
    }

    /* ===================== 渲染主入口 ===================== */
    public void render(List<SpaceUsage> data) {
        updateXAxis(data);
        updateYAxis(data);
        series.getData().clear();
        int barHeight = 22;
        int chartHeight = data.size() * (barHeight) + 52;
        setPrefHeight(chartHeight);
        setMinHeight(chartHeight);

        for (SpaceUsage usage : data) {
            XYChart.Data<Number, String> bar =
                    new XYChart.Data<>(usage.getTotal(), usage.getLabel());

            series.getData().add(bar);

            Tooltip tooltip = createTooltip(usage);

            bar.nodeProperty().addListener((obs, o, node) -> {
                if (node instanceof Region r) {
                    installBarFeatures(r, usage, tooltip);
                }
            });
            if (bar.getNode() instanceof Region r) {
                installBarFeatures(r, usage, tooltip);
            }

        }

        applyCss();
        //设置柱子区域背景色
        Node plot = lookup(".chart-plot-background");
        if (plot != null) {
            plot.setStyle("-fx-background-color: -color-bg-content;");
        }
        layout();
        refreshAllBars(data);
    }

    /* ===================== 缩放+右键菜单组合功能（核心修改） ===================== */
    private void addHoverScaleAndContextMenuEffect(Region bar, SpaceUsage spaceUsage) {
        // 1. 缩放效果（保留原有逻辑）
        Scale scale = new Scale(NORMAL_SCALE, NORMAL_SCALE);
        bar.getTransforms().add(scale);

        // 新增：标记是否正在显示右键菜单（控制缩放是否恢复）
        boolean[] isMenuShowing = {false};

        // 鼠标进入：放大+阴影
        bar.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> {
            if (!isMenuShowing[0]) { // 菜单未显示时才放大
                scale.setPivotX(0); // X轴锚点（柱子左侧）
                scale.setPivotY(bar.getHeight() / 2); // Y轴锚点（垂直居中）
                // 平滑缩放
                javafx.animation.Timeline timeline = new javafx.animation.Timeline(
                        new javafx.animation.KeyFrame(javafx.util.Duration.millis(SCALE_DURATION),
                                new javafx.animation.KeyValue(scale.xProperty(), HOVER_SCALE),
                                new javafx.animation.KeyValue(scale.yProperty(), HOVER_SCALE)
                        )
                );
                timeline.play();
                // 添加阴影
               // bar.setStyle(bar.getStyle() + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.8), 2, 0.1, 0, 0);");
            }
        });

        // 鼠标离开：仅当菜单未显示时才恢复缩放（核心修改）
        bar.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
            if (!isMenuShowing[0]) { // 菜单关闭时才恢复
                javafx.animation.Timeline timeline = new javafx.animation.Timeline(
                        new javafx.animation.KeyFrame(javafx.util.Duration.millis(SCALE_DURATION),
                                new javafx.animation.KeyValue(scale.xProperty(), NORMAL_SCALE),
                                new javafx.animation.KeyValue(scale.yProperty(), NORMAL_SCALE)
                        )
                );
                timeline.play();
                // 移除阴影
                //bar.setStyle(bar.getStyle().replace("-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.8), 2, 0.1, 0, 0);", ""));
            }
        });

        // 2. 右键菜单功能（使用统一 MenuItem + Icon 工厂）
        ContextMenu contextMenu = new ContextMenu();

        CustomShortcutMenuItem createDbspace = MenuItemUtil.createMenuItemI18n(
                "space.menu.create_dbspace",
                IconFactory.group(IconPaths.MARKDOWN_NEW_FOLDER_ITEM, 0.55)
        );
        createDbspace.setOnAction(e -> {
            if (contextMenuListener != null) {
                contextMenuListener.onCreateDbspace(spaceUsage, false);
            }
            isMenuShowing[0] = false;
        });

        CustomShortcutMenuItem deleteDbspace = MenuItemUtil.createMenuItemI18n(
                "space.menu.drop_dbspace",
                IconFactory.group(IconPaths.METADATA_TRUNCATE_ITEM, 0.55, IconFactory.dangerColor())
        );
        deleteDbspace.setOnAction(e -> {
            if (contextMenuListener != null) {
                contextMenuListener.onDropDbspace(spaceUsage);
            }
            isMenuShowing[0] = false;
        });

        CustomShortcutMenuItem unlimitSizeItem = MenuItemUtil.createMenuItemI18n(
                "space.menu.unlimit_size",
                IconFactory.group(IconPaths.METADATA_MODIFY_TO_STANDARD_ITEM, 0.6)
        );
        unlimitSizeItem.setOnAction(e -> {
            if (contextMenuListener != null) {
                contextMenuListener.onUnlimitedSpaceSize(spaceUsage);
            }
            isMenuShowing[0] = false;
        });

        CustomShortcutMenuItem addDatafile = MenuItemUtil.createMenuItemI18n(
                "space.menu.add_data_file",
                IconFactory.group(IconPaths.MARKDOWN_NEW_FILE_ITEM, 0.55)
        );
        addDatafile.setOnAction(e -> {
            if (contextMenuListener != null) {
                contextMenuListener.onCreateDbspace(spaceUsage, true);
            }
            isMenuShowing[0] = false;
        });

        CustomShortcutMenuItem expandDatafile = MenuItemUtil.createMenuItemI18n(
                "space.menu.set_extendable",
                IconFactory.group(IconPaths.INSTANCE_SPACE_EXTENDABLE, 0.6)
        );
        expandDatafile.setOnAction(e -> {
            if (contextMenuListener != null) {
                contextMenuListener.onExpandDatafile(spaceUsage);
            }
            isMenuShowing[0] = false;
        });

        CustomShortcutMenuItem unExpandDatafile = MenuItemUtil.createMenuItemI18n(
                "space.menu.set_unextendable",
                IconFactory.group(IconPaths.INSTANCE_SPACE_UNEXTENDABLE, 0.6)
        );
        unExpandDatafile.setOnAction(e -> {
            if (contextMenuListener != null) {
                contextMenuListener.onUnExpandDatafile(spaceUsage);
            }
            isMenuShowing[0] = false;
        });

        CustomShortcutMenuItem deleteDatafile = MenuItemUtil.createMenuItemI18n(
                "space.menu.drop_data_file",
                IconFactory.group(IconPaths.METADATA_TRUNCATE_ITEM, 0.55, IconFactory.dangerColor())
        );
        deleteDatafile.setOnAction(e -> {
            if (contextMenuListener != null) {
                contextMenuListener.onDropDatafile(spaceUsage);
            }
            isMenuShowing[0] = false;
        });

        if (spaceUsage.getIsExtendable() > 0) {
            expandDatafile.setDisable(true);
        }else{
            unExpandDatafile.setDisable(true);
        }
        // 添加菜单选项
        if (colorMode.equals(ColorMode.DBSPACE)) {
            if (spaceUsage.getLimitSize() > 0) {
                contextMenu.getItems().addAll(createDbspace, addDatafile, unlimitSizeItem,deleteDbspace);
            }else{
                contextMenu.getItems().addAll(createDbspace, addDatafile, deleteDbspace);
            }
        }else if(colorMode.equals(ColorMode.CHUNK)){
            contextMenu.getItems().addAll(expandDatafile,unExpandDatafile,deleteDatafile);
        }
        for (javafx.scene.control.MenuItem item : contextMenu.getItems()) {
            item.getProperties().put("baseDisabled", item.isDisable());
        }

        // 监听菜单显示/隐藏事件（更新标记）
        contextMenu.showingProperty().addListener((obs, oldVal, newVal) -> {
            isMenuShowing[0] = newVal; // 菜单显示时标记为true，隐藏时标记为false
            if (!newVal && !bar.isHover()) { // 菜单隐藏且鼠标不在柱子上，才恢复缩放
                javafx.animation.Timeline timeline = new javafx.animation.Timeline(
                        new javafx.animation.KeyFrame(javafx.util.Duration.millis(SCALE_DURATION),
                                new javafx.animation.KeyValue(scale.xProperty(), NORMAL_SCALE),
                                new javafx.animation.KeyValue(scale.yProperty(), NORMAL_SCALE)
                        )
                );
                timeline.play();
                //bar.setStyle(bar.getStyle().replace("-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.8), 2, 0.1, 0.1, 0);", ""));
            }
        });

        // 右键点击显示菜单（阻止事件冒泡，避免触发MOUSE_EXITED）
        bar.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                e.consume(); // 消费事件，阻止冒泡到父组件
                // 在鼠标位置显示菜单
                for (javafx.scene.control.MenuItem item : contextMenu.getItems()) {
                    boolean baseDisabled = Boolean.TRUE.equals(item.getProperties().get("baseDisabled"));
                    item.setDisable(menuItemsDisabled || baseDisabled);
                }
                contextMenu.show(bar, e.getScreenX(), e.getScreenY());
            }
        });

        // 解决Tooltip和菜单冲突
        bar.setMouseTransparent(false);
    }

    /* ===================== 样式与 Tooltip ===================== */
    private void applyBarStyle(Region bar, SpaceUsage u) {
        double ratio = u.getTotal() == 0 ? 0 : u.getUsed() / u.getTotal();

        String usedColor = resolveUsedColor(u);


        bar.setStyle(String.format(
                "-fx-background-color: linear-gradient(to right, %s 0%%, %s %.1f%%, %s %.1f%%, %s 100%%);" +
                        "-fx-background-insets: 0; -fx-border-width: 0;",
                usedColor, usedColor, ratio * 100,
                COLOR_UNUSED, ratio * 100, COLOR_UNUSED
        ));

        if(u.getMetaTotal()>0){ //如果是大对象空间，单独着色,getTotalPages()==0表示为数据库空间
            String usedColorData=COLOR_NORMAL;
            if(u.getUsed()/(u.getTotal()-u.getMetaTotal())>=0.8){
                usedColorData=COLOR_WARNING;
            }
            if(u.getUsed()/(u.getTotal()-u.getMetaTotal())>=0.9){
                usedColorData=COLOR_DANGER;
            }

            String usedColorMeta=COLOR_NORMAL;
            if(u.getMetaUsed()/u.getMetaTotal()>=0.8){
                usedColorMeta=COLOR_WARNING;
            }
            if(u.getMetaUsed()/u.getMetaTotal()>=0.9){
                usedColorMeta=COLOR_DANGER;
            }

            if(u.getTotalPages()!=0){ //如果是chunk,不需要分色显示告警
                usedColorData=COLOR_NORMAL;
                usedColorMeta=COLOR_NORMAL;
            }

            double ratio1=u.getUsed()/u.getTotal();
            double ratio2=(u.getTotal()-u.getMetaTotal())/u.getTotal();
            double ratio3=(u.getTotal()-u.getMetaTotal()+u.getMetaUsed())/u.getTotal();


            bar.setStyle(String.format(
                    "-fx-background-color: linear-gradient(to right, %s 0%%, %s %.1f%%, " +
                            "%s %.1f%%,%s %.1f%%," +
                            "%s %.1f%%,%s %.1f%%," +
                            "%s %.1f%%, %s 100%%);" +
                            "-fx-background-insets: 0; -fx-border-width: 0;",
                    usedColorData, usedColorData, ratio1 * 100,
                    COLOR_UNUSED, ratio1 * 100, COLOR_UNUSED,ratio2 * 100,
                    usedColorMeta, ratio2 * 100,usedColorMeta,ratio3 * 100,
                    COLOR_UNUSED,ratio3 * 100,COLOR_UNUSED
            ));
        }
        bar.setShape(new Rectangle(6, 6));
        bar.setScaleShape(true);
    }

    private Tooltip createTooltip(SpaceUsage u) {
        Tooltip t = new Tooltip();
        t.textProperty().bind(Bindings.createStringBinding(
                () -> buildTooltipText(u),
                I18n.localeProperty()
        ));
        t.setShowDelay(javafx.util.Duration.millis(100));
        t.setHideDelay(javafx.util.Duration.millis(500));
        return t;
    }

    private String buildTooltipText(SpaceUsage u) {
        String yesText = I18n.t("common.yes", "是");
        String noText = I18n.t("common.no", "否");
        StringBuilder sb = new StringBuilder();

        if (colorMode.equals(ColorMode.DBSPACE)) {
            if (u.getMetaTotal() > 0) {
                sb.append(kv("space.tooltip.space_name", "空间名", u.getName())).append('\n');
                sb.append(kv("space.tooltip.total", "总容量", String.format("%.2f GB", u.getTotal() - u.getMetaTotal()))).append('\n');
                sb.append(kv("space.tooltip.used", "已使用", String.format("%.2f GB（%.1f%%）", u.getUsed(), u.getUsed() / (u.getTotal() - u.getMetaTotal())))).append('\n');
                sb.append(kv("space.tooltip.unused", "未使用", String.format("%.2f GB", u.getTotal() - u.getMetaTotal() - u.getUsed()))).append('\n');
                sb.append(kv("space.tooltip.extendable", "可扩展", u.getIsExtendable() > 0 ? yesText : noText)).append('\n');
                sb.append("--------------------").append('\n');
                sb.append(kv("space.tooltip.meta_total", "元数据总大小", String.format("%.2f GB", u.getMetaTotal()))).append('\n');
                sb.append(kv("space.tooltip.meta_used", "元数据已使用", String.format("%.2f GB（%.1f%%）", u.getMetaUsed(), u.getMetaUsed() * 100 / u.getMetaTotal())));
                if (u.getLimitSize() > 0) {
                    sb.append('\n').append("--------------------").append('\n');
                    sb.append(kv("space.tooltip.limit_size", "限制大小", String.format("%.2f GB", u.getLimitSize())));
                }
                return sb.toString();
            }

            sb.append(kv("space.tooltip.space_name", "空间名", u.getName())).append('\n');
            sb.append(kv("space.tooltip.total", "总容量", String.format("%.2f GB", u.getTotal()))).append('\n');
            sb.append(kv("space.tooltip.used", "已使用", String.format("%.2f GB（%.1f%%）", u.getUsed(), u.getUsagePercent()))).append('\n');
            sb.append(kv("space.tooltip.unused", "未使用", String.format("%.2f GB", u.getUnused()))).append('\n');
            sb.append(kv("space.tooltip.extendable", "可扩展", u.getIsExtendable() > 0 ? yesText : noText));
            if (u.getLimitSize() > 0) {
                sb.append('\n').append("--------------------").append('\n');
                sb.append(kv("space.tooltip.limit_size", "限制大小", String.format("%.2f GB", u.getLimitSize())));
            }
            return sb.toString();
        }

        if (colorMode.equals(ColorMode.CHUNK)) {
            if (u.getMetaTotal() > 0) {
                sb.append(kv("space.tooltip.space_name", "空间名", u.getName())).append('\n');
                sb.append(kv("space.tooltip.total", "总容量", String.format("%.2f GB", u.getTotal() - u.getMetaTotal()))).append('\n');
                sb.append(kv("space.tooltip.used", "已使用", String.format("%.2f GB（%.1f%%）", u.getUsed(), u.getUsed() / (u.getTotal() - u.getMetaTotal())))).append('\n');
                sb.append(kv("space.tooltip.unused", "未使用", String.format("%.2f GB", u.getTotal() - u.getMetaTotal() - u.getUsed()))).append('\n');
                sb.append(kv("space.tooltip.extendable", "可扩展", u.getIsExtendable() > 0 ? yesText : noText)).append('\n');
                sb.append("--------------------").append('\n');
                sb.append(kv("space.tooltip.meta_total", "元数据总大小", String.format("%.2f GB", u.getMetaTotal()))).append('\n');
                sb.append(kv("space.tooltip.meta_used", "元数据已使用", String.format("%.2f GB（%.1f%%）", u.getMetaUsed(), u.getMetaUsed() * 100 / u.getMetaTotal())));
                return sb.toString();
            }

            sb.append(kv("space.tooltip.file_name", "文件名", u.getName())).append('\n');
            sb.append(kv("space.tooltip.total", "总容量", String.format("%.2f GB", u.getTotal()))).append('\n');
            sb.append(kv("space.tooltip.used", "已使用", String.format("%.2f GB（%.1f%%）", u.getUsed(), u.getUsagePercent()))).append('\n');
            sb.append(kv("space.tooltip.unused", "未使用", String.format("%.2f GB", u.getUnused()))).append('\n');
            sb.append(kv("space.tooltip.extendable", "可扩展", u.getIsExtendable() > 0 ? yesText : noText));
            return sb.toString();
        }

        if (colorMode.equals(ColorMode.DATABASE)) {
            sb.append(kv("space.tooltip.db_name", "库名", u.getName())).append('\n');
            sb.append(kv("space.tooltip.total", "总容量", String.format("%.2f GB", u.getTotal()))).append('\n');
            sb.append(kv("space.tooltip.used", "已使用", String.format("%.2f GB（%.1f%%）", u.getUsed(), u.getUsagePercent()))).append('\n');
            sb.append(kv("space.tooltip.unused", "未使用", String.format("%.2f GB", u.getUnused())));
            return sb.toString();
        }

        if (colorMode.equals(ColorMode.TABLE)) {
            sb.append(kv("space.tooltip.object_name", "对象名", u.getName())).append('\n');
            sb.append(kv("space.tooltip.total", "总容量", String.format("%.2f GB", u.getTotal()))).append('\n');
            sb.append(kv("space.tooltip.used", "已使用", String.format("%.2f GB（%.1f%%）", u.getUsed(), u.getUsagePercent()))).append('\n');
            sb.append(kv("space.tooltip.unused", "未使用", String.format("%.2f GB", u.getUnused()))).append('\n');
            sb.append(kv("space.tooltip.allocated_pages", "分配页", String.valueOf(u.getTotalPages()))).append('\n');
            sb.append(kv("space.tooltip.data_pages", "数据页", String.valueOf(u.getUsedPages())));
            return sb.toString();
        }

        return "";
    }

    private String kv(String key, String fallback, String value) {
        String label = I18n.t(key, fallback);
        if (isEnglishLocale()) {
            return String.format("%-10s: %s", label, value);
        }
        return label + "：" + value;
    }

    private boolean isEnglishLocale() {
        Locale locale = I18n.getLocale();
        return locale != null && "en".equalsIgnoreCase(locale.getLanguage());
    }

    private void refreshAllBars(List<SpaceUsage> data) {
        Map<String, SpaceUsage> map = new HashMap<>();
        data.forEach(d -> map.put(d.getLabel(), d));

        for (XYChart.Data<Number, String> d : series.getData()) {
            SpaceUsage u = map.get(d.getYValue());
            if (u != null && d.getNode() instanceof Region r) {
                installBarFeatures(r, u, createTooltip(u));
            }
        }
    }

    private void installBarFeatures(Region bar, SpaceUsage usage, Tooltip tooltip) {
        applyBarStyle(bar, usage);
        Tooltip.install(bar, tooltip);
        if (!Boolean.TRUE.equals(bar.getProperties().get(INTERACTION_INSTALLED_KEY))) {
            addHoverScaleAndContextMenuEffect(bar, usage);
            bar.getProperties().put(INTERACTION_INSTALLED_KEY, Boolean.TRUE);
        }
    }

    /* ===================== 轴创建 ===================== */
    private static NumberAxis createXAxis(List<SpaceUsage> data) {
        double max = data.stream().mapToDouble(SpaceUsage::getTotal).max().orElse(1);
        NumberAxis x = new NumberAxis(0, niceMax(max), niceMax(max) / 10);
        x.setTickLabelFormatter(new NumberAxis.DefaultFormatter(x) {
            @Override
            public String toString(Number n) {
                return formatAxisLabel(n.doubleValue());
            }
        });
        return x;
    }

    private static CategoryAxis createYAxis(List<SpaceUsage> data) {
        List<String> names = new ArrayList<>(
                data.stream().map(SpaceUsage::getName).toList()
        );

        Collections.reverse(names); // ⭐ 关键

        CategoryAxis y = new CategoryAxis();
        y.setAutoRanging(false);    // 非常重要
        y.setCategories(FXCollections.observableArrayList(names));
        return y;
    }

    private static double niceMax(double max) {
        if (max <= 0) return 1;

        double exp = Math.floor(Math.log10(max));
        double base = max / Math.pow(10, exp);

        double niceBase;
        if (base <= 1) niceBase = 1;
        else if (base <= 2) niceBase = 2;
        else if (base <= 2.5) niceBase = 2.5;
        else if (base <= 5) niceBase = 5;
        else niceBase = 10;

        return niceBase * Math.pow(10, exp);
    }

    private static double niceTick(double niceMax) {
        return niceMax / 10.0;
    }

    private static String formatAxisLabel(double value) {
        if (Math.abs(value) < 1) {
            return String.format("%.2f", value);
        }
        return String.format("%.1f", value);
    }
    public Node createLegend() {
        HBox legend = new HBox(6);
        legend.setStyle("""
        -fx-alignment: CENTER;
        -fx-padding: 6;
        -fx-background-color: none;
        -fx-border-color: none;
        -fx-border-radius: 3;
        -fx-background-radius: 3;
        -fx-font-size: 9;
        """);

        switch (colorMode) {

            case DBSPACE -> {
                legend.getChildren().addAll(
                        createLegendItem(COLOR_NORMAL, "space.legend.normal", "正常 (< 80%或不增长)"),
                        createLegendItem(COLOR_WARNING, "space.legend.warning", "警告 (80% ~ 90%)"),
                        createLegendItem(COLOR_DANGER, "space.legend.danger", "危险 (≥ 90%)"),
                        createLegendItem(COLOR_EXTENDABLE, "space.legend.extendable", "可自动扩展"),
                        createLegendItem(COLOR_UNUSED, "space.legend.allocated_unused", "已分配未使用")
                );
            }

            case CHUNK -> {
                legend.getChildren().addAll(
                        createLegendItem(COLOR_NORMAL, "space.legend.not_extendable", "不自动扩展"),
                        createLegendItem(COLOR_EXTENDABLE, "space.legend.extendable", "可自动扩展"),
                        createLegendItem(COLOR_UNUSED, "space.legend.allocated_unused", "已分配未使用")
                );
            }

            case DATABASE -> {
                legend.getChildren().addAll(
                        createLegendItem(COLOR_NORMAL, "space.legend.used", "已使用"),
                        createLegendItem(COLOR_UNUSED, "space.legend.allocated_unused", "已分配未使用")
                );
            }

            case TABLE -> {
                legend.getChildren().addAll(
                        createLegendItem(COLOR_NORMAL, "space.legend.page_lt_10m", "使用页 < 10,000,000"),
                        createLegendItem(COLOR_WARNING, "space.legend.page_ge_10m", "使用页 ≥ 10,000,000"),
                        createLegendItem(COLOR_DANGER, "space.legend.page_ge_12m", "使用页 ≥ 12,000,000"),
                        createLegendItem(COLOR_UNUSED, "space.legend.allocated_unused", "已分配未使用")
                );
            }
        }

        return legend;
    }
    private Node createLegendItem(String color, String i18nKey, String fallback) {
        Rectangle rect = new Rectangle(9, 9);
        rect.setArcWidth(4);
        rect.setArcHeight(4);
        rect.setFill(Color.web(color));

        javafx.scene.control.Label label = new javafx.scene.control.Label();
        label.textProperty().bind(I18n.bind(i18nKey, fallback));
        label.setStyle("-fx-font-size: 9px;");

        javafx.scene.layout.HBox box = new javafx.scene.layout.HBox(6, rect, label);
        box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return box;
    }

    public void setMenuItemsDisabled(boolean disabled) {
        this.menuItemsDisabled = disabled;
    }
}
