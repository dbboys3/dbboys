package com.dbboys.util;

import com.dbboys.app.AppState;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.List;

public final class CustomWindowFrameUtil {
    private static final String MAXIMIZED_KEY = "customWindowMaximized";
    private static final String TITLE_BG = "#1f2a36";
    private static final String BODY_BG = "#151a1f";
    private static final String BORDER_COLOR = "#314150";
    private static final String TITLE_STYLE =
            "-fx-background-color: " + TITLE_BG + ";" +
            "-fx-padding: 0 0 0 12;" +
            "-fx-min-height: 36;" +
            "-fx-pref-height: 36;" +
            "-fx-alignment: center-left;";
    private static final String ROOT_STYLE =
            "-fx-background-color: " + BODY_BG + ";" +
            "-fx-border-color: " + BORDER_COLOR + ";" +
            "-fx-border-width: 1;";
    private static final String CLOSE_STYLE =
            "-fx-background-color: transparent;" +
            "-fx-text-fill: white;" +
            "-fx-border-width: 0;" +
            "-fx-background-radius: 0;" +
            "-fx-padding: 0 12 0 12;";
    private static final String WINDOW_BUTTON_STYLE =
            "-fx-background-color: transparent;" +
            "-fx-border-width: 0;" +
            "-fx-background-radius: 0;" +
            "-fx-padding: 0 12 0 12;";
    private static final double RESIZE_MARGIN = 5;

    private CustomWindowFrameUtil() {
    }

    public static Frame create(Stage stage,
                               ObservableValue<String> titleBinding,
                               Node content,
                               double width,
                               double height) {
        return create(stage, titleBinding, content, width, height, null);
    }

    /** Same as create(...) but with optional left content in the title bar (e.g. logo + menu). */
    public static Frame create(Stage stage,
                               ObservableValue<String> titleBinding,
                               Node content,
                               double width,
                               double height,
                               Node titleBarLeft) {
        return create(stage, titleBinding, content, width, height, titleBarLeft, true);
    }

    /**
     * Same as create(..., titleBarLeft) but can disable edge resize (e.g. for fixed-size dialogs).
     * @param enableResize false to disable border drag resize and resize cursor
     */
    public static Frame create(Stage stage,
                               ObservableValue<String> titleBinding,
                               Node content,
                               double width,
                               double height,
                               Node titleBarLeft,
                               boolean enableResize) {
        Region dragRegion = new Region();
        HBox.setHgrow(dragRegion, Priority.ALWAYS);

        Label titleLabel = new Label();
        titleLabel.textProperty().bind(titleBinding);
        titleLabel.setStyle("-fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold;");

        Button minButton = createWindowButton(IconPaths.WINDOW_MINIMIZE, 0.45);
        Button maxButton = createWindowButton(IconPaths.WINDOW_MAXIMIZE, 0.55);
        Button closeButton = new Button("✕");
        closeButton.setFocusTraversable(false);
        closeButton.setStyle(CLOSE_STYLE);
        closeButton.setOnMouseEntered(event ->
                closeButton.setStyle(CLOSE_STYLE + "-fx-background-color: #a63f3a;"));
        closeButton.setOnMouseExited(event -> closeButton.setStyle(CLOSE_STYLE));

        HBox titleBar = new HBox(titleLabel, dragRegion, minButton, maxButton, closeButton);
        if (titleBarLeft != null) {
            titleBar.getChildren().add(0, titleBarLeft);
        }
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setStyle(TITLE_STYLE);

        BorderPane pane = new BorderPane(content);
        pane.setTop(titleBar);
        pane.setStyle(ROOT_STYLE);

        StackPane root = new StackPane(pane);
        root.setStyle(ROOT_STYLE);
        root.setPrefSize(width, height);
        root.setMinSize(320, 180);

        Scene scene = new Scene(root, width, height);
        AppState.applyAppStylesheet(scene);
        if (enableResize) {
            enableResize(stage, root, scene);
        }

        WindowState state = new WindowState();
        configureTitleBar(stage, titleBar, maxButton, state);
        configureButtons(stage, minButton, maxButton, closeButton, state);

        return new Frame(scene, root, titleBar, minButton, maxButton, closeButton, state);
    }

    private static Button createWindowButton(String iconPath, double scale) {
        Button button = new Button();
        button.setFocusTraversable(false);
        button.setStyle(WINDOW_BUTTON_STYLE);
        button.setGraphic(IconFactory.group(iconPath, scale, Color.WHITE));
        button.setOnMouseEntered(event ->
                button.setStyle(WINDOW_BUTTON_STYLE + "-fx-background-color: #314150;"));
        button.setOnMouseExited(event -> button.setStyle(WINDOW_BUTTON_STYLE));
        return button;
    }

    private static void configureButtons(Stage stage,
                                         Button minButton,
                                         Button maxButton,
                                         Button closeButton,
                                         WindowState state) {
        minButton.setOnAction(event -> stage.setIconified(true));
        maxButton.setOnAction(event -> toggleMaximize(stage, maxButton, state));
        closeButton.setOnAction(event -> stage.close());
    }

    private static void configureTitleBar(Stage stage, HBox titleBar, Button maxButton, WindowState state) {
        final double[] dragOffsetX = new double[1];
        final double[] dragOffsetY = new double[1];
        final double[] dragRatioX = new double[1];

        titleBar.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                maxButton.fire();
            }
        });

        titleBar.setOnMousePressed(event -> {
            dragOffsetX[0] = event.getScreenX() - stage.getX();
            dragOffsetY[0] = event.getScreenY() - stage.getY();
            dragRatioX[0] = stage.getWidth() <= 0 ? 0.5 : event.getSceneX() / stage.getWidth();
        });

        titleBar.setOnMouseDragged(event -> {
            if (state.maximized) {
                toggleMaximize(stage, maxButton, state);
                stage.setX(event.getScreenX() - stage.getWidth() * dragRatioX[0]);
                dragOffsetX[0] = event.getScreenX() - stage.getX();
            } else {
                stage.setX(event.getScreenX() - dragOffsetX[0]);
                stage.setY(event.getScreenY() - dragOffsetY[0]);
            }
        });
    }

    private static void toggleMaximize(Stage stage, Button maxButton, WindowState state) {
        if (state.maximized) {
            maxButton.setGraphic(IconFactory.group(IconPaths.WINDOW_MAXIMIZE, 0.55, Color.WHITE));
            stage.setX(state.prevX);
            stage.setY(state.prevY);
            stage.setWidth(state.prevWidth);
            stage.setHeight(state.prevHeight);
        } else {
            state.prevX = stage.getX();
            state.prevY = stage.getY();
            state.prevWidth = stage.getWidth();
            state.prevHeight = stage.getHeight();

            Rectangle2D visualBounds = getVisualBounds(stage);
            stage.setX(visualBounds.getMinX());
            stage.setY(visualBounds.getMinY());
            stage.setWidth(visualBounds.getWidth());
            stage.setHeight(visualBounds.getHeight());
            maxButton.setGraphic(IconFactory.group(IconPaths.WINDOW_RESTORE, 0.4, Color.WHITE));
        }
        state.maximized = !state.maximized;
        stage.getProperties().put(MAXIMIZED_KEY, state.maximized);
    }

    /** Maximizes the stage using the frame's state and button; call after show if you want to start maximized. */
    public static void requestMaximize(Frame frame) {
        Stage stage = (Stage) frame.scene.getWindow();
        if (frame.state.maximized) return;
        frame.state.prevX = stage.getX();
        frame.state.prevY = stage.getY();
        frame.state.prevWidth = stage.getWidth();
        frame.state.prevHeight = stage.getHeight();
        Rectangle2D visualBounds = getVisualBounds(stage);
        stage.setX(visualBounds.getMinX());
        stage.setY(visualBounds.getMinY());
        stage.setWidth(visualBounds.getWidth());
        stage.setHeight(visualBounds.getHeight());
        frame.maxButton.setGraphic(IconFactory.group(IconPaths.WINDOW_RESTORE, 0.4, Color.WHITE));
        frame.state.maximized = true;
        stage.getProperties().put(MAXIMIZED_KEY, true);
    }

    private static Rectangle2D getVisualBounds(Stage stage) {
        List<Screen> screens = Screen.getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        Screen screen = screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
        return screen.getVisualBounds();
    }

    private static void enableResize(Stage stage, StackPane root, Scene scene) {
        final ResizeDirection[] direction = new ResizeDirection[]{ResizeDirection.NONE};
        final DragResizeState[] dragState = new DragResizeState[]{new DragResizeState()};
        final boolean[] resizeActive = new boolean[]{false};

        scene.addEventFilter(MouseEvent.ANY, event -> {
            double w = root.getWidth() > 0 ? root.getWidth() : stage.getWidth();
            double h = root.getHeight() > 0 ? root.getHeight() : stage.getHeight();
            double sx = event.getSceneX();
            double sy = event.getSceneY();

            if (event.getEventType() == MouseEvent.MOUSE_MOVED) {
                direction[0] = detectResizeDirection(w, h, sx, sy);
                scene.setCursor(direction[0].cursor);
                return;
            }
            if (event.getEventType() == MouseEvent.MOUSE_EXITED) {
                if (!event.isPrimaryButtonDown()) {
                    direction[0] = ResizeDirection.NONE;
                    resizeActive[0] = false;
                    scene.setCursor(Cursor.DEFAULT);
                }
                return;
            }
            if (event.getEventType() == MouseEvent.MOUSE_PRESSED) {
                dragState[0] = new DragResizeState(
                        stage.getX(),
                        stage.getY(),
                        stage.getWidth(),
                        stage.getHeight(),
                        event.getScreenX(),
                        event.getScreenY()
                );
                direction[0] = detectResizeDirection(w, h, sx, sy);
                resizeActive[0] = direction[0] != ResizeDirection.NONE;
                if (resizeActive[0]) {
                    event.consume();
                }
                scene.setCursor(direction[0].cursor);
                return;
            }
            if (event.getEventType() == MouseEvent.MOUSE_DRAGGED) {
                if (resizeActive[0] && isResizable(stage)) {
                    applyResize(stage, direction[0], dragState[0], event.getScreenX(), event.getScreenY());
                    event.consume();
                    scene.setCursor(direction[0].cursor);
                } else if (!isResizable(stage)) {
                    scene.setCursor(Cursor.DEFAULT);
                }
                return;
            }
            if (event.getEventType() == MouseEvent.MOUSE_RELEASED) {
                if (resizeActive[0]) {
                    event.consume();
                }
                resizeActive[0] = false;
                direction[0] = detectResizeDirection(w, h, event.getSceneX(), event.getSceneY());
                scene.setCursor(direction[0].cursor);
            }
        });
    }

    private static boolean isResizable(Stage stage) {
        return stage.isResizable() && !Boolean.TRUE.equals(stage.getProperties().get(MAXIMIZED_KEY));
    }

    /** 使用宽高和场景坐标检测边缘，避免依赖 root 的 layout 和事件目标。 */
    private static ResizeDirection detectResizeDirection(double width, double height, double x, double y) {
        if (width <= 0 || height <= 0) return ResizeDirection.NONE;
        boolean left = x >= 0 && x <= RESIZE_MARGIN;
        boolean right = x >= width - RESIZE_MARGIN && x <= width;
        boolean top = y >= 0 && y <= RESIZE_MARGIN;
        boolean bottom = y >= height - RESIZE_MARGIN && y <= height;

        if (top && left) return ResizeDirection.NW;
        if (top && right) return ResizeDirection.NE;
        if (bottom && left) return ResizeDirection.SW;
        if (bottom && right) return ResizeDirection.SE;
        if (top) return ResizeDirection.N;
        if (bottom) return ResizeDirection.S;
        if (left) return ResizeDirection.W;
        if (right) return ResizeDirection.E;
        return ResizeDirection.NONE;
    }

    private static void applyResize(Stage stage,
                                    ResizeDirection direction,
                                    DragResizeState dragState,
                                    double screenX,
                                    double screenY) {
        double deltaX = screenX - dragState.startScreenX;
        double deltaY = screenY - dragState.startScreenY;

        switch (direction) {
            case E -> {
                double newWidth = dragState.startWidth + deltaX;
                if (newWidth > stage.getMinWidth()) {
                    stage.setWidth(newWidth);
                }
            }
            case W -> {
                double newWidth = dragState.startWidth - deltaX;
                if (newWidth > stage.getMinWidth()) {
                    stage.setWidth(newWidth);
                    stage.setX(dragState.startX + deltaX);
                }
            }
            case S -> {
                double newHeight = dragState.startHeight + deltaY;
                if (newHeight > stage.getMinHeight()) {
                    stage.setHeight(newHeight);
                }
            }
            case N -> {
                double newHeight = dragState.startHeight - deltaY;
                if (newHeight > stage.getMinHeight()) {
                    stage.setHeight(newHeight);
                    stage.setY(dragState.startY + deltaY);
                }
            }
            case SE -> {
                double newWidth = dragState.startWidth + deltaX;
                double newHeight = dragState.startHeight + deltaY;
                if (newWidth > stage.getMinWidth()) {
                    stage.setWidth(newWidth);
                }
                if (newHeight > stage.getMinHeight()) {
                    stage.setHeight(newHeight);
                }
            }
            case SW -> {
                double newHeight = dragState.startHeight + deltaY;
                if (newHeight > stage.getMinHeight()) {
                    stage.setHeight(newHeight);
                }
                double newWidth = dragState.startWidth - deltaX;
                if (newWidth > stage.getMinWidth()) {
                    stage.setWidth(newWidth);
                    stage.setX(dragState.startX + deltaX);
                }
            }
            case NE -> {
                double newWidth = dragState.startWidth + deltaX;
                if (newWidth > stage.getMinWidth()) {
                    stage.setWidth(newWidth);
                }
                double newHeight = dragState.startHeight - deltaY;
                if (newHeight > stage.getMinHeight()) {
                    stage.setHeight(newHeight);
                    stage.setY(dragState.startY + deltaY);
                }
            }
            case NW -> {
                double newWidth = dragState.startWidth - deltaX;
                if (newWidth > stage.getMinWidth()) {
                    stage.setWidth(newWidth);
                    stage.setX(dragState.startX + deltaX);
                }
                double newHeight = dragState.startHeight - deltaY;
                if (newHeight > stage.getMinHeight()) {
                    stage.setHeight(newHeight);
                    stage.setY(dragState.startY + deltaY);
                }
            }
            case NONE -> {
            }
        }
    }

    public static final class Frame {
        public final Scene scene;
        public final StackPane root;
        public final HBox titleBar;
        public final Button minButton;
        public final Button maxButton;
        public final Button closeButton;
        public final WindowState state;

        private Frame(Scene scene,
                      StackPane root,
                      HBox titleBar,
                      Button minButton,
                      Button maxButton,
                      Button closeButton,
                      WindowState state) {
            this.scene = scene;
            this.root = root;
            this.titleBar = titleBar;
            this.minButton = minButton;
            this.maxButton = maxButton;
            this.closeButton = closeButton;
            this.state = state;
        }
    }

    public static final class WindowState {
        public boolean maximized;
        public double prevX;
        public double prevY;
        public double prevWidth;
        public double prevHeight;
    }

    private enum ResizeDirection {
        NONE(Cursor.DEFAULT),
        N(Cursor.N_RESIZE),
        S(Cursor.S_RESIZE),
        E(Cursor.E_RESIZE),
        W(Cursor.W_RESIZE),
        NE(Cursor.NE_RESIZE),
        NW(Cursor.NW_RESIZE),
        SE(Cursor.SE_RESIZE),
        SW(Cursor.SW_RESIZE);

        private final Cursor cursor;

        ResizeDirection(Cursor cursor) {
            this.cursor = cursor;
        }
    }

    private static final class DragResizeState {
        private final double startX;
        private final double startY;
        private final double startWidth;
        private final double startHeight;
        private final double startScreenX;
        private final double startScreenY;

        private DragResizeState() {
            this(0, 0, 0, 0, 0, 0);
        }

        private DragResizeState(double startX,
                                double startY,
                                double startWidth,
                                double startHeight,
                                double startScreenX,
                                double startScreenY) {
            this.startX = startX;
            this.startY = startY;
            this.startWidth = startWidth;
            this.startHeight = startHeight;
            this.startScreenX = startScreenX;
            this.startScreenY = startScreenY;
        }
    }
}
