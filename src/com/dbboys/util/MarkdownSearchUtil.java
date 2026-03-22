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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class MarkdownSearchNormalizer {
    private static final Pattern ASCII_HAN_BOUNDARY_1 = Pattern.compile("(?<=[A-Za-z0-9_])(?=\\p{IsHan})");
    private static final Pattern ASCII_HAN_BOUNDARY_2 = Pattern.compile("(?<=\\p{IsHan})(?=[A-Za-z0-9_])");
    private static final Pattern ASCII_TOKEN_SPACES = Pattern.compile("(?<=[A-Za-z0-9_])\\s+(?=[A-Za-z0-9_])");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern QUERY_CONCEPT_PATTERN = Pattern.compile("[A-Za-z0-9_]+|\\p{IsHan}+");
    private static final Pattern EXACT_IDENTIFIER_PATTERN =
            Pattern.compile("(?<![A-Za-z0-9_])([A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)+)(?![A-Za-z0-9_])");
    private static final List<String> QUERY_NOISE_PHRASES = List.of(
            "麻烦帮我看一下", "麻烦帮我查一下", "麻烦帮我搜一下", "麻烦帮我找一下",
            "请帮我看一下", "请帮我查一下", "请帮我搜一下", "请帮我找一下",
            "帮我看一下", "帮我查一下", "帮我搜一下", "帮我找一下",
            "给我看一下", "给我查一下", "给我搜一下", "给我找一下",
            "我想问一下", "我想了解一下", "我想咨询一下", "我想请教一下",
            "想问一下", "想了解一下", "想咨询一下", "想请教一下",
            "请问一下", "麻烦看一下", "麻烦查一下", "麻烦搜一下", "麻烦找一下",
            "帮我看下", "帮我查下", "帮我搜下", "帮我找下",
            "给我看下", "给我查下", "给我搜下", "给我找下",
            "我想知道", "我想了解", "我想咨询", "我想请教",
            "想知道", "想了解", "想咨询", "想请教",
            "请帮我", "帮我", "给我", "麻烦", "请问",
            "看一下", "查一下", "搜一下", "找一下", "问一下", "说一下", "讲一下",
            "看下", "查下", "搜下", "找下", "问下", "说下", "讲下",
            "能不能", "可不可以", "有没有", "怎么", "如何", "怎样");
    private static final Set<String> QUERY_STOP_WORDS = Set.of(
            "我", "你", "他", "她", "它", "咱", "咱们",
            "要", "想", "找", "查", "看", "问", "搜", "说", "讲",
            "我要", "我想", "想要", "需要", "帮我", "给我", "麻烦", "劳烦",
            "请问", "请教", "咨询", "了解", "知道", "帮忙", "求助",
            "怎么", "如何", "怎样", "咋", "啥", "什么", "哪个", "哪里",
            "有无", "有没有", "是否", "能否", "可否", "能不能", "可不可以",
            "一下", "一下子", "一下儿", "下",
            "这边", "这里", "这个", "那个", "就是", "有关", "关于");

    private MarkdownSearchNormalizer() {
    }

    static String normalizeQuery(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "";
        }
        String normalized = keyword.trim();
        normalized = stripQueryNoisePhrases(normalized);
        normalized = ASCII_HAN_BOUNDARY_1.matcher(normalized).replaceAll(" ");
        normalized = ASCII_HAN_BOUNDARY_2.matcher(normalized).replaceAll(" ");
        normalized = stripQueryNoisePhrases(normalized);
        normalized = MULTI_SPACE.matcher(normalized).replaceAll(" ").trim();
        normalized = filterStopWordsBeforeSearch(normalized);
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

    static List<String> extractQueryConcepts(String keyword, List<String> analyzedTokens) {
        return extractQueryConcepts(keyword, analyzedTokens, Collections.emptyList());
    }

    static List<String> extractQueryConcepts(String keyword, List<String> analyzedTokens, List<String> exactIdentifiers) {
        LinkedHashSet<String> concepts = new LinkedHashSet<>();
        if (exactIdentifiers != null) {
            for (String identifier : exactIdentifiers) {
                String normalizedIdentifier = normalizeConcept(identifier);
                if (!normalizedIdentifier.isBlank()) {
                    concepts.add(normalizedIdentifier);
                }
            }
        }
        if (analyzedTokens != null) {
            for (String token : analyzedTokens) {
                String normalizedToken = normalizeConcept(token);
                if (!normalizedToken.isBlank()) {
                    concepts.add(normalizedToken);
                }
            }
        }
        if (!concepts.isEmpty()) {
            return new ArrayList<>(concepts);
        }

        String normalized = normalizeQuery(keyword).toLowerCase();
        if (normalized.isBlank()) {
            return Collections.emptyList();
        }
        Matcher matcher = QUERY_CONCEPT_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String concept = normalizeConcept(matcher.group());
            if (!concept.isBlank()) {
                concepts.add(concept);
            }
        }
        return new ArrayList<>(concepts);
    }

    static List<String> extractExactIdentifiers(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        Matcher matcher = EXACT_IDENTIFIER_PATTERN.matcher(text);
        while (matcher.find()) {
            String identifier = normalizeConcept(matcher.group(1));
            if (!identifier.isBlank()) {
                identifiers.add(identifier);
            }
        }
        return new ArrayList<>(identifiers);
    }

    private static String stripQueryNoisePhrases(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text;
        for (String phrase : QUERY_NOISE_PHRASES) {
            normalized = normalized.replace(phrase, " ");
        }
        return normalized;
    }

    private static String normalizeConcept(String token) {
        return token == null ? "" : token.trim().toLowerCase();
    }

    static List<String> pruneShortQueryTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> prunedTokens = new ArrayList<>();
        for (String token : tokens) {
            String normalizedToken = normalizeConcept(token);
            if (normalizedToken.isBlank()) {
                continue;
            }
            if (shouldIgnoreShortQueryToken(normalizedToken)) {
                continue;
            }
            prunedTokens.add(normalizedToken);
        }
        return prunedTokens;
    }

    private static boolean shouldIgnoreShortQueryToken(String token) {
        return isSingleHanToken(token) || isSingleAsciiLetterToken(token);
    }

    private static boolean isSingleHanToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return token.codePointCount(0, token.length()) == 1
                && token.codePoints().allMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    private static boolean isSingleAsciiLetterToken(String token) {
        return token != null && token.length() == 1 && ((token.charAt(0) >= 'a' && token.charAt(0) <= 'z')
                || (token.charAt(0) >= 'A' && token.charAt(0) <= 'Z'));
    }

    private static String filterStopWordsBeforeSearch(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        LinkedHashSet<String> filteredTokens = new LinkedHashSet<>();
        try (Analyzer analyzer = new SmartChineseAnalyzer();
             TokenStream ts = analyzer.tokenStream("content", text)) {
            ts.reset();
            while (ts.incrementToken()) {
                String normalizedToken = normalizeConcept(
                        ts.getAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class).toString());
                if (normalizedToken.isBlank() || QUERY_STOP_WORDS.contains(normalizedToken)) {
                    continue;
                }
                filteredTokens.add(normalizedToken);
            }
            ts.end();
        } catch (Exception ignored) {
            for (String token : text.split("\\s+")) {
                String normalizedToken = normalizeConcept(token);
                if (normalizedToken.isBlank() || QUERY_STOP_WORDS.contains(normalizedToken)) {
                    continue;
                }
                filteredTokens.add(normalizedToken);
            }
        }
        return String.join(" ", pruneShortQueryTokens(new ArrayList<>(filteredTokens)));
    }
}

/**
 * LuceneIndexer - 为 Markdown 文件夹建立 Lucene 索引
 *
 * 说明：
 * - content 字段仅用于分词检索，不再存储整篇正文，避免搜索时反序列化大文档。
 * - content_preview 单独存储较短预览，用于搜索结果摘要与 AI 参考片段。
 * - buildIndex 可选择覆盖(CREATE)或追加(APPEND)模式。
 */
class LuceneIndexer {
    private static final Logger log = LogManager.getLogger(LuceneIndexer.class);
    private static final String DOC_TYPE_FILE = "file";
    private static final String DOC_TYPE_AI_CHUNK = "ai_chunk";
    static final String FIELD_OWNER_PATH_RAW = "owner_path_raw";
    static final String LEGACY_FIELD_PATH = "path";
    static final String LEGACY_FIELD_FILENAME = "filename";
    static final String FIELD_PATH_RAW = "path_raw";
    static final String FIELD_PATH_TEXT = "path_text";
    static final String FIELD_FILENAME_RAW = "filename_raw";
    static final String FIELD_FILENAME_TEXT = "filename_text";
    static final String FIELD_TITLE_TEXT = "title_text";
    static final String FIELD_CONTENT = "content";
    static final String FIELD_CONTENT_PREVIEW = "content_preview";
    static final String FIELD_IDENTIFIER_EXACT = "identifier_exact";
    static final String FIELD_DOC_TYPE = "doc_type";
    static final String FIELD_AI_SOURCE_PATH_RAW = "ai_source_path_raw";
    static final String FIELD_AI_PATH_TEXT = "ai_path_text";
    static final String FIELD_AI_FILENAME_TEXT = "ai_filename_text";
    static final String FIELD_AI_TITLE_TEXT = "ai_title_text";
    static final String FIELD_AI_CONTENT = "ai_content";
    static final String FIELD_AI_CONTENT_PREVIEW = "ai_content_preview";
    static final String FIELD_AI_HEADING_STORED = "ai_heading_stored";
    static final String FIELD_AI_IDENTIFIER_EXACT = "ai_identifier_exact";
    static final String FIELD_MODIFIED = "modified";
    static final String FIELD_MODIFIED_STORED = "modified_stored";
    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("(?m)^#{1,6}\\s+(.+?)\\s*$");
    private static final int MAX_TITLE_PARTS = 8;
    private static final int MAX_TITLE_TEXT_LENGTH = 800;
    private static final int MAX_CONTENT_PREVIEW_CHARS = 20_000;
    private static final int MAX_AI_CHUNK_CHARS = 1_600;
    private static final int MIN_AI_CHUNK_CHARS = 280;

    private record AiChunk(String heading, String text, int order) {}

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
        List<DocumentIndexTextExtractor.PdfPageText> pdfPages = Collections.emptyList();
        String content;
        if (isPdfFile(file)) {
            pdfPages = DocumentIndexTextExtractor.extractPdfPages(file);
            content = DocumentIndexTextExtractor.joinPdfPageTexts(pdfPages);
        } else {
            content = DocumentIndexTextExtractor.extractText(file);
        }
        String contentPreview = buildContentPreview(content);
        long modified = Files.getLastModifiedTime(file).toMillis();
        String rawPath = file.toString();
        String fileName = file.getFileName().toString();
        String fileStem = stripExtension(fileName);
        String titleText = buildTitleText(file, content, fileStem);
        List<Document> docs = new ArrayList<>();
        docs.add(buildFileDocument(rawPath, fileName, fileStem, titleText, content, contentPreview, modified));
        docs.addAll(buildAiChunkDocuments(file, rawPath, fileName, fileStem, content, pdfPages, modified));
        writer.deleteDocuments(new Term(FIELD_OWNER_PATH_RAW, rawPath));
        writer.addDocuments(docs);
    }

    private static Document buildFileDocument(String rawPath,
                                              String fileName,
                                              String fileStem,
                                              String titleText,
                                              String content,
                                              String contentPreview,
                                              long modified) {
        Document doc = new Document();
        doc.add(new StringField(FIELD_OWNER_PATH_RAW, rawPath, Field.Store.NO));
        doc.add(new StringField(FIELD_DOC_TYPE, DOC_TYPE_FILE, Field.Store.NO));
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
        doc.add(new org.apache.lucene.document.TextField(FIELD_CONTENT, content, Field.Store.NO));
        doc.add(new StoredField(FIELD_CONTENT_PREVIEW, contentPreview));
        addExactIdentifierFields(doc, FIELD_IDENTIFIER_EXACT, rawPath);
        addExactIdentifierFields(doc, FIELD_IDENTIFIER_EXACT, fileName);
        addExactIdentifierFields(doc, FIELD_IDENTIFIER_EXACT, titleText);
        addExactIdentifierFields(doc, FIELD_IDENTIFIER_EXACT, content);
        doc.add(new LongPoint(FIELD_MODIFIED, modified));
        doc.add(new StoredField(FIELD_MODIFIED_STORED, modified));
        return doc;
    }

    private static List<Document> buildAiChunkDocuments(Path file,
                                                        String rawPath,
                                                        String fileName,
                                                        String fileStem,
                                                        String content,
                                                        List<DocumentIndexTextExtractor.PdfPageText> pdfPages,
                                                        long modified) {
        List<AiChunk> chunks = buildAiChunks(file, content, fileStem, pdfPages);
        if (chunks.isEmpty()) {
            return Collections.emptyList();
        }
        List<Document> docs = new ArrayList<>(chunks.size());
        String enrichedPathText = MarkdownSearchNormalizer.enrichIndexText(rawPath.replace('\\', ' ').replace('/', ' '));
        String enrichedFileNameText = MarkdownSearchNormalizer.enrichIndexText((fileStem + " " + fileName).trim());
        for (AiChunk chunk : chunks) {
            if (chunk == null || chunk.text() == null || chunk.text().isBlank()) {
                continue;
            }
            Document doc = new Document();
            String heading = chunk.heading() == null ? "" : chunk.heading().trim();
            String headingText = heading.isBlank() ? fileStem : heading;
            doc.add(new StringField(FIELD_OWNER_PATH_RAW, rawPath, Field.Store.NO));
            doc.add(new StringField(FIELD_DOC_TYPE, DOC_TYPE_AI_CHUNK, Field.Store.NO));
            doc.add(new StoredField(FIELD_AI_SOURCE_PATH_RAW, rawPath));
            doc.add(new org.apache.lucene.document.TextField(FIELD_AI_PATH_TEXT, enrichedPathText, Field.Store.NO));
            doc.add(new org.apache.lucene.document.TextField(FIELD_AI_FILENAME_TEXT, enrichedFileNameText, Field.Store.NO));
            doc.add(new org.apache.lucene.document.TextField(
                    FIELD_AI_TITLE_TEXT,
                    MarkdownSearchNormalizer.enrichIndexText((fileStem + "\n" + headingText).trim()),
                    Field.Store.NO));
            doc.add(new org.apache.lucene.document.TextField(FIELD_AI_CONTENT, chunk.text(), Field.Store.NO));
            doc.add(new StoredField(FIELD_AI_CONTENT_PREVIEW, buildChunkPreview(chunk.text())));
            if (!heading.isBlank()) {
                doc.add(new StoredField(FIELD_AI_HEADING_STORED, heading));
            }
            addExactIdentifierFields(doc, FIELD_AI_IDENTIFIER_EXACT, rawPath);
            addExactIdentifierFields(doc, FIELD_AI_IDENTIFIER_EXACT, fileName);
            addExactIdentifierFields(doc, FIELD_AI_IDENTIFIER_EXACT, headingText);
            addExactIdentifierFields(doc, FIELD_AI_IDENTIFIER_EXACT, chunk.text());
            doc.add(new LongPoint(FIELD_MODIFIED, modified));
            doc.add(new StoredField(FIELD_MODIFIED_STORED, modified));
            docs.add(doc);
        }
        return docs;
    }

    private static void addExactIdentifierFields(Document doc, String fieldName, String text) {
        if (doc == null || text == null || text.isBlank()) {
            return;
        }
        for (String identifier : MarkdownSearchNormalizer.extractExactIdentifiers(text)) {
            doc.add(new StringField(fieldName, identifier, Field.Store.NO));
        }
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

    private static String buildContentPreview(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        if (content.length() <= MAX_CONTENT_PREVIEW_CHARS) {
            return content;
        }
        return content.substring(0, MAX_CONTENT_PREVIEW_CHARS);
    }

    private static String buildChunkPreview(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= MAX_AI_CHUNK_CHARS ? text : text.substring(0, MAX_AI_CHUNK_CHARS);
    }

    private static List<AiChunk> buildAiChunks(Path file,
                                               String content,
                                               String fileStem,
                                               List<DocumentIndexTextExtractor.PdfPageText> pdfPages) {
        if (content == null || content.isBlank()) {
            return Collections.emptyList();
        }
        String lowerName = file.getFileName().toString().toLowerCase();
        if (lowerName.endsWith(".pdf") && pdfPages != null && !pdfPages.isEmpty()) {
            List<AiChunk> pdfChunks = buildPdfAiChunks(pdfPages);
            if (!pdfChunks.isEmpty()) {
                return pdfChunks;
            }
        }
        if (lowerName.endsWith(".md") || lowerName.endsWith(".markdown")) {
            List<AiChunk> markdownChunks = buildMarkdownAiChunks(content, fileStem);
            if (!markdownChunks.isEmpty()) {
                return markdownChunks;
            }
        }
        return buildGenericAiChunks(content, fileStem);
    }

    private static List<AiChunk> buildPdfAiChunks(List<DocumentIndexTextExtractor.PdfPageText> pdfPages) {
        if (pdfPages == null || pdfPages.isEmpty()) {
            return Collections.emptyList();
        }
        List<AiChunk> chunks = new ArrayList<>();
        int order = 0;
        for (DocumentIndexTextExtractor.PdfPageText page : pdfPages) {
            if (page == null || page.text() == null || page.text().isBlank()) {
                continue;
            }
            String heading = "第" + page.pageNumber() + "页";
            for (String chunkText : splitIntoAiChunkTexts(page.text())) {
                if (chunkText.isBlank()) {
                    continue;
                }
                chunks.add(new AiChunk(heading, chunkText, order++));
            }
        }
        return chunks;
    }

    private static List<AiChunk> buildMarkdownAiChunks(String content, String fileStem) {
        String source = content == null ? "" : content.replace("\r\n", "\n");
        if (source.isBlank()) {
            return Collections.emptyList();
        }
        List<AiChunk> chunks = new ArrayList<>();
        Matcher matcher = MARKDOWN_HEADING_PATTERN.matcher(source);
        String currentHeading = fileStem == null ? "" : fileStem.trim();
        int sectionStart = 0;
        int order = 0;
        boolean foundHeading = false;
        while (matcher.find()) {
            foundHeading = true;
            String sectionText = source.substring(sectionStart, matcher.start());
            order = appendMarkdownSectionChunks(chunks, currentHeading, sectionText, source, sectionStart, order);
            currentHeading = cleanMarkdownHeading(matcher.group(1));
            sectionStart = matcher.end();
        }
        String tailSection = source.substring(Math.min(sectionStart, source.length()));
        appendMarkdownSectionChunks(chunks, currentHeading, tailSection, source, sectionStart, order);
        if (!foundHeading && chunks.isEmpty()) {
            return buildGenericAiChunks(source, fileStem);
        }
        return chunks;
    }

    private static int appendMarkdownSectionChunks(List<AiChunk> chunks,
                                                   String heading,
                                                   String rawSectionText,
                                                   String source,
                                                   int rawSectionStart,
                                                   int startOrder) {
        if (rawSectionText == null || rawSectionText.isBlank()) {
            return startOrder;
        }
        int trimmedStart = leadingTrimOffset(rawSectionText);
        String sectionText = rawSectionText.trim();
        if (sectionText.isBlank()) {
            return startOrder;
        }
        int sectionBaseLine = lineNumberAtOffset(source, rawSectionStart + trimmedStart);
        int order = startOrder;
        int searchFrom = 0;
        for (String chunkText : splitIntoAiChunkTexts(sectionText)) {
            if (chunkText.isBlank()) {
                continue;
            }
            int chunkOffset = sectionText.indexOf(chunkText, searchFrom);
            if (chunkOffset < 0) {
                chunkOffset = Math.max(0, searchFrom);
            }
            int chunkLine = sectionBaseLine + countNewlines(sectionText, chunkOffset);
            chunks.add(new AiChunk(buildMarkdownChunkHeading(heading, chunkLine), chunkText, order++));
            searchFrom = Math.min(sectionText.length(), chunkOffset + chunkText.length());
        }
        return order;
    }

    private static int appendSectionChunks(List<AiChunk> chunks, String heading, String sectionText, int startOrder) {
        if (sectionText == null || sectionText.isBlank()) {
            return startOrder;
        }
        int order = startOrder;
        for (String chunkText : splitIntoAiChunkTexts(sectionText)) {
            if (chunkText.isBlank()) {
                continue;
            }
            chunks.add(new AiChunk(heading == null ? "" : heading.trim(), chunkText, order++));
        }
        return order;
    }

    private static List<AiChunk> buildGenericAiChunks(String content, String fileStem) {
        if (content == null || content.isBlank()) {
            return Collections.emptyList();
        }
        List<AiChunk> chunks = new ArrayList<>();
        int order = 0;
        for (String chunkText : splitIntoAiChunkTexts(content)) {
            if (chunkText.isBlank()) {
                continue;
            }
            chunks.add(new AiChunk(fileStem == null ? "" : fileStem.trim(), chunkText, order++));
        }
        return chunks;
    }

    private static List<String> splitIntoAiChunkTexts(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isBlank()) {
            return Collections.emptyList();
        }
        String[] paragraphs = normalized.split("\\n\\s*\\n+");
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            String unit = paragraph == null ? "" : paragraph.trim();
            if (unit.isBlank()) {
                continue;
            }
            if (unit.length() > MAX_AI_CHUNK_CHARS) {
                flushChunk(chunks, current);
                appendLongTextChunks(chunks, unit);
                continue;
            }
            if (current.length() > 0 && current.length() + 2 + unit.length() > MAX_AI_CHUNK_CHARS) {
                flushChunk(chunks, current);
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(unit);
        }
        flushChunk(chunks, current);
        if (chunks.isEmpty()) {
            return List.of(normalized);
        }
        return chunks;
    }

    private static void flushChunk(List<String> chunks, StringBuilder current) {
        if (current == null || current.length() == 0) {
            return;
        }
        String chunk = current.toString().trim();
        if (!chunk.isBlank()) {
            chunks.add(chunk);
        }
        current.setLength(0);
    }

    private static void appendLongTextChunks(List<String> chunks, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + MAX_AI_CHUNK_CHARS);
            if (end < text.length()) {
                int adjusted = findChunkBoundary(text, start, end);
                if (adjusted > start + MIN_AI_CHUNK_CHARS) {
                    end = adjusted;
                }
            }
            String chunk = text.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            start = end;
        }
    }

    private static int findChunkBoundary(String text, int start, int end) {
        int[] candidates = new int[]{
                text.lastIndexOf("\n\n", end),
                text.lastIndexOf('\n', end),
                text.lastIndexOf('。', end),
                text.lastIndexOf('；', end),
                text.lastIndexOf('！', end),
                text.lastIndexOf('？', end),
                text.lastIndexOf('.', end),
                text.lastIndexOf(';', end),
                text.lastIndexOf('!', end),
                text.lastIndexOf('?', end),
                text.lastIndexOf(' ', end)
        };
        int minBoundary = Math.min(text.length(), start + MIN_AI_CHUNK_CHARS);
        for (int candidate : candidates) {
            if (candidate >= minBoundary) {
                return candidate + 1;
            }
        }
        return end;
    }

    private static String cleanMarkdownHeading(String heading) {
        return heading == null ? "" : heading.replaceAll("[`*_#>\\[\\]]", "").trim();
    }

    private static String buildMarkdownChunkHeading(String heading, int lineNumber) {
        String safeHeading = heading == null ? "" : heading.trim();
        String lineLabel = lineNumber > 0 ? "第" + lineNumber + "行" : "";
        if (safeHeading.isBlank()) {
            return lineLabel;
        }
        if (lineLabel.isBlank()) {
            return safeHeading;
        }
        return safeHeading + "（" + lineLabel + "）";
    }

    private static int leadingTrimOffset(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int offset = 0;
        while (offset < text.length() && Character.isWhitespace(text.charAt(offset))) {
            offset++;
        }
        return offset;
    }

    private static int lineNumberAtOffset(String text, int offset) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
        int safeOffset = Math.max(0, Math.min(offset, text.length()));
        int line = 1;
        for (int i = 0; i < safeOffset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static int countNewlines(String text, int endExclusive) {
        if (text == null || text.isEmpty() || endExclusive <= 0) {
            return 0;
        }
        int safeEnd = Math.min(endExclusive, text.length());
        int lines = 0;
        for (int i = 0; i < safeEnd; i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static boolean isPdfFile(Path file) {
        if (file == null || file.getFileName() == null) {
            return false;
        }
        return file.getFileName().toString().toLowerCase().endsWith(".pdf");
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
    private enum SearchMode {
        FILE,
        AI_CHUNK
    }

    private static final Logger log = LogManager.getLogger(LuceneSearcher.class);
    private static final int DEFAULT_SNIPPET_CONTEXT_CHARS = 30;
    private static final int DEFAULT_FALLBACK_SNIPPET_CHARS = 120;
    private static final int DEFAULT_BOUNDARY_WINDOW_CHARS = 50;
    private static final int AI_SNIPPET_CONTEXT_CHARS = DEFAULT_SNIPPET_CONTEXT_CHARS * 2;
    private static final int AI_FALLBACK_SNIPPET_CHARS = DEFAULT_FALLBACK_SNIPPET_CHARS * 2;
    private static final int AI_BOUNDARY_WINDOW_CHARS = DEFAULT_BOUNDARY_WINDOW_CHARS * 2;
    /** 生成摘要/加分时只扫描预览片段，避免 PDF/DOCX 等大文档命中后整篇反序列化与全文扫描 */
    private static final int MAX_CONTENT_SCAN_CHARS = 20_000;
    /** 单关键词在正文中最多收集的匹配区间数，避免极端长文重复词导致大量 indexOf */
    private static final int MAX_POSITIONS_PER_TOKEN = 40;
    private static final Set<String> STORED_SEARCH_FIELDS = Set.of(
            LuceneIndexer.FIELD_PATH_RAW,
            LuceneIndexer.LEGACY_FIELD_PATH,
            LuceneIndexer.FIELD_CONTENT_PREVIEW,
            LuceneIndexer.FIELD_CONTENT);
    private static final Set<String> AI_STORED_SEARCH_FIELDS = Set.of(
            LuceneIndexer.FIELD_AI_SOURCE_PATH_RAW,
            LuceneIndexer.FIELD_AI_CONTENT_PREVIEW,
            LuceneIndexer.FIELD_AI_HEADING_STORED);
    private final Path indexDir;
    private final Analyzer analyzer = new SmartChineseAnalyzer();

    public LuceneSearcher(Path indexDir) {
        this.indexDir = indexDir;
    }

    public List<LuceneSearcher.SearchResult> search(String keyword, int limit) throws Exception {
        return search(keyword, limit, DEFAULT_SNIPPET_CONTEXT_CHARS, DEFAULT_FALLBACK_SNIPPET_CHARS,
                DEFAULT_BOUNDARY_WINDOW_CHARS, false, SearchMode.FILE);
    }

    public List<LuceneSearcher.SearchResult> searchForAi(String keyword, int limit) throws Exception {
        List<LuceneSearcher.SearchResult> chunkResults = search(keyword, limit, AI_SNIPPET_CONTEXT_CHARS,
                AI_FALLBACK_SNIPPET_CHARS, AI_BOUNDARY_WINDOW_CHARS, false, SearchMode.AI_CHUNK);
        if (!chunkResults.isEmpty()) {
            return chunkResults;
        }
        return search(keyword, limit, AI_SNIPPET_CONTEXT_CHARS, AI_FALLBACK_SNIPPET_CHARS,
                AI_BOUNDARY_WINDOW_CHARS, false, SearchMode.FILE);
    }

    private List<LuceneSearcher.SearchResult> search(String keyword,
                                                     int limit,
                                                     int contextChars,
                                                     int fallbackSnippetChars,
                                                     int boundaryWindowChars,
                                                     boolean strict,
                                                     SearchMode searchMode) throws Exception {
        String normalizedKeyword = MarkdownSearchNormalizer.normalizeQuery(keyword);
        List<String> exactIdentifiers = MarkdownSearchNormalizer.extractExactIdentifiers(keyword);
        List<String> tokens = analyzeQueryTerms(normalizedKeyword);
        List<String> queryConcepts = MarkdownSearchNormalizer.extractQueryConcepts(normalizedKeyword, tokens, exactIdentifiers);
        Query query = searchMode == SearchMode.AI_CHUNK
                ? buildAiChunkQuery(tokens, exactIdentifiers, strict)
                : buildQuery(keyword, tokens, exactIdentifiers, strict);
        if (query instanceof MatchNoDocsQuery) {
            return Collections.emptyList();
        }
        IndexSearcher searcher = MarkdownSearchUtil.acquireIndexSearcher(indexDir);
        TopDocs topDocs = searcher.search(query, limit);
            List<LuceneSearcher.SearchResult> results = new ArrayList<>();

            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(sd.doc,
                        searchMode == SearchMode.AI_CHUNK ? AI_STORED_SEARCH_FIELDS : STORED_SEARCH_FIELDS);
                String path = searchMode == SearchMode.AI_CHUNK
                        ? doc.get(LuceneIndexer.FIELD_AI_SOURCE_PATH_RAW)
                        : doc.get(LuceneIndexer.FIELD_PATH_RAW);
                if ((path == null || path.isBlank()) && searchMode == SearchMode.FILE) {
                    path = doc.get(LuceneIndexer.LEGACY_FIELD_PATH);
                }
                String content = searchMode == SearchMode.AI_CHUNK
                        ? doc.get(LuceneIndexer.FIELD_AI_CONTENT_PREVIEW)
                        : doc.get(LuceneIndexer.FIELD_CONTENT_PREVIEW);
                String title = searchMode == SearchMode.AI_CHUNK ? doc.get(LuceneIndexer.FIELD_AI_HEADING_STORED) : "";
                if (content == null) content = "";

            // --------- 2) 收集所有匹配区间（去重） ----------
            // 仅对正文前 MAX_CONTENT_SCAN_CHARS 扫描，避免大文档全文 toLowerCase/indexOf 卡顿
            int scanLen = Math.min(content.length(), MAX_CONTENT_SCAN_CHARS);
            String scanSlice = scanLen == content.length() ? content : content.substring(0, scanLen);
            String lowerContent = scanSlice.toLowerCase();
            List<int[]> positions = new ArrayList<>();

            LinkedHashSet<String> snippetTerms = new LinkedHashSet<>(exactIdentifiers);
            snippetTerms.addAll(tokens);
            for (String token : snippetTerms) {
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
                results.add(new LuceneSearcher.SearchResult(path, title, adjustedScore, fallback));
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
                results.add(new LuceneSearcher.SearchResult(path, title, adjustedScore, snippet));
            }

        results.sort(Comparator.comparingDouble((LuceneSearcher.SearchResult item) -> item.score).reversed());
        return results;
    }

    private Query buildQuery(String keyword, List<String> tokens, List<String> exactIdentifiers, boolean strict) {
        BooleanQuery.Builder root = new BooleanQuery.Builder();
        root.setMinimumNumberShouldMatch(1);

        addQuery(root, buildExactNameQuery(keyword), BooleanClause.Occur.SHOULD);
        addQuery(root, buildExactIdentifierQuery(exactIdentifiers), BooleanClause.Occur.SHOULD);
        addQuery(root, buildHanIntentQuery(tokens, strict), BooleanClause.Occur.SHOULD);
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

    private Query buildAiChunkQuery(List<String> tokens, List<String> exactIdentifiers, boolean strict) {
        BooleanQuery.Builder root = new BooleanQuery.Builder();
        root.setMinimumNumberShouldMatch(1);

        addQuery(root, buildExactIdentifierQuery(exactIdentifiers, LuceneIndexer.FIELD_AI_IDENTIFIER_EXACT, 17.0f),
                BooleanClause.Occur.SHOULD);
        addQuery(root, buildAiHanIntentQuery(tokens, strict), BooleanClause.Occur.SHOULD);
        if (tokens != null && tokens.size() > 1) {
            int allTerms = tokens.size();
            addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_AI_FILENAME_TEXT, tokens, allTerms, strict ? 4.0f : 3.6f),
                    BooleanClause.Occur.SHOULD);
            addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_AI_TITLE_TEXT, tokens, allTerms, strict ? 7.0f : 6.0f),
                    BooleanClause.Occur.SHOULD);
            addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_AI_PATH_TEXT, tokens, allTerms, strict ? 3.2f : 2.8f),
                    BooleanClause.Occur.SHOULD);
            addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_AI_CONTENT, tokens, allTerms, strict ? 9.2f : 7.4f),
                    BooleanClause.Occur.SHOULD);
        }
        addQuery(root, buildPhraseQuery(LuceneIndexer.FIELD_AI_TITLE_TEXT, tokens, strict ? 4.4f : 3.8f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildPhraseQuery(LuceneIndexer.FIELD_AI_CONTENT, tokens, strict ? 7.8f : 6.4f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildPhraseQuery(LuceneIndexer.FIELD_AI_PATH_TEXT, tokens, strict ? 2.6f : 2.1f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_AI_TITLE_TEXT, tokens, minimumShouldMatch(tokens.size(), strict, true),
                strict ? 2.4f : 2.0f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_AI_CONTENT, tokens, minimumShouldMatch(tokens.size(), strict, false),
                strict ? 5.2f : 4.2f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_AI_PATH_TEXT, tokens, 1, strict ? 0.6f : 0.5f), BooleanClause.Occur.SHOULD);

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
        return new BoostQuery(query, boost * averageTokenBoost(field, tokens));
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
            builder.add(new BoostQuery(new TermQuery(new Term(field, token)), tokenMatchBoost(field, token)),
                    BooleanClause.Occur.SHOULD);
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

    private Query buildHanIntentQuery(List<String> tokens, boolean strict) {
        return buildHanIntentQuery(tokens,
                LuceneIndexer.FIELD_TITLE_TEXT,
                LuceneIndexer.FIELD_PATH_TEXT,
                LuceneIndexer.FIELD_FILENAME_TEXT,
                strict ? 12.0f : 10.0f,
                strict ? 10.0f : 8.5f,
                strict ? 8.0f : 6.5f,
                strict ? 7.0f : 6.0f,
                strict ? 6.0f : 5.0f,
                strict ? 5.0f : 4.0f);
    }

    private Query buildAiHanIntentQuery(List<String> tokens, boolean strict) {
        return buildHanIntentQuery(tokens,
                LuceneIndexer.FIELD_AI_TITLE_TEXT,
                LuceneIndexer.FIELD_AI_PATH_TEXT,
                LuceneIndexer.FIELD_AI_FILENAME_TEXT,
                strict ? 13.0f : 11.0f,
                strict ? 11.0f : 9.0f,
                strict ? 8.5f : 7.0f,
                strict ? 8.0f : 6.8f,
                strict ? 6.6f : 5.6f,
                strict ? 5.5f : 4.5f);
    }

    private Query buildHanIntentQuery(List<String> tokens,
                                      String titleField,
                                      String pathField,
                                      String filenameField,
                                      float titleTermBoost,
                                      float pathTermBoost,
                                      float filenameTermBoost,
                                      float titlePhraseBoost,
                                      float pathPhraseBoost,
                                      float filenamePhraseBoost) {
        if (tokens == null || tokens.isEmpty()) {
            return null;
        }
        List<String> hanTokens = new ArrayList<>();
        for (String token : tokens) {
            if (containsHanToken(token)) {
                hanTokens.add(token);
            }
        }
        if (hanTokens.isEmpty()) {
            return null;
        }

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        int allHanTerms = hanTokens.size();
        addQuery(builder, buildTermSetQuery(titleField, hanTokens, allHanTerms, titleTermBoost), BooleanClause.Occur.SHOULD);
        addQuery(builder, buildTermSetQuery(pathField, hanTokens, allHanTerms, pathTermBoost), BooleanClause.Occur.SHOULD);
        addQuery(builder, buildTermSetQuery(filenameField, hanTokens, allHanTerms, filenameTermBoost), BooleanClause.Occur.SHOULD);
        addQuery(builder, buildPhraseQuery(titleField, hanTokens, titlePhraseBoost), BooleanClause.Occur.SHOULD);
        addQuery(builder, buildPhraseQuery(pathField, hanTokens, pathPhraseBoost), BooleanClause.Occur.SHOULD);
        addQuery(builder, buildPhraseQuery(filenameField, hanTokens, filenamePhraseBoost), BooleanClause.Occur.SHOULD);
        BooleanQuery built = builder.build();
        return built.clauses().isEmpty() ? null : built;
    }

    private Query buildExactIdentifierQuery(List<String> exactIdentifiers) {
        return buildExactIdentifierQuery(exactIdentifiers, LuceneIndexer.FIELD_IDENTIFIER_EXACT, 15.0f);
    }

    private Query buildExactIdentifierQuery(List<String> exactIdentifiers, String field, float boost) {
        if (exactIdentifiers == null || exactIdentifiers.isEmpty()) {
            return null;
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        int added = 0;
        for (String identifier : exactIdentifiers) {
            if (identifier == null || identifier.isBlank()) {
                continue;
            }
            builder.add(new BoostQuery(new TermQuery(new Term(field, identifier)), boost),
                    BooleanClause.Occur.SHOULD);
            added++;
        }
        if (added == 0) {
            return null;
        }
        if (added > 1) {
            builder.setMinimumNumberShouldMatch(added);
        }
        return builder.build();
    }

    private float averageTokenBoost(String field, List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return 1f;
        }
        float total = 0f;
        int count = 0;
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            total += tokenMatchBoost(field, token);
            count++;
        }
        return count == 0 ? 1f : total / count;
    }

    private float tokenMatchBoost(String field, String token) {
        String normalizedToken = token == null ? "" : token.trim().toLowerCase();
        if (normalizedToken.isBlank()) {
            return 1f;
        }
        if (containsHanToken(normalizedToken)) {
            if (isPathLikeField(field) || isTitleLikeField(field) || isFilenameLikeField(field)) {
                return 2.8f;
            }
            return 2.0f;
        }
        if (normalizedToken.matches("[a-z0-9_]+")) {
            if (isPathLikeField(field)) {
                return 0.35f;
            }
            if (isTitleLikeField(field) || isFilenameLikeField(field)) {
                return 0.65f;
            }
            return 0.85f;
        }
        return 1.1f;
    }

    private boolean isPathLikeField(String field) {
        return LuceneIndexer.FIELD_PATH_TEXT.equals(field) || LuceneIndexer.FIELD_AI_PATH_TEXT.equals(field);
    }

    private boolean isTitleLikeField(String field) {
        return LuceneIndexer.FIELD_TITLE_TEXT.equals(field) || LuceneIndexer.FIELD_AI_TITLE_TEXT.equals(field);
    }

    private boolean isFilenameLikeField(String field) {
        return LuceneIndexer.FIELD_FILENAME_TEXT.equals(field) || LuceneIndexer.FIELD_AI_FILENAME_TEXT.equals(field);
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
        return MarkdownSearchNormalizer.pruneShortQueryTokens(new ArrayList<>(tokens));
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

        float totalConceptWeight = 0f;
        float pathHitsWeight = 0f;
        float contentHitsWeight = 0f;
        float chineseConceptWeight = 0f;
        float chinesePathHitsWeight = 0f;
        float chineseContentHitsWeight = 0f;
        for (String concept : queryConcepts) {
            float conceptWeight = conceptWeight(concept);
            totalConceptWeight += conceptWeight;
            boolean chineseConcept = containsHanToken(concept);
            if (chineseConcept) {
                chineseConceptWeight += conceptWeight;
            }
            if (containsConcept(pathText, pathCompact, concept)) {
                pathHitsWeight += conceptWeight;
                if (chineseConcept) {
                    chinesePathHitsWeight += conceptWeight;
                }
            }
            if (containsConcept(contentText, contentCompact, concept)) {
                contentHitsWeight += conceptWeight;
                if (chineseConcept) {
                    chineseContentHitsWeight += conceptWeight;
                }
            }
        }

        float bonus = 0f;
        float pathHitRatio = totalConceptWeight <= 0f ? 0f : pathHitsWeight / totalConceptWeight;
        float contentHitRatio = totalConceptWeight <= 0f ? 0f : contentHitsWeight / totalConceptWeight;
        if (pathHitRatio >= 0.99f) {
            bonus += 28f;
        } else if (pathHitRatio >= 0.55f) {
            bonus += 12f;
        }
        if (contentHitRatio >= 0.99f) {
            bonus += 16f;
        } else if (contentHitRatio >= 0.55f) {
            bonus += 7f;
        }
        if (chineseConceptWeight > 0f) {
            bonus += 14f * (chinesePathHitsWeight / chineseConceptWeight);
            bonus += 9f * (chineseContentHitsWeight / chineseConceptWeight);
        }
        if (queryConcepts.contains("安装") && (pathText.contains("安装配置") || pathText.contains("安装"))) {
            bonus += 12f;
        }
        return bonus;
    }

    private float conceptWeight(String concept) {
        if (concept == null || concept.isBlank()) {
            return 0f;
        }
        if (containsHanToken(concept)) {
            return 2.4f;
        }
        if (concept.matches("[a-z0-9_]+")) {
            return 0.8f;
        }
        return 1.1f;
    }

    private boolean containsHanToken(String token) {
        return token != null && token.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    private boolean containsConcept(String text, String compactText, String concept) {
        if (concept == null || concept.isBlank()) {
            return false;
        }
        if (text.contains(concept)) {
            return true;
        }
        return concept.matches("[a-z0-9_]+") && compactText.contains(concept);
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
        public final String title;
        public final float score;
        public final String snippet;
        public SearchResult(String path, String title, float score, String snippet) {
            this.path = path;
            this.title = title;
            this.score = score;
            this.snippet = snippet;
        }
        public SearchResult(String path, float score, String snippet) {
            this(path, "", score, snippet);
        }
        public SearchResult(String path, float score) {
            this(path, "", score, "");
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
            flow.getChildren().add(new Text(item.path + "\n"));
            return flow;
        }

        // ====== 分词提取 ======
        LinkedHashSet<String> tokenSet = new LinkedHashSet<>(MarkdownSearchNormalizer.extractExactIdentifiers(normalizedKeyword));
        try (Analyzer analyzer = new SmartChineseAnalyzer();
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
            List<LuceneSearcher.SearchResult> results = searcher.search(searchText, SEARCH_UI_FETCH_LIMIT);
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
            searcher.search(warmUpKeywordBinding.get(), SEARCH_UI_FETCH_LIMIT);
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

