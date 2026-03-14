package com.dbboys.util;

import com.dbboys.app.AppState;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.input.MouseEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.List;

public final class CustomWindowFrameUtil {
    private static final String MAXIMIZED_KEY = "customWindowMaximized";
    private static final String TITLE_BG = "-color-bg-default";
    private static final String BODY_BG = "-color-bg-default";
    private static final String BORDER_COLOR = "-color-fg-default";
    private static final String TITLE_STYLE =
            "-fx-background-color: " + TITLE_BG + ";" +
            "-fx-padding: 0 0 0 6;" +
            "-fx-min-height: 28;" +
            "-fx-pref-height: 28;" +
            "-fx-alignment: center-left;";
    private static final String ROOT_STYLE =
            "-fx-background-color: " + BODY_BG + ";" +
            "-fx-padding: 0;";
    private static final String POPUP_FRAME_STYLE =
            "-fx-border-color: " + BORDER_COLOR + ";" +
            "-fx-border-width: 0.5;" +
            "-fx-padding: 0;";
    private static final String CONTENT_STYLE =
            "-fx-background-color: " + BODY_BG + ";";
    private static final String DIALOG_TITLE_BORDER_STYLE =
            "-fx-border-width: 0.5 0.5 0 0.5;" +
            "-fx-border-color: " + BORDER_COLOR + ";";
    private static final String CLOSE_STYLE =
            "-fx-background-color: transparent;" +
            "-fx-text-fill: white;" +
            "-fx-border-width: 0;" +
            "-fx-background-radius: 0;" +
            "-fx-padding: 0 12 0 12;" +
            "-fx-min-height: 28;" +
            "-fx-pref-height: 28;";
    private static final String WINDOW_BUTTON_STYLE =
            "-fx-background-color: transparent;" +
            "-fx-border-width: 0;" +
            "-fx-background-radius: 0;" +
            "-fx-padding: 0 12 0 12;" +
            "-fx-min-height: 28;" +
            "-fx-pref-height: 28;";
    private static final double RESIZE_MARGIN = 5;

    private CustomWindowFrameUtil() {
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

    public static Frame create(Stage stage,
                               ObservableValue<String> titleBinding,
                               Node content,
                               double width,
                               double height) {
        return create(stage, titleBinding, content, width, height, null);
    }

    public static Frame createModalPopup(Stage stage,
                                         ObservableValue<String> titleBinding,
                                         Node content,
                                         double width,
                                         double height,
                                         boolean resizable) {
        return createModalPopup(stage, titleBinding, content, width, height, resizable, null);
    }

    public static Frame createModalPopup(Stage stage,
                                         ObservableValue<String> titleBinding,
                                         Node content,
                                         double width,
                                         double height,
                                         boolean resizable,
                                         Window owner) {
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setResizable(resizable);
        stage.initModality(Modality.APPLICATION_MODAL);

        Window resolvedOwner = owner != null ? owner : AppState.getWindow();
        if (resolvedOwner != null) {
            stage.initOwner(resolvedOwner);
        }
        if (stage.getIcons().isEmpty()) {
            stage.getIcons().add(new Image(IconPaths.MAIN_LOGO));
        }

        Frame frame = create(stage, titleBinding, content, width, height, null, resizable, resizable, resizable);
        stage.setScene(frame.scene);
        return frame;
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
        return create(stage, titleBinding, content, width, height, titleBarLeft, enableResize, true, true);
    }

    public static Frame create(Stage stage,
                               ObservableValue<String> titleBinding,
                               Node content,
                               double width,
                               double height,
                               Node titleBarLeft,
                               boolean enableResize,
                               boolean showMinButton,
                               boolean showMaxButton) {
        boolean mainWindow = isMainWindowFrame(titleBarLeft, showMinButton, showMaxButton);
        if (!mainWindow) {
            applyPopupChoiceBoxStyle(content);
        }
        Region dragRegion = new Region();
        HBox.setHgrow(dragRegion, Priority.ALWAYS);

        Label titleLabel = new Label();
        titleLabel.textProperty().bind(titleBinding);
        titleLabel.setMaxHeight(Double.MAX_VALUE);
        titleLabel.setStyle(
                "-fx-text-fill: -color-fg-default;" +
                "-fx-font-weight: bold;" +
                "-fx-alignment: center-left;"
        );

        Button minButton = createWindowButton(IconPaths.WINDOW_MINIMIZE, 0.45);
        Button maxButton = createWindowButton(IconPaths.WINDOW_MAXIMIZE, 0.55);
        Button closeButton = new Button("✕");
        closeButton.setFocusTraversable(false);
        closeButton.setStyle(CLOSE_STYLE);
        closeButton.setOnMouseEntered(event ->
                closeButton.setStyle(CLOSE_STYLE + "-fx-background-color: " + toCssColor(IconFactory.stopColor()) + ";"));
        closeButton.setOnMouseExited(event -> closeButton.setStyle(CLOSE_STYLE));

        HBox titleBar = new HBox(titleLabel, dragRegion);
        if (titleBarLeft != null) {
            titleBar.getChildren().add(0, titleBarLeft);
        }
        if (showMinButton) {
            titleBar.getChildren().add(minButton);
        } else {
            minButton.setManaged(false);
            minButton.setVisible(false);
        }
        if (showMaxButton) {
            titleBar.getChildren().add(maxButton);
        } else {
            maxButton.setManaged(false);
            maxButton.setVisible(false);
        }
        titleBar.getChildren().add(closeButton);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setStyle(TITLE_STYLE + resolveTitleStyle(mainWindow, content));

        BorderPane pane = new BorderPane(content);
        pane.setTop(titleBar);
        pane.setStyle(CONTENT_STYLE);

        StackPane root = new StackPane(pane);
        root.setStyle(mainWindow ? ROOT_STYLE : POPUP_FRAME_STYLE);
        root.setPrefSize(width, height);
        if (!mainWindow) {
            root.setMinSize(200, 100);
        }

        Scene scene = new Scene(root, width, height);
        if (!mainWindow) {
            stage.setMinWidth(Math.max(200, width));
            stage.setMinHeight(Math.max(100, height));
        }
        AppState.applyAppStylesheet(scene);
        if (enableResize) {
            installResizeHandles(stage, root, scene);
        }

        WindowState state = new WindowState();
        configureTitleBar(stage, titleBar, maxButton, state, showMaxButton);
        configureButtons(stage, minButton, maxButton, closeButton, state, showMinButton, showMaxButton);

        return new Frame(scene, root, titleBar, minButton, maxButton, closeButton, state);
    }

    private static boolean isMainWindowFrame(Node titleBarLeft, boolean showMinButton, boolean showMaxButton) {
        return titleBarLeft != null && showMinButton && showMaxButton;
    }

    private static String resolveTitleStyle(boolean mainWindow, Node content) {
        if (mainWindow || !(content instanceof javafx.scene.control.DialogPane)) {
            return "";
        }
        return DIALOG_TITLE_BORDER_STYLE;
    }

    private static void applyPopupChoiceBoxStyle(Node node) {
        if (node instanceof ChoiceBox<?> choiceBox
                && !choiceBox.getStyleClass().contains("choice-box-with-border")) {
            choiceBox.getStyleClass().add("choice-box-with-border");
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                applyPopupChoiceBoxStyle(child);
            }
        }
    }

    private static Button createWindowButton(String iconPath, double scale) {
        Button button = new Button();
        button.setFocusTraversable(false);
        button.setStyle(WINDOW_BUTTON_STYLE);
        button.setGraphic(IconFactory.group(iconPath, scale));
        button.setOnMouseEntered(event ->
                button.setStyle(WINDOW_BUTTON_STYLE + "-fx-background-color: #314150;"));
        button.setOnMouseExited(event -> button.setStyle(WINDOW_BUTTON_STYLE));
        return button;
    }

    private static void configureButtons(Stage stage,
                                         Button minButton,
                                         Button maxButton,
                                         Button closeButton,
                                         WindowState state,
                                         boolean showMinButton,
                                         boolean showMaxButton) {
        if (showMinButton) {
            minButton.setOnAction(event -> stage.setIconified(true));
        }
        if (showMaxButton) {
            maxButton.setOnAction(event -> toggleMaximize(stage, maxButton, state));
        }
        closeButton.setOnAction(event -> stage.close());
    }

    private static void configureTitleBar(Stage stage, HBox titleBar, Button maxButton, WindowState state, boolean showMaxButton) {
        final double[] dragOffsetX = new double[1];
        final double[] dragOffsetY = new double[1];
        final double[] dragRatioX = new double[1];

        titleBar.setOnMouseClicked(event -> {
            if (showMaxButton && event.getClickCount() == 2) {
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
            maxButton.setGraphic(IconFactory.group(IconPaths.WINDOW_MAXIMIZE, 0.55));
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
            maxButton.setGraphic(IconFactory.group(IconPaths.WINDOW_RESTORE, 0.4));
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
        frame.maxButton.setGraphic(IconFactory.group(IconPaths.WINDOW_RESTORE, 0.4));
        frame.state.maximized = true;
        stage.getProperties().put(MAXIMIZED_KEY, true);
    }

    private static Rectangle2D getVisualBounds(Stage stage) {
        List<Screen> screens = Screen.getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        Screen screen = screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
        return screen.getVisualBounds();
    }

    private static void installResizeHandles(Stage stage, StackPane root, Scene scene) {
        addEdgeResizeHandle(stage, scene, root, ResizeDirection.N);
        addEdgeResizeHandle(stage, scene, root, ResizeDirection.S);
        addEdgeResizeHandle(stage, scene, root, ResizeDirection.W);
        addEdgeResizeHandle(stage, scene, root, ResizeDirection.E);

        addCornerResizeHandle(stage, scene, root, ResizeDirection.NW);
        addCornerResizeHandle(stage, scene, root, ResizeDirection.NE);
        addCornerResizeHandle(stage, scene, root, ResizeDirection.SW);
        addCornerResizeHandle(stage, scene, root, ResizeDirection.SE);

        root.addEventHandler(MouseEvent.MOUSE_MOVED, event -> {
            if (event.getTarget() == root) {
                scene.setCursor(Cursor.DEFAULT);
            }
        });
        root.addEventHandler(MouseEvent.MOUSE_ENTERED, event -> {
            if (event.getTarget() == root && !event.isPrimaryButtonDown()) {
                scene.setCursor(Cursor.DEFAULT);
            }
        });
        root.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> {
            if (event.getTarget() == root) {
                scene.setCursor(Cursor.DEFAULT);
            }
        });
        scene.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> {
            if (!isPointerOnResizeHandle(root, event.getSceneX(), event.getSceneY())) {
                scene.setCursor(Cursor.DEFAULT);
            }
        });
        scene.addEventHandler(MouseEvent.MOUSE_EXITED, event -> {
            if (!event.isPrimaryButtonDown()) {
                scene.setCursor(Cursor.DEFAULT);
            }
        });
    }

    private static void addEdgeResizeHandle(Stage stage,
                                            Scene scene,
                                            StackPane root,
                                            ResizeDirection direction) {
        Region handle = createResizeHandle(stage, scene, direction);
        root.getChildren().add(handle);
        root.widthProperty().addListener((obs, oldVal, newVal) -> layoutEdgeResizeHandle(handle, root, direction));
        root.heightProperty().addListener((obs, oldVal, newVal) -> layoutEdgeResizeHandle(handle, root, direction));
        layoutEdgeResizeHandle(handle, root, direction);
    }

    private static void addCornerResizeHandle(Stage stage,
                                              Scene scene,
                                              StackPane root,
                                              ResizeDirection direction) {
        Region handle = createResizeHandle(stage, scene, direction);
        root.getChildren().add(handle);
        root.widthProperty().addListener((obs, oldVal, newVal) -> layoutCornerResizeHandle(handle, root, direction));
        root.heightProperty().addListener((obs, oldVal, newVal) -> layoutCornerResizeHandle(handle, root, direction));
        layoutCornerResizeHandle(handle, root, direction);
    }

    private static Region createResizeHandle(Stage stage,
                                             Scene scene,
                                             ResizeDirection direction) {
        Region handle = new Region();
        handle.setManaged(false);
        handle.setStyle("-fx-background-color: transparent;");
        handle.setCursor(direction.cursor);
        final DragResizeState[] dragState = new DragResizeState[]{new DragResizeState()};
        handle.addEventHandler(MouseEvent.MOUSE_ENTERED, event -> {
            if (isResizable(stage)) {
                scene.setCursor(direction.cursor);
            }
        });
        handle.addEventHandler(MouseEvent.MOUSE_EXITED, event -> {
            if (!event.isPrimaryButtonDown()) {
                scene.setCursor(Cursor.DEFAULT);
            }
        });
        handle.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (!isResizable(stage)) {
                return;
            }
            dragState[0] = new DragResizeState(
                    stage.getX(),
                    stage.getY(),
                    stage.getWidth(),
                    stage.getHeight(),
                    event.getScreenX(),
                    event.getScreenY()
            );
            scene.setCursor(direction.cursor);
            event.consume();
        });
        handle.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!isResizable(stage)) {
                scene.setCursor(Cursor.DEFAULT);
                return;
            }
            applyResize(stage, direction, dragState[0], event.getScreenX(), event.getScreenY());
            scene.setCursor(direction.cursor);
            event.consume();
        });
        handle.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> {
            if (!isPointerOnResizeHandle((StackPane) scene.getRoot(), event.getSceneX(), event.getSceneY())) {
                scene.setCursor(Cursor.DEFAULT);
            } else {
                scene.setCursor(direction.cursor);
            }
            event.consume();
        });
        return handle;
    }

    private static boolean isPointerOnResizeHandle(StackPane root, double sceneX, double sceneY) {
        for (Node node : root.getChildren()) {
            if (!(node instanceof Region handle) || handle.isManaged()) {
                continue;
            }
            double minX = handle.getLayoutX();
            double minY = handle.getLayoutY();
            double maxX = minX + handle.getWidth();
            double maxY = minY + handle.getHeight();
            if (sceneX >= minX && sceneX <= maxX && sceneY >= minY && sceneY <= maxY) {
                return true;
            }
        }
        return false;
    }

    private static void layoutEdgeResizeHandle(Region handle,
                                               StackPane root,
                                               ResizeDirection direction) {
        double width = root.getWidth();
        double height = root.getHeight();
        switch (direction) {
            case N -> handle.resizeRelocate(RESIZE_MARGIN, 0, Math.max(0, width - RESIZE_MARGIN * 2), RESIZE_MARGIN);
            case S -> handle.resizeRelocate(RESIZE_MARGIN, Math.max(0, height - RESIZE_MARGIN), Math.max(0, width - RESIZE_MARGIN * 2), RESIZE_MARGIN);
            case W -> handle.resizeRelocate(0, RESIZE_MARGIN, RESIZE_MARGIN, Math.max(0, height - RESIZE_MARGIN * 2));
            case E -> handle.resizeRelocate(Math.max(0, width - RESIZE_MARGIN), RESIZE_MARGIN, RESIZE_MARGIN, Math.max(0, height - RESIZE_MARGIN * 2));
            default -> {
            }
        }
    }

    private static void layoutCornerResizeHandle(Region handle,
                                                 StackPane root,
                                                 ResizeDirection direction) {
        double width = root.getWidth();
        double height = root.getHeight();
        switch (direction) {
            case NW -> handle.resizeRelocate(0, 0, RESIZE_MARGIN, RESIZE_MARGIN);
            case NE -> handle.resizeRelocate(Math.max(0, width - RESIZE_MARGIN), 0, RESIZE_MARGIN, RESIZE_MARGIN);
            case SW -> handle.resizeRelocate(0, Math.max(0, height - RESIZE_MARGIN), RESIZE_MARGIN, RESIZE_MARGIN);
            case SE -> handle.resizeRelocate(Math.max(0, width - RESIZE_MARGIN), Math.max(0, height - RESIZE_MARGIN), RESIZE_MARGIN, RESIZE_MARGIN);
            default -> {
            }
        }
    }

    private static boolean isResizable(Stage stage) {
        return stage.isResizable() && !Boolean.TRUE.equals(stage.getProperties().get(MAXIMIZED_KEY));
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
