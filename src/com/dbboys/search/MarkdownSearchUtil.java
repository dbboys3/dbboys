package com.dbboys.search;

import com.dbboys.app.AppExecutor;
import com.dbboys.app.AppState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.ui.util.TabpaneUtil;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.ui.notification.NotificationUtil;

import javafx.application.Platform;
import javafx.beans.binding.StringBinding;
import javafx.collections.FXCollections;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Popup;
import javafx.stage.Stage;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.*;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.wltea.analyzer.lucene.IKAnalyzer;

import java.io.File;
import java.io.IOException;
import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class MarkdownSearchUtil {
    private static final Logger log = LogManager.getLogger(MarkdownSearchUtil.class);
    private static final Popup searchResultPopup = new Popup();
    private static final Path indexDir = Paths.get("index");

    /**
     * 与 {@link #performSearch(String)} 从 Lucene 拉取的候选条数一致。
     * 侧边栏与 AI 都基于同一套 chunk/file 检索链路；若只拉前几条再重排，前几位会与最终展示不一致。
     */
    public static final int SEARCH_UI_FETCH_LIMIT = 50;

    /** 发给大模型的知识库片段条数（与侧边栏「搜索」同一套 {@link LuceneSearcher#search(String, int)} 排序与摘要） */
    public static final int AI_PROMPT_SNIPPET_COUNT = 10;

    /** AI 回复末尾「参考文档」展示的链接条数（取 {@link #loadAiKnowledgeFromSearch(String)} 结果的前若干条） */
    public static final int AI_UI_REFERENCE_LINK_COUNT = 3;

    /** 复用 DirectoryReader，避免每次搜索都 FSDirectory.open + DirectoryReader.open（磁盘与 inode 开销大） */
    private static volatile Directory mdSharedDirectory;
    private static volatile DirectoryReader mdSharedReader;
    private static Path mdCachedIndexPath;
    private static final Object MD_INDEX_LOCK = new Object();

    /**
     * 获取共享的 {@link IndexSearcher}；同一索引目录下使用 openIfChanged 自动感知段合并/外部更新。
     * 重建索引前须调用 {@link #invalidateIndexReader()} 以关闭句柄、避免 Windows 下删除索引文件失败。
     */
    static IndexSearcher acquireIndexSearcher(Path path) throws IOException {
        synchronized (MD_INDEX_LOCK) {
            Path normalized = path.toAbsolutePath().normalize();
            if (!Files.exists(normalized)) {
                throw new IOException("Index directory does not exist: " + normalized);
            }
            if (mdSharedReader == null || mdCachedIndexPath == null || !mdCachedIndexPath.equals(normalized)) {
                closeIndexReaderUnsafe();
                mdSharedDirectory = FSDirectory.open(normalized);
                if (!DirectoryReader.indexExists(mdSharedDirectory)) {
                    mdSharedDirectory.close();
                    mdSharedDirectory = null;
                    throw new IOException("No valid Lucene index in: " + normalized);
                }
                mdSharedReader = DirectoryReader.open(mdSharedDirectory);
                mdCachedIndexPath = normalized;
            } else {
                DirectoryReader newReader = DirectoryReader.openIfChanged(mdSharedReader);
                if (newReader != null) {
                    mdSharedReader.close();
                    mdSharedReader = newReader;
                }
            }
            return new IndexSearcher(mdSharedReader);
        }
    }

    static void invalidateIndexReader() {
        synchronized (MD_INDEX_LOCK) {
            mdCachedIndexPath = null;
            closeIndexReaderUnsafe();
        }
    }

    private static void closeIndexReaderUnsafe() {
        if (mdSharedReader != null) {
            try {
                mdSharedReader.close();
            } catch (IOException e) {
                log.debug("Close shared DirectoryReader", e);
            }
            mdSharedReader = null;
        }
        if (mdSharedDirectory != null) {
            try {
                mdSharedDirectory.close();
            } catch (IOException e) {
                log.debug("Close shared Directory", e);
            }
            mdSharedDirectory = null;
        }
    }
    private static final ListView<LuceneSearcher.SearchResult> resultList = new ListView<>();
    private static final Label resultPlaceholderLabel = new Label();
    private static final StringBinding errorTitleBinding = I18n.bind("common.error", "错误");
    private static final StringBinding noMatchBinding = I18n.bind("markdown.search.notice.no_match", "搜索没有匹配项！");
    private static final StringBinding rebuildDoneBinding = I18n.bind("markdown.search.notice.rebuild_done", "索引重建完成！");
    private static final StringBinding buildFailedBinding = I18n.bind("markdown.search.error.build_failed", "索引建立失败：%s");
    private static final StringBinding warmUpKeywordBinding = I18n.bind("markdown.search.warmup.keyword", "安装配置");
    private static String keywordField;
    private static boolean popupListenersAdded = false;
    private static final AtomicBoolean indexBuildRunning = new AtomicBoolean(false);

    public record KnowledgeReference(String path, String title, String snippet) {}

    static {
        resultList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            //if (newVal != null) showPreview(newVal.path);
        });
        resultList.setCellFactory(lv -> new ListCell<LuceneSearcher.SearchResult>() {
            @Override
            protected void updateItem(LuceneSearcher.SearchResult item, boolean empty) {
                super.updateItem(item, empty);
                setOnMouseClicked(null);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(null);
                    TextFlow flow = buildHighlightedText(item, keywordField == null ? "" : keywordField.trim());
                    setGraphic(flow);
                    setOnMouseClicked(event -> {
                        if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 1) {
                            LuceneSearcher.SearchResult clickedItem = getItem();
                            openSearchResult(clickedItem.path);
                            searchResultPopup.hide();
                        }
                    });
                }

            }


        });
        searchResultPopup.getContent().add(resultList);
        resultList.setFocusTraversable(false);
        resultList.setPrefWidth(480);
        resultList.getStyleClass().addAll("striped", "search-result-list");
        resultPlaceholderLabel.textProperty().bind(I18n.bind("markdown.search.placeholder", "暂无搜索结果"));
        resultList.setPlaceholder(resultPlaceholderLabel);
    }

    private static TextFlow buildHighlightedText(LuceneSearcher.SearchResult item, String keyword) {
        TextFlow flow = new TextFlow();
        flow.setLineSpacing(2);

        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        if (normalizedKeyword.isBlank()) {
            flow.getChildren().add(new Text(formatSearchResultHeader(item) + "\n"));
            return flow;
        }

        // ====== 分词提取 ======
        LinkedHashSet<String> tokenSet = new LinkedHashSet<>(MarkdownSearchNormalizer.extractExactIdentifiers(normalizedKeyword));
        try (Analyzer analyzer = MarkdownSearchAnalyzers.createAnalyzer();
             TokenStream ts = analyzer.tokenStream("content", normalizedKeyword)) {
            ts.reset();
            while (ts.incrementToken()) {
                String term = ts.getAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class).toString();
                if (!term.isBlank()) tokenSet.add(term);
            }
            ts.end();
        } catch (Exception e) {
            log.debug("Tokenizer failed, falling back to raw keyword", e);
        }

        List<String> tokens = MarkdownSearchNormalizer.pruneShortQueryTokens(new ArrayList<>(tokenSet));
        tokens.sort(Comparator.comparingInt(String::length).reversed());
        if (tokens.isEmpty() && !MarkdownSearchNormalizer.pruneShortQueryTokens(List.of(normalizedKeyword)).isEmpty()) {
            tokens.add(normalizedKeyword);
        }

        // ====== 高亮结果头部（路径 + chunk 标题） ======
        String tokenPattern = tokens.stream()
                .map(Pattern::quote)
                .reduce((a, b) -> a + "|" + b)
                .orElse(Pattern.quote(normalizedKeyword));
        Pattern highlightPattern = Pattern.compile(tokenPattern, Pattern.CASE_INSENSITIVE);
        appendHighlightedText(flow,
                item.path == null ? "" : item.path,
                highlightPattern,
                Font.font("System", FontWeight.BOLD, 10),
                null,
                "markdown-search-match");
        if (item.title != null && !item.title.isBlank()) {
            flow.getChildren().add(new Text("\n"));
            appendHighlightedText(flow,
                    item.title,
                    highlightPattern,
                    Font.font("System", FontWeight.SEMI_BOLD, 9),
                    "markdown-search-title",
                    "markdown-search-match");
        }

        flow.getChildren().add(new Text("\n"));

        // ====== 高亮 snippet 内容 ======
        String snippet = item.snippet == null ? "" : item.snippet.strip();
        if (snippet.isEmpty()) return flow;

        // 限制 snippet 最多 5 行
        String[] lines = snippet.split("\\R");
        StringBuilder limitedSnippet = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 5); i++) {
            limitedSnippet.append(lines[i]).append("\n");
        }
        if (lines.length > 5) limitedSnippet.append("...");

        appendHighlightedText(flow,
                limitedSnippet.toString(),
                highlightPattern,
                Font.font("System", FontWeight.NORMAL, 8),
                "markdown-search-snippet",
                "markdown-search-match");

        return flow;
    }

    private static String formatSearchResultHeader(LuceneSearcher.SearchResult item) {
        if (item == null) {
            return "";
        }
        String path = item.path == null ? "" : item.path;
        String title = item.title == null ? "" : item.title.trim();
        if (title.isBlank()) {
            return path;
        }
        return path + "\n" + title;
    }

    private static void appendHighlightedText(TextFlow flow,
                                              String source,
                                              Pattern pattern,
                                              Font font,
                                              String normalStyle,
                                              String matchStyle) {
        if (flow == null || source == null || source.isEmpty()) {
            return;
        }
        if (pattern == null) {
            Text text = new Text(source);
            text.setFont(font);
            if (normalStyle != null && !normalStyle.isBlank()) {
                text.getStyleClass().add(normalStyle);
            }
            flow.getChildren().add(text);
            return;
        }

        Matcher matcher = pattern.matcher(source);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) {
                Text normal = new Text(source.substring(last, matcher.start()));
                normal.setFont(font);
                if (normalStyle != null && !normalStyle.isBlank()) {
                    normal.getStyleClass().add(normalStyle);
                }
                flow.getChildren().add(normal);
            }
            Text match = new Text(source.substring(matcher.start(), matcher.end()));
            match.setFont(font);
            if (matchStyle != null && !matchStyle.isBlank()) {
                match.getStyleClass().add(matchStyle);
            }
            flow.getChildren().add(match);
            last = matcher.end();
        }
        if (last < source.length()) {
            Text tail = new Text(source.substring(last));
            tail.setFont(font);
            if (normalStyle != null && !normalStyle.isBlank()) {
                tail.getStyleClass().add(normalStyle);
            }
            flow.getChildren().add(tail);
        }
    }


    public static void buildIndex(){
        buildIndex(true);
    }
    public static void buildIndex(boolean isNeedNotice) {
        if (!indexBuildRunning.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(() -> AppState.getRebuildMarkdownIndexButton().setVisible(false));
        AppExecutor.runAsync(() -> {
        long start = System.currentTimeMillis();
        try {
            // 先关闭共享 Reader，释放文件句柄，否则 Windows 下删除 index 目录可能失败
            invalidateIndexReader();
            if (Files.exists(indexDir)) {
                try (Stream<Path> walk = Files.walk(indexDir)) {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                   // System.err.println("删除失败: " + path + " => " + e.getMessage());
                                }
                            });
                }
            }
            LuceneIndexer indexer = new LuceneIndexer(indexDir);
            indexer.buildIndex(Paths.get("docs"));
            log.info("Markdown index rebuild finished in {} ms", System.currentTimeMillis() - start);
            if(isNeedNotice){
                Platform.runLater(MarkdownSearchUtil::notifyIndexBuildSuccess);
            }
        } catch (Exception e) {
            Platform.runLater(() -> AlertUtil.CustomAlert(errorTitleBinding.get(),
                    buildFailedBinding.get().formatted(e.getMessage())));
            log.error("Operation failed", e);
        } finally {
            indexBuildRunning.set(false);
            Platform.runLater(() -> AppState.getRebuildMarkdownIndexButton().setVisible(true));
        }
        });
    }

    private static void notifyIndexBuildSuccess() {
        NotificationUtil.showMainNotification(rebuildDoneBinding.get());
    }

    private static void setSearchButtonRunning(boolean running) {
        Platform.runLater(() -> {
            Button searchButton = AppState.getMarkdownSearchButton();
            if (searchButton != null) {
                searchButton.setVisible(!running);
            }
        });
    }

    public static void performSearch(String searchText) {
        setSearchButtonRunning(true);
        if (searchText.isEmpty()) {
            setSearchButtonRunning(false);
            return;
        }
        String normalizedKeyword = MarkdownSearchNormalizer.normalizeQuery(searchText);
        if (normalizedKeyword.isBlank()) {
            searchResultPopup.hide();
            setSearchButtonRunning(false);
            return;
        }
        keywordField = searchText.trim();
        // 在后台线程执行 Lucene 检索与摘要生成，避免阻塞 JavaFX 线程导致界面卡顿
        AppExecutor.runAsync(() -> {
        try {
            LuceneSearcher searcher = new LuceneSearcher(indexDir);
            List<LuceneSearcher.SearchResult> results = searcher.searchForAi(searchText, SEARCH_UI_FETCH_LIMIT);
            Platform.runLater(()->{
                Stage mainStage = (Stage) AppState.getWindow();
                if (!popupListenersAdded) {
                    mainStage.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
                        if (searchResultPopup.isShowing()) {
                            // 1. 将主窗口内的鼠标坐标转换为屏幕绝对坐标
                            double mouseX = mainStage.getX() + event.getX();
                            double mouseY = mainStage.getY() + event.getY();

                            // 2. 获取弹窗的屏幕坐标范围
                            double popupX = searchResultPopup.getX();
                            double popupY = searchResultPopup.getY();
                            double popupWidth = searchResultPopup.getWidth();
                            double popupHeight = searchResultPopup.getHeight();

                            // 3. 判断鼠标是否在弹窗外部
                            boolean isOutside = mouseX < popupX
                                    || mouseX > popupX + popupWidth
                                    || mouseY < popupY
                                    || mouseY > popupY + popupHeight;

                            if (isOutside) {
                                searchResultPopup.hide();
                            }
                        }
                    });
                    mainStage.focusedProperty().addListener((obs, oldFocused, newFocused) -> {
                        // 主窗口失去焦点（切换到其他程序），隐藏 Popup
                        if (!newFocused && searchResultPopup.isShowing()) {
                            searchResultPopup.hide();
                        }
                    });
                    popupListenersAdded=true;
                }
                resultList.setItems(FXCollections.observableArrayList(results));
                resultList.getSelectionModel().select(null);//避免第一个被选中显示背景色
                resultList.setPrefHeight(mainStage.getHeight()-80);
                resultList.getStyleClass().add("search-result-popup-list");
                //这个设置可以避免出现search_result_popup在第一次搜索“配置”时靠顶显示
                searchResultPopup.setAutoFix(false);
                if(resultList.getItems().size()>0){
                    resultList.scrollTo(0);
                    searchResultPopup.show(mainStage,
                            mainStage.getX() + 28,
                            mainStage.getY() + 50);
                }else{
                    searchResultPopup.hide();
                    NotificationUtil.showMainNotification(noMatchBinding.get());
                }

                DropShadow shadow = new DropShadow();
                shadow.setRadius(10);              // 模糊半径
                shadow.setOffsetX(0);              // 阴影水平偏移
                shadow.setOffsetY(0);              // 阴影垂直偏移
                shadow.setColor(Color.rgb(0, 0, 0, 0.3));  // 阴影颜色（含透明度）

                resultList.setEffect(shadow);
            });
        } catch (Exception e) {
            Platform.runLater(()->{
                AlertUtil.CustomAlert(errorTitleBinding.get(), e.getMessage());
            });
            log.error("Operation failed", e);
        } finally {
            setSearchButtonRunning(false);
        }
        });
    }

    /*
    private static void showPreview(String path) {
        try {
            String content = Files.readString(Paths.get(path));
            previewArea.setText(content.substring(0, Math.min(2000, content.length())));
        } catch (Exception e) {
            previewArea.setText("无法读取文件：" + e.getMessage());
        }
    }

     */


    public static void warmUpIndex() {
        try  {
            keywordField = warmUpKeywordBinding.get();
            LuceneSearcher searcher = new LuceneSearcher(indexDir);
            searcher.searchForAi(warmUpKeywordBinding.get(), SEARCH_UI_FETCH_LIMIT);
        } catch (Exception e) {
            log.debug("Index warm-up failed", e);
        }
    }

    /**
     * 为 AI 加载知识库：与 {@link #performSearch(String)} 使用相同的排序与命中规则，
     * 但发送给模型的摘要片段长度会放大到侧边栏搜索的两倍；在重排后取前 {@link #AI_PROMPT_SNIPPET_COUNT} 条发给模型，
     * 回复中可再只展示前 {@link #AI_UI_REFERENCE_LINK_COUNT} 条文档链接。
     */
    public static List<KnowledgeReference> loadAiKnowledgeFromSearch(String keyword) {
        String normalizedKeyword = MarkdownSearchNormalizer.normalizeQuery(keyword);
        if (normalizedKeyword.isBlank()) {
            return Collections.emptyList();
        }
        try {
            LuceneSearcher searcher = new LuceneSearcher(indexDir);
            int fetchSize = Math.max(AI_PROMPT_SNIPPET_COUNT, SEARCH_UI_FETCH_LIMIT);
            List<LuceneSearcher.SearchResult> results = searcher.searchForAi(keyword, fetchSize);
            List<KnowledgeReference> references = new ArrayList<>();
            for (LuceneSearcher.SearchResult item : results) {
                if (references.size() >= AI_PROMPT_SNIPPET_COUNT) {
                    break;
                }
                String path = item.path == null ? "" : item.path.trim();
                if (path.isEmpty()) {
                    continue;
                }
                String heading = item.title == null ? "" : item.title.trim();
                String title = heading.isBlank() ? path : path + " · " + heading;
                String snippet = item.snippet == null ? "" : item.snippet.replace("\r", "").trim();
                references.add(new KnowledgeReference(path, title, snippet));
            }
            return references;
        } catch (Exception e) {
            log.warn("Knowledge search for AI failed: {}", keyword, e);
            return Collections.emptyList();
        }
    }

    private static void openSearchResult(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        File file = new File(path);
        if (!file.exists()) {
            AlertUtil.CustomAlert(errorTitleBinding.get(), I18n.t("tabpane.error.file_not_exists", "文件不存在！"));
            return;
        }
        String lowerPath = path.toLowerCase();
        if (lowerPath.endsWith(".md") || lowerPath.endsWith(".markdown")) {
            TabpaneUtil.addCustomMarkdownTab(file, false);
            return;
        }
        try {
            Desktop.getDesktop().open(file);
        } catch (Exception ex) {
            AlertUtil.CustomAlert(errorTitleBinding.get(), ex.getMessage());
            log.error("Failed to open indexed file: {}", path, ex);
        }
    }


}

