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

import java.io.File;
import java.io.IOException;
import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class MarkdownSearchNormalizer {
    private static final Pattern ASCII_HAN_BOUNDARY_1 = Pattern.compile("(?<=[A-Za-z0-9])(?=\\p{IsHan})");
    private static final Pattern ASCII_HAN_BOUNDARY_2 = Pattern.compile("(?<=\\p{IsHan})(?=[A-Za-z0-9])");
    private static final Pattern ASCII_TOKEN_SPACES = Pattern.compile("(?<=[A-Za-z0-9])\\s+(?=[A-Za-z0-9])");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern QUERY_CONCEPT_PATTERN = Pattern.compile("[A-Za-z0-9]+|\\p{IsHan}+");

    private MarkdownSearchNormalizer() {
    }

    static String normalizeQuery(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "";
        }
        String normalized = keyword.trim();
        normalized = ASCII_HAN_BOUNDARY_1.matcher(normalized).replaceAll(" ");
        normalized = ASCII_HAN_BOUNDARY_2.matcher(normalized).replaceAll(" ");
        normalized = MULTI_SPACE.matcher(normalized).replaceAll(" ").trim();
        return normalized;
    }

    static String enrichIndexText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = MULTI_SPACE.matcher(text.trim()).replaceAll(" ");
        String compactAscii = ASCII_TOKEN_SPACES.matcher(normalized).replaceAll("");
        if (compactAscii.equals(normalized)) {
            return normalized;
        }
        return normalized + "\n" + compactAscii;
    }

    static String compactAsciiText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = MULTI_SPACE.matcher(text.trim()).replaceAll(" ");
        return ASCII_TOKEN_SPACES.matcher(normalized).replaceAll("");
    }

    static List<String> extractQueryConcepts(String keyword) {
        String normalized = normalizeQuery(keyword).toLowerCase();
        if (normalized.isBlank()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> concepts = new LinkedHashSet<>();
        Matcher matcher = QUERY_CONCEPT_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String concept = matcher.group();
            if (concept == null || concept.isBlank()) {
                continue;
            }
            if (concept.matches("[a-z0-9]+") && concept.length() <= 1) {
                continue;
            }
            concepts.add(concept);
        }
        return new ArrayList<>(concepts);
    }
}

/**
 * LuceneIndexer - 为 Markdown 文件夹建立 Lucene 索引
 *
 * 说明：
 * - content 字段被存储（Store.YES），便于在检索后读取原文或做高亮。
 * - buildIndex 可选择覆盖(CREATE)或追加(APPEND)模式。
 */
class LuceneIndexer {
    private static final Logger log = LogManager.getLogger(LuceneIndexer.class);
    static final String LEGACY_FIELD_PATH = "path";
    static final String LEGACY_FIELD_FILENAME = "filename";
    static final String FIELD_PATH_RAW = "path_raw";
    static final String FIELD_PATH_TEXT = "path_text";
    static final String FIELD_FILENAME_RAW = "filename_raw";
    static final String FIELD_FILENAME_TEXT = "filename_text";
    static final String FIELD_TITLE_TEXT = "title_text";
    static final String FIELD_CONTENT = "content";
    static final String FIELD_MODIFIED = "modified";
    static final String FIELD_MODIFIED_STORED = "modified_stored";
    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("(?m)^#{1,6}\\s+(.+?)\\s*$");
    private static final int MAX_TITLE_PARTS = 8;
    private static final int MAX_TITLE_TEXT_LENGTH = 800;

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
        String rawPath = file.toString();
        String fileName = file.getFileName().toString();
        String fileStem = stripExtension(fileName);
        String titleText = buildTitleText(file, content, fileStem);

        Document doc = new Document();

        doc.add(new StringField(FIELD_PATH_RAW, rawPath, Field.Store.YES));
        doc.add(new org.apache.lucene.document.TextField(
                FIELD_PATH_TEXT,
                MarkdownSearchNormalizer.enrichIndexText(rawPath.replace('\\', ' ').replace('/', ' ')),
                Field.Store.NO));
        doc.add(new StringField(FIELD_FILENAME_RAW, fileName, Field.Store.YES));
        doc.add(new org.apache.lucene.document.TextField(
                FIELD_FILENAME_TEXT,
                MarkdownSearchNormalizer.enrichIndexText((fileStem + " " + fileName).trim()),
                Field.Store.NO));
        if (!titleText.isBlank()) {
            doc.add(new org.apache.lucene.document.TextField(
                    FIELD_TITLE_TEXT,
                    MarkdownSearchNormalizer.enrichIndexText(titleText),
                    Field.Store.NO));
        }

        // content: 存储并分词（便于高亮与显示）
        doc.add(new org.apache.lucene.document.TextField(FIELD_CONTENT, content, Field.Store.YES));

        // modified 时间：用于排序或权重
        doc.add(new LongPoint(FIELD_MODIFIED, modified));
        // 同时存储 modified 以便检索后取得值
        doc.add(new StoredField(FIELD_MODIFIED_STORED, modified));

        // 增量更新：删除已存在的同 path 文档后添加新文档
        // 这保证了 path 的唯一性（避免重复）
        writer.updateDocument(new Term(FIELD_PATH_RAW, rawPath), doc);
    }

    private static String buildTitleText(Path file, String content, String fileStem) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        if (fileStem != null && !fileStem.isBlank()) {
            parts.add(fileStem.trim());
        }
        String lowerName = file.getFileName().toString().toLowerCase();
        if ((lowerName.endsWith(".md") || lowerName.endsWith(".markdown")) && content != null && !content.isBlank()) {
            Matcher matcher = MARKDOWN_HEADING_PATTERN.matcher(content);
            while (matcher.find() && parts.size() < MAX_TITLE_PARTS) {
                String heading = matcher.group(1) == null ? "" : matcher.group(1).replaceAll("[`*_#>\\[\\]]", "").trim();
                if (!heading.isBlank()) {
                    parts.add(heading);
                }
            }
        }
        String joined = String.join("\n", parts);
        if (joined.length() <= MAX_TITLE_TEXT_LENGTH) {
            return joined;
        }
        return joined.substring(0, MAX_TITLE_TEXT_LENGTH);
    }

    private static String stripExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return fileName;
        }
        return fileName.substring(0, dot);
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
    /** 生成摘要/加分时只扫描正文前若干字符，避免 PDF/DOCX 等大文档全文 toLowerCase + 多次 indexOf 极慢 */
    private static final int MAX_CONTENT_SCAN_CHARS = 200_000;
    /** 单关键词在正文中最多收集的匹配区间数，避免极端长文重复词导致大量 indexOf */
    private static final int MAX_POSITIONS_PER_TOKEN = 40;
    private final Path indexDir;
    private final Analyzer analyzer = new SmartChineseAnalyzer();

    public LuceneSearcher(Path indexDir) {
        this.indexDir = indexDir;
    }

    public List<LuceneSearcher.SearchResult> search(String keyword, int limit) throws Exception {
        return search(keyword, limit, DEFAULT_SNIPPET_CONTEXT_CHARS, DEFAULT_FALLBACK_SNIPPET_CHARS,
                DEFAULT_BOUNDARY_WINDOW_CHARS, false);
    }

    private List<LuceneSearcher.SearchResult> search(String keyword,
                                                     int limit,
                                                     int contextChars,
                                                     int fallbackSnippetChars,
                                                     int boundaryWindowChars,
                                                     boolean strict) throws Exception {
        String normalizedKeyword = MarkdownSearchNormalizer.normalizeQuery(keyword);
        List<String> tokens = analyzeQueryTerms(normalizedKeyword);
        List<String> queryConcepts = MarkdownSearchNormalizer.extractQueryConcepts(normalizedKeyword);
        Query query = buildQuery(normalizedKeyword, tokens, strict);
        if (query instanceof MatchNoDocsQuery) {
            return Collections.emptyList();
        }
        IndexSearcher searcher = MarkdownSearchUtil.acquireIndexSearcher(indexDir);
        TopDocs topDocs = searcher.search(query, limit);
            List<LuceneSearcher.SearchResult> results = new ArrayList<>();

            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(sd.doc);
                String path = doc.get(LuceneIndexer.FIELD_PATH_RAW);
                if (path == null || path.isBlank()) {
                    path = doc.get(LuceneIndexer.LEGACY_FIELD_PATH);
                }
                String content = doc.get(LuceneIndexer.FIELD_CONTENT);
                if (content == null) content = "";

            // --------- 2) 收集所有匹配区间（去重） ----------
            // 仅对正文前 MAX_CONTENT_SCAN_CHARS 扫描，避免大文档全文 toLowerCase/indexOf 卡顿
            int scanLen = Math.min(content.length(), MAX_CONTENT_SCAN_CHARS);
            String scanSlice = scanLen == content.length() ? content : content.substring(0, scanLen);
            String lowerContent = scanSlice.toLowerCase();
            List<int[]> positions = new ArrayList<>();

            for (String token : tokens) {
                if (token == null || token.isBlank()) continue;
                String lowerToken = token.toLowerCase();
                int idx = 0;
                int found = 0;
                while (found < MAX_POSITIONS_PER_TOKEN && (idx = lowerContent.indexOf(lowerToken, idx)) >= 0) {
                    int start = Math.max(0, idx - contextChars);
                    int end = Math.min(content.length(), idx + lowerToken.length() + contextChars);
                    positions.add(new int[]{start, end});
                    found++;
                    idx = idx + Math.max(1, lowerToken.length()); // 避免无限循环
                }
            }

            // 如果没有找到任何位置，则给出文首一小段作为 snippet
                if (positions.isEmpty()) {
                    String fallback = content.length() > fallbackSnippetChars
                            ? content.substring(0, fallbackSnippetChars) + " ... "
                            : content;
                float adjustedScore = sd.score + computeHeuristicBonus(path, content, queryConcepts);
                results.add(new LuceneSearcher.SearchResult(path, adjustedScore, fallback));
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

                float adjustedScore = sd.score + computeHeuristicBonus(path, content, queryConcepts);
                results.add(new LuceneSearcher.SearchResult(path, adjustedScore, snippet));
            }

        results.sort(Comparator.comparingDouble((LuceneSearcher.SearchResult item) -> item.score).reversed());
        return results;
    }

    private Query buildQuery(String keyword, List<String> tokens, boolean strict) {
        BooleanQuery.Builder root = new BooleanQuery.Builder();
        root.setMinimumNumberShouldMatch(1);

        addQuery(root, buildExactNameQuery(keyword), BooleanClause.Occur.SHOULD);
        if (tokens != null && tokens.size() > 1) {
            int allTerms = tokens.size();
            addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_FILENAME_TEXT, tokens, allTerms, strict ? 4.2f : 3.8f),
                    BooleanClause.Occur.SHOULD);
            addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_TITLE_TEXT, tokens, allTerms, strict ? 4.8f : 4.2f),
                    BooleanClause.Occur.SHOULD);
            addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_PATH_TEXT, tokens, allTerms, strict ? 3.6f : 3.0f),
                    BooleanClause.Occur.SHOULD);
            addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_CONTENT, tokens, allTerms, strict ? 8.0f : 6.5f),
                    BooleanClause.Occur.SHOULD);
        }
        addQuery(root, buildPhraseQuery(LuceneIndexer.FIELD_FILENAME_TEXT, tokens, strict ? 2.4f : 2.0f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildPhraseQuery(LuceneIndexer.FIELD_TITLE_TEXT, tokens, strict ? 2.8f : 2.4f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildPhraseQuery(LuceneIndexer.FIELD_CONTENT, tokens, strict ? 7.0f : 5.5f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_FILENAME_TEXT, tokens, minimumShouldMatch(tokens.size(), strict, true),
                strict ? 1.8f : 1.5f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_TITLE_TEXT, tokens, minimumShouldMatch(tokens.size(), strict, true),
                strict ? 2.0f : 1.6f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_CONTENT, tokens, minimumShouldMatch(tokens.size(), strict, false),
                strict ? 4.5f : 3.5f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_PATH_TEXT, tokens, 1, strict ? 0.3f : 0.5f), BooleanClause.Occur.SHOULD);

        BooleanQuery built = root.build();
        if (built.clauses().isEmpty()) {
            return new MatchNoDocsQuery();
        }
        return built;
    }

    private Query buildExactNameQuery(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String normalized = keyword.trim();
        if (!looksLikeExactNameQuery(normalized)) {
            return null;
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(new BoostQuery(new TermQuery(new Term(LuceneIndexer.FIELD_FILENAME_RAW, normalized)), 6.0f),
                BooleanClause.Occur.SHOULD);
        builder.add(new BoostQuery(new TermQuery(new Term(LuceneIndexer.LEGACY_FIELD_FILENAME, normalized)), 5.5f),
                BooleanClause.Occur.SHOULD);
        builder.add(new BoostQuery(new TermQuery(new Term(LuceneIndexer.FIELD_PATH_RAW, normalized)), 4.0f),
                BooleanClause.Occur.SHOULD);
        BooleanQuery built = builder.build();
        return built.clauses().isEmpty() ? null : built;
    }

    private Query buildPhraseQuery(String field, List<String> tokens, float boost) {
        if (tokens == null || tokens.isEmpty()) {
            return null;
        }
        Query query;
        if (tokens.size() == 1) {
            query = new TermQuery(new Term(field, tokens.get(0)));
        } else {
            PhraseQuery.Builder builder = new PhraseQuery.Builder();
            for (int i = 0; i < tokens.size(); i++) {
                builder.add(new Term(field, tokens.get(i)), i);
            }
            query = builder.build();
        }
        return new BoostQuery(query, boost);
    }

    private Query buildTermSetQuery(String field, List<String> tokens, int minShouldMatch, float boost) {
        if (tokens == null || tokens.isEmpty()) {
            return null;
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        int added = 0;
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            builder.add(new TermQuery(new Term(field, token)), BooleanClause.Occur.SHOULD);
            added++;
        }
        if (added == 0) {
            return null;
        }
        if (added > 1) {
            builder.setMinimumNumberShouldMatch(Math.min(Math.max(minShouldMatch, 1), added));
        }
        return new BoostQuery(builder.build(), boost);
    }

    private List<String> analyzeQueryTerms(String keyword) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (keyword == null || keyword.isBlank()) {
            return new ArrayList<>();
        }
        try (TokenStream ts = analyzer.tokenStream(LuceneIndexer.FIELD_CONTENT, keyword)) {
            ts.reset();
            while (ts.incrementToken()) {
                String term = ts.getAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class).toString();
                if (term != null && !term.isBlank()) {
                    tokens.add(term);
                }
            }
            ts.end();
        } catch (Exception e) {
            log.debug("Tokenizer failed, falling back to raw keyword", e);
        }
        if (tokens.isEmpty()) {
            tokens.add(keyword.trim().toLowerCase());
        }
        return new ArrayList<>(tokens);
    }

    private int minimumShouldMatch(int tokenCount, boolean strict, boolean shortField) {
        if (tokenCount <= 1) {
            return tokenCount;
        }
        if (shortField) {
            return strict ? Math.min(tokenCount, Math.max(1, tokenCount - 1)) : Math.min(tokenCount, Math.max(1, tokenCount - 2));
        }
        if (strict) {
            return Math.min(tokenCount, Math.max(2, (int) Math.ceil(tokenCount * 0.7)));
        }
        return Math.min(tokenCount, Math.max(1, (int) Math.ceil(tokenCount * 0.5)));
    }

    private float computeHeuristicBonus(String path, String content, List<String> queryConcepts) {
        if (queryConcepts == null || queryConcepts.isEmpty()) {
            return 0f;
        }
        String pathText = path == null ? "" : path.toLowerCase();
        String pathCompact = MarkdownSearchNormalizer.compactAsciiText(pathText);
        String raw = content == null ? "" : content;
        if (raw.length() > MAX_CONTENT_SCAN_CHARS) {
            raw = raw.substring(0, MAX_CONTENT_SCAN_CHARS);
        }
        String contentText = raw.toLowerCase();
        String contentCompact = MarkdownSearchNormalizer.compactAsciiText(contentText);

        int pathHits = 0;
        int contentHits = 0;
        for (String concept : queryConcepts) {
            if (containsConcept(pathText, pathCompact, concept)) {
                pathHits++;
            }
            if (containsConcept(contentText, contentCompact, concept)) {
                contentHits++;
            }
        }

        float bonus = 0f;
        if (pathHits == queryConcepts.size()) {
            bonus += 26f;
        } else if (pathHits >= Math.max(1, queryConcepts.size() - 1)) {
            bonus += 10f;
        }
        if (contentHits == queryConcepts.size()) {
            bonus += 14f;
        } else if (contentHits >= Math.max(1, queryConcepts.size() - 1)) {
            bonus += 6f;
        }
        if (queryConcepts.contains("安装") && (pathText.contains("安装配置") || pathText.contains("安装"))) {
            bonus += 12f;
        }
        return bonus;
    }

    private boolean containsConcept(String text, String compactText, String concept) {
        if (concept == null || concept.isBlank()) {
            return false;
        }
        if (text.contains(concept)) {
            return true;
        }
        return concept.matches("[a-z0-9]+") && compactText.contains(concept);
    }

    private boolean looksLikeExactNameQuery(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return false;
        }
        String normalized = keyword.trim();
        return normalized.contains("\\")
                || normalized.contains("/")
                || normalized.contains(".")
                || normalized.contains("_")
                || normalized.contains("-");
    }

    private void addQuery(BooleanQuery.Builder builder, Query query, BooleanClause.Occur occur) {
        if (builder == null || query == null) {
            return;
        }
        builder.add(query, occur);
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

    /**
     * 与 {@link #performSearch(String)} 从 Lucene 拉取的候选条数一致。
     * 检索在候选集上会做启发式重排；若只拉前几条再重排，前几位会与侧边栏（50 条候选）不一致。
     */
    public static final int SEARCH_UI_FETCH_LIMIT = 50;

    /** 发给大模型的知识库片段条数（与侧边栏「搜索」同一套 {@link LuceneSearcher#search(String, int)} 排序与摘要） */
    public static final int AI_PROMPT_SNIPPET_COUNT = 5;

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

        String normalizedKeyword = MarkdownSearchNormalizer.normalizeQuery(keyword);
        if (normalizedKeyword.isBlank()) {
            flow.getChildren().add(new Text(item.path + "\n"));
            return flow;
        }

        // ====== 分词提取 ======
        List<String> tokens = new ArrayList<>();
        try (Analyzer analyzer = new SmartChineseAnalyzer();
             TokenStream ts = analyzer.tokenStream("content", normalizedKeyword)) {
            ts.reset();
            while (ts.incrementToken()) {
                String term = ts.getAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class).toString();
                if (!term.isBlank()) tokens.add(term);
            }
            ts.end();
        } catch (Exception e) {
            log.debug("Tokenizer failed, falling back to raw keyword", e);
        }

        if (tokens.isEmpty()) tokens.add(normalizedKeyword);

        // ====== 高亮路径 ======
        String fullPath = item.path;
        String tokenPattern = tokens.stream()
                .map(Pattern::quote)
                .reduce((a, b) -> a + "|" + b)
                .orElse(Pattern.quote(normalizedKeyword));
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

    public static void performSearch(String searchText) {
        if (searchText.isEmpty()) {
            return;
        }
        keywordField = searchText;
        // 在后台线程执行 Lucene 检索与摘要生成，避免阻塞 JavaFX 线程导致界面卡顿
        AppExecutor.runAsync(() -> {
        try {
            LuceneSearcher searcher = new LuceneSearcher(indexDir);
            List<LuceneSearcher.SearchResult> results = searcher.search(keywordField, SEARCH_UI_FETCH_LIMIT);
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
            keywordField=warmUpKeywordBinding.get();
            LuceneSearcher searcher = new LuceneSearcher(indexDir);
            searcher.search(keywordField, SEARCH_UI_FETCH_LIMIT);
        } catch (Exception e) {
            log.debug("Index warm-up failed", e);
        }
    }

    /**
     * 为 AI 加载知识库：与 {@link #performSearch(String)} 使用相同的 {@link LuceneSearcher#search(String, int)}，
     * 即与侧边栏「搜索」相同的排序与摘要片段；在重排后取前 {@link #AI_PROMPT_SNIPPET_COUNT} 条发给模型，
     * 回复中可再只展示前 {@link #AI_UI_REFERENCE_LINK_COUNT} 条文档链接。
     */
    public static List<KnowledgeReference> loadAiKnowledgeFromSearch(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }
        try {
            LuceneSearcher searcher = new LuceneSearcher(indexDir);
            int fetchSize = Math.max(AI_PROMPT_SNIPPET_COUNT, SEARCH_UI_FETCH_LIMIT);
            List<LuceneSearcher.SearchResult> results = searcher.search(keyword.trim(), fetchSize);
            List<KnowledgeReference> references = new ArrayList<>();
            for (LuceneSearcher.SearchResult item : results) {
                if (references.size() >= AI_PROMPT_SNIPPET_COUNT) {
                    break;
                }
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

