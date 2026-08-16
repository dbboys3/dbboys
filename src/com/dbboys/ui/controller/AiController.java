package com.dbboys.ui.controller;

import com.dbboys.app.AppExecutor;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.ai.AiApiClient;
import com.dbboys.infra.ai.AiAuthClient;
import com.dbboys.search.MarkdownSearchUtil;
import com.dbboys.ui.notification.NotificationUtil;
import com.dbboys.ui.component.CustomAiStyledArea;
import com.dbboys.ui.component.CustomUserTextarea;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 对话面板控制器。
 * 从 MainController 中提取，负责 AI 输入、消息流式渲染、对话记忆等全部 AI UI 逻辑。
 */
public class AiController {
    private static final Pattern AI_REFERENCE_LOCATION_PATTERN = Pattern.compile("第\\d+[页行]");
    private static final double USER_BUBBLE_MAX_WIDTH_RATIO = 0.7;
    private static final int MESSAGE_BUBBLE_RADIUS = 6;
    private static final double AI_INPUT_HEIGHT = 90;
    private static final int AI_HISTORY_TURNS = 3;
    private static final String AI_STREAMING_LABEL_STYLE = "ai-streaming-label";
    private static final String AI_THINKING_LABEL_STYLE = "ai-thinking-label";
    private static final String AI_MEMORY_BTN_STYLE_OFF = "ai-memory-toggle-off";
    private static final String AI_MEMORY_BTN_STYLE_ON = "ai-memory-toggle-on";
    private static final List<String> AI_AVAILABLE_MODELS = List.of(
            "deepseek-v4-flash",
            "deepseek-v4-pro",
            "doubao-seed-2-0-mini-260215",
            "qwen3.6-plus"
    );

    // FXML-injected fields (passed from MainController)
    private final VBox aiTabVBox;
    private final ScrollPane aiChatScrollPane;
    private final VBox aiChatMessages;
    private final CustomUserTextarea aiInputField;
    private final Button aiSendButton;
    private final ChoiceBox<String> aiModelChoiceBox;
    private final Button aiSettingsButton;
    private final Button aiMemoryToggleButton;

    // Internal state
    private Future<?> aiTaskFuture;
    private AiMessageView aiStreamingMessage;
    private volatile boolean aiCancelled = false;
    private volatile boolean aiMemoryEnabled = false;
    private final AtomicBoolean aiScrollScheduled = new AtomicBoolean(false);
    private final List<AiConversationMessage> aiConversationHistory = new ArrayList<>();

    private record AiConversationMessage(String role, String content) {}

    private static final class AiMessageView {
        private final CustomAiStyledArea area = new CustomAiStyledArea();
        private final Label streamingLabel = new Label();
        private final AtomicBoolean renderScheduled = new AtomicBoolean(false);
        private final AtomicInteger revision = new AtomicInteger();
        private final AtomicInteger renderedRevision = new AtomicInteger(-1);
        final VBox messageGroup;
        final StackPane bubble;
        final HBox buttonRow;
        private final StringBuilder rawContent = new StringBuilder();
        private FadeTransition thinkingFade;
        private Timeline thinkingDotsTimeline;

        AiMessageView(VBox messageGroup, StackPane bubble, HBox buttonRow) {
            this.messageGroup = messageGroup;
            this.bubble = bubble;
            this.buttonRow = buttonRow;
        }

        static String aiThinkingBaseText(String i18nReplying) {
            if (i18nReplying == null) {
                return "";
            }
            return i18nReplying.replaceAll("\\.+\\s*$", "").trim();
        }

        private static String thinkingDotSuffix(int stepMod4) {
            int n = Math.floorMod(stepMod4, 4);
            return n == 0 ? "" : ".".repeat(n);
        }

        void startThinkingAnimation(String baseText) {
            stopThinkingAnimation();
            streamingLabel.getStyleClass().remove(AI_STREAMING_LABEL_STYLE);
            if (!streamingLabel.getStyleClass().contains(AI_THINKING_LABEL_STYLE)) {
                streamingLabel.getStyleClass().add(AI_THINKING_LABEL_STYLE);
            }
            thinkingFade = new FadeTransition(Duration.millis(950), streamingLabel);
            thinkingFade.setFromValue(0.52);
            thinkingFade.setToValue(1.0);
            thinkingFade.setCycleCount(Animation.INDEFINITE);
            thinkingFade.setAutoReverse(true);
            thinkingFade.play();

            thinkingDotsTimeline = new Timeline(
                    new KeyFrame(Duration.ZERO, e -> streamingLabel.setText(baseText + thinkingDotSuffix(0))),
                    new KeyFrame(Duration.millis(400), e -> streamingLabel.setText(baseText + thinkingDotSuffix(1))),
                    new KeyFrame(Duration.millis(800), e -> streamingLabel.setText(baseText + thinkingDotSuffix(2))),
                    new KeyFrame(Duration.millis(1200), e -> streamingLabel.setText(baseText + thinkingDotSuffix(3)))
            );
            thinkingDotsTimeline.setCycleCount(Timeline.INDEFINITE);
            thinkingDotsTimeline.play();
        }

        void stopThinkingAnimation() {
            if (thinkingFade != null) {
                thinkingFade.stop();
                thinkingFade = null;
            }
            if (thinkingDotsTimeline != null) {
                thinkingDotsTimeline.stop();
                thinkingDotsTimeline = null;
            }
            streamingLabel.setOpacity(1.0);
            streamingLabel.getStyleClass().remove(AI_THINKING_LABEL_STYLE);
            if (!streamingLabel.getStyleClass().contains(AI_STREAMING_LABEL_STYLE)) {
                streamingLabel.getStyleClass().add(AI_STREAMING_LABEL_STYLE);
            }
        }

        synchronized void appendRaw(String delta) {
            rawContent.append(delta);
            revision.incrementAndGet();
        }

        synchronized void setRaw(String text) {
            rawContent.setLength(0);
            rawContent.append(text == null ? "" : text);
            revision.incrementAndGet();
        }

        synchronized String getRaw() {
            return rawContent.toString();
        }
    }

    public AiController(VBox aiTabVBox, ScrollPane aiChatScrollPane, VBox aiChatMessages,
                        CustomUserTextarea aiInputField, Button aiSendButton,
                        ChoiceBox<String> aiModelChoiceBox, Button aiSettingsButton,
                        Button aiMemoryToggleButton) {
        this.aiTabVBox = aiTabVBox;
        this.aiChatScrollPane = aiChatScrollPane;
        this.aiChatMessages = aiChatMessages;
        this.aiInputField = aiInputField;
        this.aiSendButton = aiSendButton;
        this.aiModelChoiceBox = aiModelChoiceBox;
        this.aiSettingsButton = aiSettingsButton;
        this.aiMemoryToggleButton = aiMemoryToggleButton;
    }

    public void init() {
        updateAiSendButtonText(false);

        if (aiInputField != null) {
            aiInputField.setMinHeight(AI_INPUT_HEIGHT);
            aiInputField.setPrefHeight(AI_INPUT_HEIGHT);
            aiInputField.setMaxHeight(AI_INPUT_HEIGHT);
            aiInputField.setOnKeyPressed(event -> {
                if (event.isShiftDown() && event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                    aiInputField.replaceSelection(System.lineSeparator());
                    event.consume();
                } else if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                    sendAiMessage();
                    event.consume();
                }
            });
            if (aiInputField.getParent() instanceof Region inputContainer) {
                inputContainer.setMinHeight(AI_INPUT_HEIGHT);
                inputContainer.setPrefHeight(AI_INPUT_HEIGHT);
                inputContainer.setMaxHeight(AI_INPUT_HEIGHT);
                VBox.setVgrow(inputContainer, Priority.NEVER);
            }
        }

        if (aiModelChoiceBox != null) {
            var modelOptions = new ArrayList<>(AI_AVAILABLE_MODELS);
            String currentModel = AiAuthClient.getModel();
            if (currentModel != null && !currentModel.isBlank() && !modelOptions.contains(currentModel)) {
                modelOptions.add(0, currentModel);
            }
            aiModelChoiceBox.getItems().setAll(modelOptions);
            if (currentModel != null && !currentModel.isBlank()) {
                aiModelChoiceBox.getSelectionModel().select(currentModel);
            } else if (!modelOptions.isEmpty()) {
                aiModelChoiceBox.getSelectionModel().selectFirst();
            }
            aiModelChoiceBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null && !newVal.isBlank()) {
                    AiAuthClient.setModel(newVal);
                }
            });
        }

        if (aiSettingsButton != null) {
            aiSettingsButton.setOnAction(event -> showAiApiKeyDialog());
        }
        if (aiMemoryToggleButton != null) {
            aiMemoryToggleButton.setOnAction(event -> {
                aiMemoryEnabled = !aiMemoryEnabled;
                updateAiMemoryToggleButton();
            });
            I18n.localeProperty().addListener((obs, oldVal, newVal) -> updateAiMemoryToggleButton());
            updateAiMemoryToggleButton();
        }

        if (aiChatScrollPane != null) {
            aiChatScrollPane.setFitToWidth(true);
            aiChatScrollPane.setFitToHeight(false);
        }
    }

    private void showAiApiKeyDialog() {
        ButtonType confirmType = new ButtonType(I18n.t("common.confirm"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType(I18n.t("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);

        Label promptLabel = new Label(I18n.t("ai.dialog.api_key.prompt"));
        com.dbboys.ui.component.CustomPasswordField keyField = new com.dbboys.ui.component.CustomPasswordField();
        keyField.setPromptText(I18n.t("ai.dialog.api_key.prompt"));
        keyField.setText(AiAuthClient.getApiToken());

        Label hintLabel = new Label(I18n.t("ai.dialog.api_key.hint") + "\n" + AiAuthClient.getApiTokenStoragePath());
        hintLabel.setWrapText(true);
        hintLabel.getStyleClass().add("ai-api-key-hint");

        VBox content = new VBox(8, promptLabel, keyField, hintLabel);
        AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                I18n.t("ai.dialog.api_key.title"),
                content,
                420,
                Region.USE_COMPUTED_SIZE,
                confirmType,
                cancelType
        );
        EventHandler<WindowEvent> originalOnShown = dialog.getStage().getOnShown();
        dialog.getStage().setOnShown(event -> {
            if (originalOnShown != null) {
                originalOnShown.handle(event);
            }
            Platform.runLater(() -> {
                keyField.requestFocus();
                keyField.positionCaret(keyField.getText().length());
            });
        });

        ButtonType result = dialog.showAndWait();
        if (result != confirmType) {
            return;
        }

        try {
            String token = keyField.getText() == null ? "" : keyField.getText().trim();
            AiAuthClient.setApiToken(token);
            NotificationUtil.showMainNotification(
                    token.isEmpty() ? I18n.t("ai.notice.api_key_cleared") : I18n.t("ai.notice.api_key_saved")
            );
        } catch (Exception e) {
            AlertUtil.CustomAlert(I18n.t("common.error"), e.getMessage());
        }
    }

    public void sendAiMessage() {
        if (aiInputField == null || aiChatMessages == null) return;

        if (aiTaskFuture != null && !aiTaskFuture.isDone()) {
            cancelAiRequest();
            return;
        }

        String text = aiInputField.getText();
        if (text == null || text.isBlank()) return;

        addUserMarkdownMessage(text);
        aiInputField.clear();
        scrollAiChatToBottom();

        if (!AiAuthClient.hasConfiguredApi()) {
            addAiMarkdownMessage(buildApiKeyGuideMessage());
            return;
        }

        AiMessageView messageView = addStreamingAiMessage(I18n.t("ai.replying"));
        aiStreamingMessage = messageView;
        updateAiSendButtonText(true);
        aiCancelled = false;

        aiTaskFuture = AppExecutor.submit(() -> {
            List<MarkdownSearchUtil.KnowledgeReference> references =
                    MarkdownSearchUtil.loadAiKnowledgeFromSearch(text);
            List<AiConversationMessage> historySnapshot = aiMemoryEnabled
                    ? snapshotAiConversationHistory()
                    : List.of();
            String prompt = buildAiPrompt(text, references, historySnapshot);
            String reply = AiApiClient.chatStream(prompt, delta -> {
                if (aiCancelled || delta == null || delta.isEmpty()) {
                    return;
                }
                messageView.appendRaw(delta);
                scheduleAiMessageRender(messageView);
            });
            Platform.runLater(() -> {
                if (aiCancelled) {
                    aiStreamingMessage = null;
                    aiTaskFuture = null;
                    updateAiSendButtonText(false);
                    return;
                }
                String content = reply != null && !reply.isEmpty() ? reply : I18n.t("ai.notice.api_error");
                rememberAiConversationTurn(text, sanitizeAiReplyForDisplay(reply));
                content = appendAiReferences(content, references);
                renderAiMessage(messageView, content, true);
                scrollAiChatToBottom();
                aiStreamingMessage = null;
                aiTaskFuture = null;
                updateAiSendButtonText(false);
            });
        });
    }

    private String buildAiPrompt(String userQuestion,
                                 List<MarkdownSearchUtil.KnowledgeReference> references,
                                 List<AiConversationMessage> history) {
        String safeQuestion = userQuestion == null ? "" : userQuestion.trim();
        StringBuilder prompt = new StringBuilder();
        prompt.append("请优先参考下面提供的知识库检索结果回答用户问题。");
        prompt.append("如果检索内容不足以支撑结论，结合通用知识或网络信息补充完整。");
        if (history != null && !history.isEmpty()) {
            prompt.append("\n\n最近对话历史（按时间顺序，最多保留最近")
                    .append(AI_HISTORY_TURNS)
                    .append("轮）：");
            for (AiConversationMessage message : history) {
                if (message == null || message.content() == null || message.content().isBlank()) {
                    continue;
                }
                prompt.append("\n\n[")
                        .append("assistant".equalsIgnoreCase(message.role()) ? "助手" : "用户")
                        .append("]\n")
                        .append(message.content().trim());
            }
        }
        prompt.append("\n\n当前用户问题：\n").append(safeQuestion);
        if (!references.isEmpty()) {
            prompt.append("\n\n知识库检索结果（与侧边栏搜索一致，按相关性排序，共")
                    .append(references.size())
                    .append("条片段）：");
            for (int i = 0; i < references.size(); i++) {
                MarkdownSearchUtil.KnowledgeReference ref = references.get(i);
                prompt.append("\n\n[").append(i + 1).append("]");
                if (ref.title() != null && !ref.title().isBlank()) {
                    prompt.append("\n来源：").append(ref.title());
                }
                if (ref.snippet() != null && !ref.snippet().isBlank()) {
                    prompt.append("\n摘要：").append(ref.snippet());
                }
            }
        } else {
            prompt.append("\n\n知识库检索结果：未匹配到相关文档。");
        }
        return prompt.toString();
    }

    private String appendAiReferences(String reply, List<MarkdownSearchUtil.KnowledgeReference> references) {
        String content = sanitizeAiReplyForDisplay(reply == null ? "" : reply.trim()).trim();
        if (references == null || references.isEmpty()) {
            return content;
        }
        StringBuilder builder = new StringBuilder(content);
        if (!content.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append("参考文档：\n");
        int displayIndex = 1;
        LinkedHashMap<String, LinkedHashSet<String>> shownPaths = new LinkedHashMap<>();
        for (MarkdownSearchUtil.KnowledgeReference ref : references) {
            if (ref == null || ref.path() == null || ref.path().isBlank()) {
                continue;
            }
            LinkedHashSet<String> pageLabels = shownPaths.get(ref.path());
            if (pageLabels == null) {
                if (shownPaths.size() >= MarkdownSearchUtil.AI_UI_REFERENCE_LINK_COUNT) {
                    continue;
                }
                pageLabels = new LinkedHashSet<>();
                shownPaths.put(ref.path(), pageLabels);
            }
            pageLabels.addAll(extractAiReferenceLocationLabels(ref.title()));
        }
        for (java.util.Map.Entry<String, LinkedHashSet<String>> entry : shownPaths.entrySet()) {
            String path = entry.getKey();
            if (path == null || path.isBlank()) {
                continue;
            }
            String linkTarget = path.replace('\\', '/');
            String title;
            try {
                title = Paths.get(path).getFileName().toString();
            } catch (Exception ex) {
                title = linkTarget;
            }
            String pageSuffix = formatAiReferencePageSuffix(entry.getValue());
            builder.append(displayIndex++)
                    .append(". [")
                    .append(title)
                    .append(pageSuffix)
                    .append("](")
                    .append(linkTarget)
                    .append(")\n");
        }
        return builder.toString().trim();
    }

    private List<String> extractAiReferenceLocationLabels(String title) {
        if (title == null || title.isBlank()) {
            return List.of();
        }
        String detail = title;
        int splitIndex = detail.indexOf(" · ");
        if (splitIndex >= 0 && splitIndex + 3 < detail.length()) {
            detail = detail.substring(splitIndex + 3);
        }
        List<String> pageLabels = new ArrayList<>();
        Matcher matcher = AI_REFERENCE_LOCATION_PATTERN.matcher(detail);
        while (matcher.find()) {
            pageLabels.add(matcher.group());
        }
        return pageLabels;
    }

    private String formatAiReferencePageSuffix(LinkedHashSet<String> pageLabels) {
        if (pageLabels == null || pageLabels.isEmpty()) {
            return "";
        }
        return "（" + String.join("、", pageLabels) + "）";
    }

    private List<AiConversationMessage> snapshotAiConversationHistory() {
        synchronized (aiConversationHistory) {
            return new ArrayList<>(aiConversationHistory);
        }
    }

    private void rememberAiConversationTurn(String userText, String assistantText) {
        if (!aiMemoryEnabled) {
            return;
        }
        String safeUser = userText == null ? "" : userText.trim();
        String safeAssistant = assistantText == null ? "" : assistantText.trim();
        if (safeUser.isEmpty() || safeAssistant.isEmpty()) {
            return;
        }
        synchronized (aiConversationHistory) {
            aiConversationHistory.add(new AiConversationMessage("user", safeUser));
            aiConversationHistory.add(new AiConversationMessage("assistant", safeAssistant));
            int maxMessages = AI_HISTORY_TURNS * 2;
            while (aiConversationHistory.size() > maxMessages) {
                aiConversationHistory.remove(0);
            }
        }
    }

    private String sanitizeAiReplyForDisplay(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        return AiApiClient.stripThinkingFromAssistantReply(content);
    }

    private String buildApiKeyGuideMessage() {
        String provider = AiAuthClient.getCurrentProviderKey();
        return String.join("\n",
                I18n.t("ai.reply.api_key_guide.title"),
                "",
                I18n.t("ai.reply.api_key_guide.step1"),
                I18n.t("ai.reply.api_key_guide." + provider + ".link", I18n.t("ai.reply.api_key_guide.link")),
                I18n.t("ai.reply.api_key_guide." + provider + ".step2", I18n.t("ai.reply.api_key_guide.step2")),
                I18n.t("ai.reply.api_key_guide.step3"),
                I18n.t("ai.reply.api_key_guide.step4"),
                "",
                I18n.t("ai.reply.api_key_guide." + provider + ".model_reason", I18n.t("ai.reply.api_key_guide.model_reason"))
        );
    }

    private void cancelAiRequest() {
        aiCancelled = true;
        AiMessageView streaming = aiStreamingMessage;
        if (streaming != null) {
            streaming.stopThinkingAnimation();
            String aborted = I18n.t("ai.aborted");
            streaming.setRaw(aborted);
            renderStreamingAiMessage(streaming, aborted);
            setAiMessageActionsVisible(streaming, true);
        }
        if (aiTaskFuture != null && !aiTaskFuture.isDone()) {
            aiTaskFuture.cancel(true);
        }
        aiTaskFuture = null;
        aiStreamingMessage = null;
        updateAiSendButtonText(false);
    }

    private void updateAiMemoryToggleButton() {
        if (aiMemoryToggleButton == null) {
            return;
        }
        aiMemoryToggleButton.setText(aiMemoryEnabled
                ? I18n.t("ai.memory.enabled")
                : I18n.t("ai.memory.disabled"));
        String tooltipKey = aiMemoryEnabled
                ? "ai.memory.toggle.disable.tooltip"
                : "ai.memory.toggle.enable.tooltip";
        Tooltip tooltip = aiMemoryToggleButton.getTooltip();
        if (tooltip == null) {
            tooltip = new Tooltip();
            aiMemoryToggleButton.setTooltip(tooltip);
        }
        tooltip.setText(I18n.t(tooltipKey));
        aiMemoryToggleButton.getStyleClass().removeAll(AI_MEMORY_BTN_STYLE_ON, AI_MEMORY_BTN_STYLE_OFF);
        aiMemoryToggleButton.getStyleClass().add(aiMemoryEnabled ? AI_MEMORY_BTN_STYLE_ON : AI_MEMORY_BTN_STYLE_OFF);
    }

    private void updateAiSendButtonText(boolean thinking) {
        if (aiSendButton == null) {
            return;
        }
        aiSendButton.setText("");
        if (thinking) {
            aiSendButton.setGraphic(
                    IconFactory.groupFixedColor(IconPaths.AI_STOP, 0.72, IconFactory.stopColor())
            );
        } else {
            aiSendButton.setGraphic(
                    IconFactory.group(IconPaths.AI_SEND, 0.7)
            );
        }
    }

    private void addAiMarkdownMessage(String content) {
        AiMessageView view = createAiMessageView(() -> content == null ? "" : content);
        aiChatMessages.getChildren().add(view.messageGroup);
        keepAiMessageVisible(view.bubble, view.messageGroup);
        renderAiMessage(view, content, true);
        scrollAiChatToBottom();
    }

    private AiMessageView addStreamingAiMessage(String initialContent) {
        AiMessageView[] ref = new AiMessageView[1];
        AiMessageView view = createAiMessageView(() ->
                sanitizeAiReplyForDisplay(ref[0].getRaw() == null ? "" : ref[0].getRaw()));
        ref[0] = view;
        setAiMessageActionsVisible(view, false);
        aiChatMessages.getChildren().add(view.messageGroup);
        keepAiMessageVisible(view.bubble, view.messageGroup);
        renderStreamingAiMessage(view, initialContent);
        scrollAiChatToBottom();
        return view;
    }

    private AiMessageView createAiMessageView(Supplier<String> textSupplier) {
        VBox messageGroup = new VBox();
        StackPane bubble = new StackPane();
        HBox buttonRow = createMessageButtonRow(textSupplier, Pos.CENTER_LEFT);
        AiMessageView view = new AiMessageView(messageGroup, bubble, buttonRow);
        configureAiMessageArea(view.area);
        configureAiStreamingLabel(view.streamingLabel, bubble);
        bubble.getChildren().addAll(view.streamingLabel, view.area);
        bubble.getStyleClass().add("ai-message-bubble");
        bubble.prefWidthProperty().bind(aiChatMessages.widthProperty().subtract(24));
        bubble.maxWidthProperty().bind(aiChatMessages.widthProperty().subtract(24));
        view.area.prefWidthProperty().bind(bubble.widthProperty());
        view.area.maxWidthProperty().bind(bubble.widthProperty());
        messageGroup.getChildren().setAll(bubble, buttonRow);
        messageGroup.setSpacing(4);
        messageGroup.setAlignment(Pos.CENTER_LEFT);
        messageGroup.setFillWidth(true);
        return view;
    }

    private void configureAiMessageArea(CustomAiStyledArea area) {
        area.setEditable(false);
        area.getStyleClass().add("ai-message-area");
    }

    private void configureAiStreamingLabel(Label label, StackPane bubble) {
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        if (!label.getStyleClass().contains(AI_STREAMING_LABEL_STYLE)) {
            label.getStyleClass().add(AI_STREAMING_LABEL_STYLE);
        }
        label.maxWidthProperty().bind(bubble.widthProperty().subtract(20));
        StackPane.setAlignment(label, Pos.CENTER_LEFT);
    }

    private void scheduleAiMessageRender(AiMessageView view) {
        if (view == null || !view.renderScheduled.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(() -> {
            try {
                int revision = view.revision.get();
                String displayContent = sanitizeAiReplyForDisplay(
                        view.getRaw() == null ? "" : view.getRaw());
                renderStreamingAiMessage(view, displayContent);
                view.renderedRevision.set(revision);
            } finally {
                view.renderScheduled.set(false);
                if (view.revision.get() != view.renderedRevision.get()) {
                    scheduleAiMessageRender(view);
                }
            }
        });
    }

    private void renderAiMessage(AiMessageView view, String content, boolean updateRaw) {
        if (view == null) {
            return;
        }
        view.stopThinkingAnimation();
        if (updateRaw) {
            view.setRaw(content);
        }
        showFinalAiContent(view);
        view.streamingLabel.setText("");
        view.area.clear();
        view.area.parseMarkdownWithStyles(sanitizeAiReplyForDisplay(content == null ? "" : content));
        setAiMessageActionsVisible(view, updateRaw);
    }

    private void renderStreamingAiMessage(AiMessageView view, String content) {
        if (view == null) {
            return;
        }
        showStreamingAiContent(view);
        String raw = view.getRaw();
        if (raw.isEmpty() && isAiReplyingPlaceholder(content)) {
            view.startThinkingAnimation(AiMessageView.aiThinkingBaseText(I18n.t("ai.replying")));
        } else {
            view.stopThinkingAnimation();
            view.streamingLabel.setText(content == null ? "" : content);
        }
    }

    private static boolean isAiReplyingPlaceholder(String content) {
        if (content == null) {
            return true;
        }
        return content.trim().equals(I18n.t("ai.replying").trim());
    }

    private void showStreamingAiContent(AiMessageView view) {
        if (view == null) {
            return;
        }
        view.streamingLabel.setManaged(true);
        view.streamingLabel.setVisible(true);
        view.area.setManaged(false);
        view.area.setVisible(false);
    }

    private void showFinalAiContent(AiMessageView view) {
        if (view == null) {
            return;
        }
        view.area.setManaged(true);
        view.area.setVisible(true);
        view.streamingLabel.setManaged(false);
        view.streamingLabel.setVisible(false);
    }

    private void setAiMessageActionsVisible(AiMessageView view, boolean visible) {
        if (view == null || view.buttonRow == null) {
            return;
        }
        view.buttonRow.setVisible(visible);
        view.buttonRow.setManaged(visible);
    }

    private void addUserMarkdownMessage(String content) {
        String text = content == null ? "" : content;

        Label messageLabel = new Label(text);
        messageLabel.setWrapText(true);
        messageLabel.setMaxWidth(Double.MAX_VALUE);
        messageLabel.getStyleClass().add("ai-user-message-label");

        StackPane bubble = new StackPane(messageLabel);
        bubble.getStyleClass().add("ai-user-message-bubble");
        bubble.maxWidthProperty().bind(aiChatMessages.widthProperty().multiply(USER_BUBBLE_MAX_WIDTH_RATIO));
        messageLabel.maxWidthProperty().bind(bubble.maxWidthProperty().subtract(20));
        HBox messageRow = createMessageRow(bubble, () -> text, Pos.CENTER_RIGHT, Pos.CENTER_RIGHT);
        aiChatMessages.getChildren().add(messageRow);
        keepAiMessageVisible(bubble, messageRow);
        scrollAiChatToBottom();
    }

    private HBox createMessageRow(Node messageNode, Supplier<String> textSupplier, Pos rowAlignment, Pos buttonAlignment) {
        VBox messageGroup = new VBox(4, messageNode, createMessageButtonRow(textSupplier, buttonAlignment));
        messageGroup.setAlignment(buttonAlignment);
        messageGroup.setFillWidth(true);
        HBox messageRow = new HBox(messageGroup);
        messageRow.setAlignment(rowAlignment);
        messageRow.setFillHeight(false);
        return messageRow;
    }

    private HBox createMessageButtonRow(Supplier<String> textSupplier, Pos buttonAlignment) {
        HBox buttonRow = new HBox(createMessageCopyButton(textSupplier));
        buttonRow.setAlignment(buttonAlignment);
        buttonRow.setFillHeight(false);
        return buttonRow;
    }

    private Button createMessageCopyButton(Supplier<String> textSupplier) {
        Button copyButton = new Button();
        copyButton.setText("");
        copyButton.setGraphic(IconFactory.group(IconPaths.COPY, 0.62));
        copyButton.getStyleClass().add("small");
        copyButton.setFocusTraversable(false);
        copyButton.setTooltip(new Tooltip(I18n.t("genericstyled.menu.copy")));
        copyButton.setOnAction(event -> copyMessageText(textSupplier == null ? "" : textSupplier.get()));
        return copyButton;
    }

    private void copyMessageText(String text) {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(text == null ? "" : text);
        clipboard.setContent(content);
        NotificationUtil.showMainNotification(I18n.t("resultset.notice.copied"));
    }

    private void keepAiMessageVisible(Region messageRegion, Region containerRegion) {
        if (messageRegion != null) {
            messageRegion.heightProperty().addListener((obs, oldVal, newVal) -> scrollAiChatToBottom());
            messageRegion.layoutBoundsProperty().addListener((obs, oldVal, newVal) -> scrollAiChatToBottom());
        }
        if (containerRegion != null) {
            containerRegion.heightProperty().addListener((obs, oldVal, newVal) -> scrollAiChatToBottom());
            containerRegion.layoutBoundsProperty().addListener((obs, oldVal, newVal) -> scrollAiChatToBottom());
        }
    }

    private void scrollAiChatToBottom() {
        if (aiChatScrollPane == null || !aiScrollScheduled.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(() -> {
            try {
                if (aiChatScrollPane != null) {
                    aiChatScrollPane.setVvalue(1.0);
                }
            } finally {
                aiScrollScheduled.set(false);
            }
        });
    }
}
