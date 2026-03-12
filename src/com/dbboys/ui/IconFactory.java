package com.dbboys.ui;

import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

public final class IconFactory {
    private static final Color MENU_ICON_COLOR = Color.WHITE;
    private static final Color DANGER_ICON_COLOR = Color.valueOf("#9f453c");
    private static final Color STOP_ICON_COLOR = Color.valueOf("#b33029");
    private IconFactory() {}

    public static Color dangerColor() {
        return DANGER_ICON_COLOR;
    }

    public static Color stopColor() {
        return STOP_ICON_COLOR;
    }

    public static SVGPath create(String path, double scaleX, double scaleY, Color color) {
        SVGPath icon = new SVGPath();
        icon.setContent(path);
        icon.setScaleX(scaleX);
        icon.setScaleY(scaleY);
        icon.setFill(color);
        // 标记为通用 SVG 图标，方便通过 CSS 统一控制颜色（例如按钮全部用白色）
        icon.getStyleClass().add("svg-icon");
        return icon;
    }

    public static SVGPath create(String path, double scaleX, double scaleY) {
        return create(path, scaleX, scaleY, MENU_ICON_COLOR);
    }

    public static SVGPath create(String path, double scale, Color color) {
        return create(path, scale, scale, color);
    }

    public static SVGPath create(String path, double scale) {
        return create(path, scale, scale, MENU_ICON_COLOR);
    }

    public static Group group(String path, double scaleX, double scaleY, Color color) {
        return new Group(create(path, scaleX, scaleY, color));
    }

    public static Group group(String path, double scaleX, double scaleY) {
        return new Group(create(path, scaleX, scaleY, MENU_ICON_COLOR));
    }

    public static Group group(String path, double scale, Color color) {
        return new Group(create(path, scale, scale, color));
    }

    public static Group group(String path, double scale) {
        return new Group(create(path, scale, scale, MENU_ICON_COLOR));
    }

    public static SVGPath createFixedColor(String path, double scaleX, double scaleY, Color color) {
        SVGPath icon = create(path, scaleX, scaleY, color);
        icon.setStyle("-fx-fill: " + toCssColor(color) + ";");
        return icon;
    }

    public static SVGPath createFixedColor(String path, double scale, Color color) {
        return createFixedColor(path, scale, scale, color);
    }

    public static Group groupFixedColor(String path, double scaleX, double scaleY, Color color) {
        return new Group(createFixedColor(path, scaleX, scaleY, color));
    }

    public static Group groupFixedColor(String path, double scale, Color color) {
        return new Group(createFixedColor(path, scale, scale, color));
    }

    private static String toCssColor(Color color) {
        int red = (int) Math.round(color.getRed() * 255);
        int green = (int) Math.round(color.getGreen() * 255);
        int blue = (int) Math.round(color.getBlue() * 255);
        double opacity = color.getOpacity();
        if (opacity >= 0.999) {
            return String.format("#%02x%02x%02x", red, green, blue);
        }
        return String.format("rgba(%d,%d,%d,%.3f)", red, green, blue, opacity);
    }

    public static ImageView loadingImageView(double scaleX, double scaleY) {
        ImageView view = new ImageView(new Image(IconPaths.LOADING_GIF));
        view.setScaleX(scaleX);
        view.setScaleY(scaleY);
        return view;
    }

    public static ImageView loadingImageView(double scale) {
        return loadingImageView(scale, scale);
    }

    public static ImageView imageView(String path, double fitWidth, double fitHeight, boolean preserveRatio) {
        ImageView view = new ImageView(new Image(path));
        view.setFitWidth(fitWidth);
        view.setFitHeight(fitHeight);
        view.setPreserveRatio(preserveRatio);
        return view;
    }
}
