package com.dbboys.ui;

import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

public final class IconFactory {
    private static final String DEFAULT_ICON_STYLE = "-fx-fill: -color-button-default;";
    private static final String DANGER_ICON_STYLE = "-fx-fill: -color-danger-7;";
    private static final Color LEGACY_DARK_ICON_COLOR = Color.BLACK;
    private static final Color LEGACY_LIGHT_ICON_COLOR = Color.WHITE;
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
        applySemanticStyle(icon, color);
        return icon;
    }

    public static SVGPath create(String path, double scaleX, double scaleY) {
        SVGPath icon = create(path, scaleX, scaleY, Color.TRANSPARENT);
        applyDefaultStyle(icon);
        return icon;
    }

    public static SVGPath create(String path, double scale, Color color) {
        return create(path, scale, scale, color);
    }

    public static SVGPath create(String path, double scale) {
        return create(path, scale, scale);
    }

    public static Group group(String path, double scaleX, double scaleY, Color color) {
        return new Group(create(path, scaleX, scaleY, color));
    }

    public static Group group(String path, double scaleX, double scaleY) {
        return new Group(create(path, scaleX, scaleY));
    }

    public static Group group(String path, double scale, Color color) {
        return new Group(create(path, scale, scale, color));
    }

    public static Group group(String path, double scale) {
        return new Group(create(path, scale, scale));
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

    public static void applyDefaultStyle(SVGPath icon) {
        icon.setStyle(DEFAULT_ICON_STYLE);
    }

    public static void applyDangerStyle(SVGPath icon) {
        icon.setStyle(DANGER_ICON_STYLE);
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

    private static void applySemanticStyle(SVGPath icon, Color color) {
        if (color == null) {
            return;
        }
        if (isDangerColor(color)) {
            applyDangerStyle(icon);
            return;
        }
        if (isDefaultColor(color)) {
            applyDefaultStyle(icon);
        }
    }

    private static boolean isDefaultColor(Color color) {
        return sameColor(color, LEGACY_DARK_ICON_COLOR)
                || sameColor(color, LEGACY_LIGHT_ICON_COLOR);
    }

    private static boolean isDangerColor(Color color) {
        return sameColor(color, DANGER_ICON_COLOR) || sameColor(color, STOP_ICON_COLOR);
    }

    private static boolean sameColor(Color left, Color right) {
        return left != null
                && right != null
                && Math.abs(left.getRed() - right.getRed()) < 0.0001
                && Math.abs(left.getGreen() - right.getGreen()) < 0.0001
                && Math.abs(left.getBlue() - right.getBlue()) < 0.0001
                && Math.abs(left.getOpacity() - right.getOpacity()) < 0.0001;
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
