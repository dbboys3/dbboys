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
    static final String FIELD_CONTENT = "content";
    static final String FIELD_CONTENT_PREVIEW = "content_preview";
    static final String FIELD_IDENTIFIER_EXACT = "identifier_exact";
    static final String FIELD_ERROR_CODE_EXACT = "error_code_exact";
    static final String FIELD_COMMAND_EXACT = "command_exact";
    static final String FIELD_ENV_VAR_EXACT = "env_var_exact";
    static final String FIELD_SYSTEM_OBJECT_EXACT = "system_object_exact";
    static final String FIELD_DOC_TYPE = "doc_type";
    static final String FIELD_AI_SOURCE_PATH_RAW = "ai_source_path_raw";
    static final String FIELD_AI_PATH_TEXT = "ai_path_text";
    static final String FIELD_AI_FILENAME_TEXT = "ai_filename_text";
    static final String FIELD_AI_CONTENT = "ai_content";
    static final String FIELD_AI_CONTENT_PREVIEW = "ai_content_preview";
    static final String FIELD_AI_HEADING_STORED = "ai_heading_stored";
    static final String FIELD_AI_IDENTIFIER_EXACT = "ai_identifier_exact";
    static final String FIELD_AI_ERROR_CODE_EXACT = "ai_error_code_exact";
    static final String FIELD_AI_COMMAND_EXACT = "ai_command_exact";
    static final String FIELD_AI_ENV_VAR_EXACT = "ai_env_var_exact";
    static final String FIELD_AI_SYSTEM_OBJECT_EXACT = "ai_system_object_exact";
    static final String FIELD_MODIFIED = "modified";
    static final String FIELD_MODIFIED_STORED = "modified_stored";
    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("(?m)^#{1,6}\\s+(.+?)\\s*$");
    private static final int MAX_TITLE_PARTS = 8;
    private static final int MAX_TITLE_TEXT_LENGTH = 800;
    private static final int MAX_CONTENT_PREVIEW_CHARS = 20_000;
    private static final int MAX_AI_CHUNK_CHARS = 1_600;
    private static final int MIN_AI_CHUNK_CHARS = 280;
    private static final int MAX_INDEX_WORKERS = 6;

    private record AiChunk(String heading, String text, int order) {}

    private final Path indexDir;
    private final Analyzer analyzer;
    private static final StringBinding FOLDER_NOT_EXISTS_BINDING =
            I18n.bind("markdown.search.error.folder_not_exists", "markdownFolder 不存在: %s");
    private static final StringBinding INDEX_FILE_ERROR_BINDING =
            I18n.bind("markdown.search.error.index_file", "索引文件出错: %s -> %s");

    public LuceneIndexer(Path indexDir) {
        this.indexDir = indexDir;
        this.analyzer = MarkdownSearchAnalyzers.createAnalyzer();
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

        List<Path> filesToIndex;
        try (Stream<Path> stream = Files.walk(markdownFolder)) {
            filesToIndex = stream.filter(Files::isRegularFile)
                    .filter(DocumentIndexTextExtractor::isSupported)
                    .toList();
        }

        Directory dir = FSDirectory.open(indexDir);
        IndexWriterConfig.OpenMode mode = overwrite ? IndexWriterConfig.OpenMode.CREATE : IndexWriterConfig.OpenMode.CREATE_OR_APPEND;
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(mode);

        try (IndexWriter writer = new IndexWriter(dir, config)) {
            indexFilesInParallel(writer, filesToIndex, progress);
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
        List<DocumentIndexTextExtractor.StructuredTextSection> aiSections = Collections.emptyList();
        String content;
        if (isAiPagedChunkFile(file)) {
            aiSections = DocumentIndexTextExtractor.extractAiSections(file);
            content = aiSections.isEmpty()
                    ? DocumentIndexTextExtractor.extractText(file)
                    : DocumentIndexTextExtractor.joinStructuredSectionTexts(aiSections);
        } else {
            content = DocumentIndexTextExtractor.extractText(file);
        }
        String contentPreview = buildContentPreview(content);
        long modified = Files.getLastModifiedTime(file).toMillis();
        String rawPath = file.toString();
        String fileName = file.getFileName().toString();
        String fileStem = stripExtension(fileName);
        List<Document> docs = new ArrayList<>();
        docs.add(buildFileDocument(rawPath, fileName, fileStem, content, contentPreview, modified));
        docs.addAll(buildAiChunkDocuments(file, rawPath, fileName, fileStem, content, aiSections, modified));
        writer.deleteDocuments(new Term(FIELD_OWNER_PATH_RAW, rawPath));
        writer.addDocuments(docs);
    }

    private void indexFilesInParallel(IndexWriter writer, List<Path> filesToIndex, Consumer<String> progress) throws IOException {
        if (writer == null || filesToIndex == null || filesToIndex.isEmpty()) {
            return;
        }

        int workerCount = Math.max(1, Math.min(MAX_INDEX_WORKERS,
                Math.min(Runtime.getRuntime().availableProcessors(), filesToIndex.size())));
        if (workerCount == 1) {
            for (Path file : filesToIndex) {
                indexSingleFile(writer, file, progress);
            }
            return;
        }

        ExecutorService executor = Executors.newFixedThreadPool(workerCount, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            thread.setName("markdown-index-" + thread.getId());
            return thread;
        });
        List<Future<?>> futures = new ArrayList<>(filesToIndex.size());
        try {
            for (Path file : filesToIndex) {
                futures.add(executor.submit(() -> {
                    indexSingleFile(writer, file, progress);
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Markdown index build interrupted", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException ioException) {
                        throw ioException;
                    }
                    throw new IOException("Markdown index build failed", cause == null ? e : cause);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void indexSingleFile(IndexWriter writer, Path file, Consumer<String> progress) {
        if (file == null) {
            return;
        }
        try {
            if (progress != null) {
                progress.accept(file.toString());
            }
            indexFile(writer, file);
        } catch (Exception ex) {
            log.warn("Indexing file failed: {}", file, ex);
            System.err.println(INDEX_FILE_ERROR_BINDING.get().formatted(file, ex.getMessage()));
        }
    }

    private static Document buildFileDocument(String rawPath,
                                              String fileName,
                                              String fileStem,
                                              String content,
                                              String contentPreview,
                                              long modified) {
        Document doc = new Document();
        String directoryPathSearchText = MarkdownSearchNormalizer.directoryPathSearchText(rawPath);
        String directoryPath = MarkdownSearchNormalizer.extractDirectoryPath(rawPath);
        doc.add(new StringField(FIELD_OWNER_PATH_RAW, rawPath, Field.Store.NO));
        doc.add(new StringField(FIELD_DOC_TYPE, DOC_TYPE_FILE, Field.Store.NO));
        doc.add(new StringField(FIELD_PATH_RAW, rawPath, Field.Store.YES));
        if (!directoryPathSearchText.isBlank()) {
            doc.add(new org.apache.lucene.document.TextField(
                    FIELD_PATH_TEXT,
                    directoryPathSearchText,
                    Field.Store.NO));
        }
        doc.add(new StringField(FIELD_FILENAME_RAW, fileName, Field.Store.YES));
        doc.add(new org.apache.lucene.document.TextField(
                FIELD_FILENAME_TEXT,
                MarkdownSearchNormalizer.enrichIndexText((fileStem + " " + fileName).trim()),
                Field.Store.NO));
        doc.add(new org.apache.lucene.document.TextField(FIELD_CONTENT, content, Field.Store.NO));
        doc.add(new StoredField(FIELD_CONTENT_PREVIEW, contentPreview));
        addDomainExactFields(doc, FIELD_IDENTIFIER_EXACT, FIELD_ERROR_CODE_EXACT, FIELD_COMMAND_EXACT,
                FIELD_ENV_VAR_EXACT, FIELD_SYSTEM_OBJECT_EXACT, directoryPath);
        addDomainExactFields(doc, FIELD_IDENTIFIER_EXACT, FIELD_ERROR_CODE_EXACT, FIELD_COMMAND_EXACT,
                FIELD_ENV_VAR_EXACT, FIELD_SYSTEM_OBJECT_EXACT, fileName);
        addDomainExactFields(doc, FIELD_IDENTIFIER_EXACT, FIELD_ERROR_CODE_EXACT, FIELD_COMMAND_EXACT,
                FIELD_ENV_VAR_EXACT, FIELD_SYSTEM_OBJECT_EXACT, content);
        doc.add(new LongPoint(FIELD_MODIFIED, modified));
        doc.add(new StoredField(FIELD_MODIFIED_STORED, modified));
        return doc;
    }

    private static List<Document> buildAiChunkDocuments(Path file,
                                                        String rawPath,
                                                        String fileName,
                                                        String fileStem,
                                                        String content,
                                                        List<DocumentIndexTextExtractor.StructuredTextSection> aiSections,
                                                        long modified) {
        List<AiChunk> chunks = buildAiChunks(file, content, fileStem, aiSections);
        if (chunks.isEmpty()) {
            return Collections.emptyList();
        }
        List<Document> docs = new ArrayList<>(chunks.size());
        String enrichedPathText = MarkdownSearchNormalizer.directoryPathSearchText(rawPath);
        String directoryPath = MarkdownSearchNormalizer.extractDirectoryPath(rawPath);
        String enrichedFileNameText = MarkdownSearchNormalizer.enrichIndexText((fileStem + " " + fileName).trim());
        for (AiChunk chunk : chunks) {
            if (chunk == null || chunk.text() == null || chunk.text().isBlank()) {
                continue;
            }
            Document doc = new Document();
            String heading = chunk.heading() == null ? "" : chunk.heading().trim();
            doc.add(new StringField(FIELD_OWNER_PATH_RAW, rawPath, Field.Store.NO));
            doc.add(new StringField(FIELD_DOC_TYPE, DOC_TYPE_AI_CHUNK, Field.Store.NO));
            doc.add(new StoredField(FIELD_AI_SOURCE_PATH_RAW, rawPath));
            if (!enrichedPathText.isBlank()) {
                doc.add(new org.apache.lucene.document.TextField(FIELD_AI_PATH_TEXT, enrichedPathText, Field.Store.NO));
            }
            doc.add(new org.apache.lucene.document.TextField(FIELD_AI_FILENAME_TEXT, enrichedFileNameText, Field.Store.NO));
            doc.add(new org.apache.lucene.document.TextField(FIELD_AI_CONTENT, chunk.text(), Field.Store.NO));
            doc.add(new StoredField(FIELD_AI_CONTENT_PREVIEW, buildChunkPreview(chunk.text())));
            if (!heading.isBlank()) {
                doc.add(new StoredField(FIELD_AI_HEADING_STORED, heading));
            }
            addDomainExactFields(doc, FIELD_AI_IDENTIFIER_EXACT, FIELD_AI_ERROR_CODE_EXACT, FIELD_AI_COMMAND_EXACT,
                    FIELD_AI_ENV_VAR_EXACT, FIELD_AI_SYSTEM_OBJECT_EXACT, directoryPath);
            addDomainExactFields(doc, FIELD_AI_IDENTIFIER_EXACT, FIELD_AI_ERROR_CODE_EXACT, FIELD_AI_COMMAND_EXACT,
                    FIELD_AI_ENV_VAR_EXACT, FIELD_AI_SYSTEM_OBJECT_EXACT, fileName);
            addDomainExactFields(doc, FIELD_AI_IDENTIFIER_EXACT, FIELD_AI_ERROR_CODE_EXACT, FIELD_AI_COMMAND_EXACT,
                    FIELD_AI_ENV_VAR_EXACT, FIELD_AI_SYSTEM_OBJECT_EXACT, chunk.text());
            doc.add(new LongPoint(FIELD_MODIFIED, modified));
            doc.add(new StoredField(FIELD_MODIFIED_STORED, modified));
            docs.add(doc);
        }
        return docs;
    }

    private static void addDomainExactFields(Document doc,
                                             String identifierField,
                                             String errorCodeField,
                                             String commandField,
                                             String envVarField,
                                             String systemObjectField,
                                             String text) {
        if (doc == null || text == null || text.isBlank()) {
            return;
        }
        MarkdownSearchNormalizer.ExactMatchTerms exactTerms = MarkdownSearchNormalizer.extractExactMatchTerms(text);
        addExactStringFields(doc, identifierField, exactTerms.allIdentifiers());
        addExactStringFields(doc, errorCodeField, exactTerms.errorCodes());
        addExactStringFields(doc, commandField, exactTerms.commands());
        addExactStringFields(doc, envVarField, exactTerms.environmentVariables());
        addExactStringFields(doc, systemObjectField, exactTerms.systemObjects());
    }

    private static void addExactStringFields(Document doc, String fieldName, List<String> values) {
        if (doc == null || fieldName == null || fieldName.isBlank() || values == null || values.isEmpty()) {
            return;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            doc.add(new StringField(fieldName, value, Field.Store.NO));
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
                                               List<DocumentIndexTextExtractor.StructuredTextSection> aiSections) {
        if (content == null || content.isBlank()) {
            return Collections.emptyList();
        }
        if (aiSections != null && !aiSections.isEmpty()) {
            List<AiChunk> pagedChunks = buildPagedAiChunks(aiSections);
            if (!pagedChunks.isEmpty()) {
                return pagedChunks;
            }
        }
        String lowerName = file.getFileName().toString().toLowerCase();
        if (lowerName.endsWith(".md") || lowerName.endsWith(".markdown")) {
            List<AiChunk> markdownChunks = buildMarkdownAiChunks(content, fileStem);
            if (!markdownChunks.isEmpty()) {
                return markdownChunks;
            }
        }
        return buildGenericAiChunks(content, fileStem);
    }

    private static List<AiChunk> buildPagedAiChunks(List<DocumentIndexTextExtractor.StructuredTextSection> aiSections) {
        if (aiSections == null || aiSections.isEmpty()) {
            return Collections.emptyList();
        }
        List<AiChunk> chunks = new ArrayList<>();
        int order = 0;
        for (DocumentIndexTextExtractor.StructuredTextSection section : aiSections) {
            if (section == null || section.text() == null || section.text().isBlank()) {
                continue;
            }
            String heading = section.heading() == null ? "" : section.heading().trim();
            for (String chunkText : splitIntoAiChunkTexts(section.text())) {
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

    private static boolean isAiPagedChunkFile(Path file) {
        if (file == null || file.getFileName() == null) {
            return false;
        }
        String lowerName = file.getFileName().toString().toLowerCase();
        return lowerName.endsWith(".pdf")
                || lowerName.endsWith(".doc")
                || lowerName.endsWith(".docx")
                || lowerName.endsWith(".docm")
                || lowerName.endsWith(".ppt")
                || lowerName.endsWith(".pptx")
                || lowerName.endsWith(".pptm");
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
