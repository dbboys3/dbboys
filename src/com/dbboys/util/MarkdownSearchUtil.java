package com.dbboys.util;

import com.dbboys.app.AppExecutor;
import com.dbboys.app.AppState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.dbboys.i18n.I18n;
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
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.File;
import java.io.IOException;
import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * LuceneIndexer - 为 Markdown 文件夹建立 Lucene 索引
 *
 * 说明：
 * - content 字段被存储（Store.YES），便于在检索后读取原文或做高亮。
 * - buildIndex 可选择覆盖(CREATE)或追加(APPEND)模式。
 */
class LuceneIndexer {
    private static final Logger log = LogManager.getLogger(LuceneIndexer.class);

    private final Path indexDir;
    private final Analyzer analyzer;
    private static final StringBinding FOLDER_NOT_EXISTS_BINDING =
            I18n.bind("markdown.search.error.folder_not_exists", "markdownFolder 不存在: %s");
    private static final StringBinding INDEX_FILE_ERROR_BINDING =
            I18n.bind("markdown.search.error.index_file", "索引文件出错: %s -> %s");

    public LuceneIndexer(Path indexDir) {
        this.indexDir = indexDir;
        this.analyzer = new SmartChineseAnalyzer(); // 中文分词器
    }

    /**
     * 建立索引
     *
     * @param markdownFolder 要索引的 Markdown 文件夹
     * @param overwrite      true = 覆盖已有索引(重新创建)； false = 追加（增量索引）
     * @param progress       可选回调，接收当前处理文件路径（用于 UI 更新），可为 null
     * @throws IOException on IO error
     */
    public void buildIndex(Path markdownFolder, boolean overwrite, Consumer<String> progress) throws IOException {
        if (markdownFolder == null || !Files.exists(markdownFolder)) {
            throw new IllegalArgumentException(FOLDER_NOT_EXISTS_BINDING.get().formatted(markdownFolder));
        }

        Directory dir = FSDirectory.open(indexDir);
        IndexWriterConfig.OpenMode mode = overwrite ? IndexWriterConfig.OpenMode.CREATE : IndexWriterConfig.OpenMode.CREATE_OR_APPEND;
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(mode);

        try (IndexWriter writer = new IndexWriter(dir, config);
             Stream<Path> stream = Files.walk(markdownFolder)) {

            stream.filter(Files::isRegularFile)
                    .filter(DocumentIndexTextExtractor::isSupported)
                    .forEach(p -> {
                        try {
                            if (progress != null) progress.accept(p.toString());
                            indexFile(writer, p);
                        } catch (Exception ex) {
                            log.warn("Indexing file failed: {}", p, ex);
                            System.err.println(INDEX_FILE_ERROR_BINDING.get().formatted(p, ex.getMessage()));
                        }
                    });

            // 强制提交
            writer.commit();
        }
    }

    public void buildIndex(Path markdownFolder) throws IOException {
        buildIndex(markdownFolder, true, null);
    }

    /**
     * 把单个文件添加到索引（若需要实现更新，可先删除同 path 的旧 doc）
     */
    private static void indexFile(IndexWriter writer, Path file) throws IOException {

        String content = DocumentIndexTextExtractor.extractText(file);
        long modified = Files.getLastModifiedTime(file).toMillis();

        Document doc = new Document();

        // path 字段：存储并索引（便于精确匹配/打开文件）
        //doc.add(new StringField("path", file.toString(), Field.Store.YES));
        //分词匹配
        doc.add(new org.apache.lucene.document.TextField("path", file.toString(), Field.Store.YES));


        // fileName 便于展示/加权
        String fileName = file.getFileName().toString();
        doc.add(new StringField("filename", fileName, Field.Store.YES));

        // content: 存储并分词（便于高亮与显示）
        doc.add(new org.apache.lucene.document.TextField("content", content, Field.Store.YES));

        // modified 时间：用于排序或权重
        doc.add(new LongPoint("modified", modified));
        // 同时存储 modified 以便检索后取得值
        doc.add(new StoredField("modified_stored", modified));

        // 如果想让 filename / path 在查询时权重更高，可以在构建 Query 时为这些字段 boost
        // 或者在创建 Document 时使用 FieldType 并 setBoost()（Lucene 9 推荐在 Query 侧处理）

        // 增量更新：删除已存在的同 path 文档后添加新文档
        // 这保证了 path 的唯一性（避免重复）
        writer.updateDocument(new Term("path", file.toString()), doc);
    }

    /**
     * 删除索引目录（谨慎使用）
     */
    public void deleteIndex() throws IOException {
        if (Files.exists(indexDir)) {
            try (Stream<Path> s = Files.walk(indexDir)) {
                s.sorted((a, b) -> b.compareTo(a)) // 先删除子文件，再删除目录
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException e) { /* ignore */ }
                        });
            }
        }
    }
}

class LuceneSearcher {
    private static final Logger log = LogManager.getLogger(LuceneSearcher.class);
    private static final int DEFAULT_SNIPPET_CONTEXT_CHARS = 30;
    private static final int DEFAULT_FALLBACK_SNIPPET_CHARS = 120;
    private static final int DEFAULT_BOUNDARY_WINDOW_CHARS = 50;
    private static final int AI_SNIPPET_CONTEXT_CHARS = DEFAULT_SNIPPET_CONTEXT_CHARS * 2;
    private static final int AI_FALLBACK_SNIPPET_CHARS = DEFAULT_FALLBACK_SNIPPET_CHARS * 2;
    private static final int AI_BOUNDARY_WINDOW_CHARS = DEFAULT_BOUNDARY_WINDOW_CHARS * 2;
    private final Path indexDir;
    private final Analyzer analyzer = new SmartChineseAnalyzer();

    public LuceneSearcher(Path indexDir) {
        this.indexDir = indexDir;
    }

    public List<LuceneSearcher.SearchResult> search(String keyword, int limit) throws Exception {
        return search(keyword, limit, DEFAULT_SNIPPET_CONTEXT_CHARS, DEFAULT_FALLBACK_SNIPPET_CHARS, DEFAULT_BOUNDARY_WINDOW_CHARS);
    }

    public List<LuceneSearcher.SearchResult> searchForAi(String keyword, int limit) throws Exception {
        return search(keyword, limit, AI_SNIPPET_CONTEXT_CHARS, AI_FALLBACK_SNIPPET_CHARS, AI_BOUNDARY_WINDOW_CHARS);
    }

    private List<LuceneSearcher.SearchResult> search(String keyword,
                                                     int limit,
                                                     int contextChars,
                                                     int fallbackSnippetChars,
                                                     int boundaryWindowChars) throws Exception {
        try (Directory dir = FSDirectory.open(indexDir);
             DirectoryReader reader = DirectoryReader.open(dir)) {
            IndexSearcher searcher = new IndexSearcher(reader);

            String[] fields = {"path", "filename", "content"};
            Map<String, Float> boosts = Map.of(
                    "path", 1.5f,
                    "filename", 2.0f,
                    "content", 1.0f
            );

            MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, analyzer, boosts);
            Query query = parser.parse(keyword);

            TopDocs topDocs = searcher.search(query, limit);
            List<LuceneSearcher.SearchResult> results = new ArrayList<>();

            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(sd.doc);
                String path = doc.get("path");
                String content = doc.get("content");
                if (content == null) content = "";

            // --------- 1) 分词提取搜索 token（和你原来的一样） ----------
            List<String> tokens = new ArrayList<>();
            try (TokenStream ts = analyzer.tokenStream("content", keyword)) {
                ts.reset();
                while (ts.incrementToken()) {
                    String term = ts.getAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class).toString();
                    if (!term.isBlank()) tokens.add(term);
                }
                ts.end();
            } catch (Exception e) {
                log.debug("Tokenizer failed, falling back to raw keyword", e);
            }

            if (tokens.isEmpty()) tokens.add(keyword);

            // --------- 2) 收集所有匹配区间（去重） ----------
            String lowerContent = content.toLowerCase();
            List<int[]> positions = new ArrayList<>();

            for (String token : tokens) {
                if (token == null || token.isBlank()) continue;
                String lowerToken = token.toLowerCase();
                int idx = 0;
                while ((idx = lowerContent.indexOf(lowerToken, idx)) >= 0) {
                    int start = Math.max(0, idx - contextChars);
                    int end = Math.min(content.length(), idx + lowerToken.length() + contextChars);
                    positions.add(new int[]{start, end});
                    idx = idx + Math.max(1, lowerToken.length()); // 避免无限循环
                }
            }

            // 如果没有找到任何位置，则给出文首一小段作为 snippet
            if (positions.isEmpty()) {
                String fallback = content.length() > fallbackSnippetChars
                        ? content.substring(0, fallbackSnippetChars) + " ... "
                        : content;
                results.add(new LuceneSearcher.SearchResult(path, sd.score, fallback));
                continue;
            }

            // --------- 3) 合并重叠或相邻区间 ----------
            positions.sort((a, b) -> Integer.compare(a[0], b[0]));
            List<int[]> merged = new ArrayList<>();
            int mergeGap = 10; // 相邻多少字符以内合并（可调）
            for (int[] pos : positions) {
                if (merged.isEmpty()) {
                    merged.add(new int[]{pos[0], pos[1]});
                } else {
                    int[] last = merged.get(merged.size() - 1);
                    if (pos[0] <= last[1] + mergeGap) {
                        // 合并到上一个区间，扩展 end
                        last[1] = Math.max(last[1], pos[1]);
                    } else {
                        merged.add(new int[]{pos[0], pos[1]});
                    }
                }
            }

            // --------- 4) 限制段数并从原文生成 snippet（避免重复） ----------
            int maxSegments = 3;
            StringBuilder snippetBuilder = new StringBuilder();
            for (int i = 0; i < Math.min(merged.size(), maxSegments); i++) {
                int[] range = merged.get(i);
                // 为了使片段更整洁，可尝试在句子边界截断（向前找换行或句号）
                int s = range[0];
                int e = range[1];
                // 向前扩展到上一句结束（可选）
                int prevNL = content.lastIndexOf('\n', s);
                if (prevNL != -1 && s - prevNL < boundaryWindowChars) s = Math.max(0, prevNL + 1);
                // 向后延到句子结束（可选）
                int nextNL = content.indexOf('\n', e);
                if (nextNL != -1 && nextNL - e < boundaryWindowChars) e = nextNL;

                snippetBuilder.append(content, s, e).append(" ... ");
            }

            String snippet = snippetBuilder.toString();

            // --------- 5) 可选：对 snippet 中的关键词做高亮（这里不改 TextFlow 渲染，返回原 snippet） ----------
            // 如果你想返回带 <mark> 的 snippet，可以在此用正则替换 token 为 <mark>xxx</mark>
            // 但注意：你现在的 UI 用 TextFlow 对 snippet 做高亮，这里返回原文更灵活。

                results.add(new LuceneSearcher.SearchResult(path, sd.score, snippet));
            }

            return results;
        }
    }

    public static class SearchResult {
        public final String path;
        public final float score;
        public final String snippet;
        public SearchResult(String path, float score, String snippet) {
            this.path = path;
            this.score = score;
            this.snippet = snippet;
        }
        public SearchResult(String path, float score) {
            this(path, score, "");
        }
    }
}


public class MarkdownSearchUtil {
    private static final Logger log = LogManager.getLogger(MarkdownSearchUtil.class);
    private static final Popup searchResultPopup = new Popup();
    private static final Path indexDir = Paths.get("index");
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

        if (keyword == null || keyword.isBlank()) {
            flow.getChildren().add(new Text(item.path + "\n"));
            return flow;
        }

        // ====== 分词提取 ======
        List<String> tokens = new ArrayList<>();
        try (Analyzer analyzer = new SmartChineseAnalyzer();
             TokenStream ts = analyzer.tokenStream("content", keyword)) {
            ts.reset();
            while (ts.incrementToken()) {
                String term = ts.getAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class).toString();
                if (!term.isBlank()) tokens.add(term);
            }
            ts.end();
        } catch (Exception e) {
            log.debug("Tokenizer failed, falling back to raw keyword", e);
        }

        if (tokens.isEmpty()) tokens.add(keyword);

        // ====== 高亮路径 ======
        String fullPath = item.path;
        String tokenPattern = tokens.stream()
                .map(Pattern::quote)
                .reduce((a, b) -> a + "|" + b)
                .orElse(Pattern.quote(keyword));
        Pattern pathPattern = Pattern.compile(tokenPattern, Pattern.CASE_INSENSITIVE);
        Matcher pathMatcher = pathPattern.matcher(fullPath);

        int last = 0;
        while (pathMatcher.find()) {
            if (pathMatcher.start() > last) {
                Text normal = new Text(fullPath.substring(last, pathMatcher.start()));
                normal.setFont(Font.font("System", FontWeight.BOLD, 10));
                flow.getChildren().add(normal);
            }
            Text match = new Text(fullPath.substring(pathMatcher.start(), pathMatcher.end()));
            match.setStyle("-fx-fill: -color-danger-7;");
            match.setFont(Font.font("System", FontWeight.BOLD, 10));
            flow.getChildren().add(match);
            last = pathMatcher.end();
        }
        if (last < fullPath.length()) {
            Text tail = new Text(fullPath.substring(last));
            tail.setFont(Font.font("System", FontWeight.BOLD, 10));
            flow.getChildren().add(tail);
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

        Matcher snippetMatcher = pathPattern.matcher(limitedSnippet.toString());
        last = 0;
        while (snippetMatcher.find()) {
            if (snippetMatcher.start() > last) {
                Text normal = new Text(limitedSnippet.substring(last, snippetMatcher.start()));
                normal.setStyle("-fx-fill: #8a8f98;");
                normal.setFont(Font.font("System", FontWeight.NORMAL, 8));
                flow.getChildren().add(normal);
            }
            Text match = new Text(limitedSnippet.substring(snippetMatcher.start(), snippetMatcher.end()));
            match.setStyle("-fx-fill: -color-danger-7;");
            match.setFont(Font.font("System", FontWeight.NORMAL, 8));
            flow.getChildren().add(match);
            last = snippetMatcher.end();
        }
        if (last < limitedSnippet.length()) {
            Text tail = new Text(limitedSnippet.substring(last));
            tail.setStyle("-fx-fill: #8a8f98;");
            tail.setFont(Font.font("System", FontWeight.NORMAL, 8));
            flow.getChildren().add(tail);
        }

        return flow;
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

    public static void performSearch(String searchText) {
        if (searchText.isEmpty()) {
            return;
        }
        try {
            keywordField=searchText;
            LuceneSearcher searcher = new LuceneSearcher(indexDir);
            List<LuceneSearcher.SearchResult> results = searcher.search(keywordField, 50);
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
                resultList.setStyle("-fx-border-color: -color-fg-default;-fx-border-width: 0.5;-fx-background-color: -color-bg-default;-fx-border-radius: 5");
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
        }
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
            keywordField=warmUpKeywordBinding.get();
            LuceneSearcher searcher = new LuceneSearcher(indexDir);
            List<LuceneSearcher.SearchResult> results = searcher.search(keywordField, 50);
        } catch (Exception e) {
            log.debug("Index warm-up failed", e);
        }
    }

    public static List<KnowledgeReference> searchKnowledgeReferences(String keyword, int limit) {
        if (keyword == null || keyword.isBlank() || limit <= 0) {
            return Collections.emptyList();
        }
        try {
            LuceneSearcher searcher = new LuceneSearcher(indexDir);
            List<LuceneSearcher.SearchResult> results = searcher.searchForAi(keyword.trim(), limit);
            List<KnowledgeReference> references = new ArrayList<>();
            for (LuceneSearcher.SearchResult item : results) {
                String path = item.path == null ? "" : item.path.trim();
                if (path.isEmpty()) {
                    continue;
                }
                String title;
                try {
                    title = Paths.get(path).getFileName().toString();
                } catch (Exception ex) {
                    title = path;
                }
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

