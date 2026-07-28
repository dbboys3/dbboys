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
    private static final int MAX_AI_CHUNKS_PER_DOCUMENT = 2;
    private static final int AI_RESULT_FETCH_MULTIPLIER = 6;
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
    private final Analyzer analyzer = MarkdownSearchAnalyzers.createAnalyzer();

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
        MarkdownSearchNormalizer.ExactMatchTerms exactMatchTerms = MarkdownSearchNormalizer.extractExactMatchTerms(keyword);
        List<String> exactIdentifiers = exactMatchTerms.allIdentifiers();
        List<String> tokens = analyzeQueryTerms(normalizedKeyword);
        List<String> queryConcepts = MarkdownSearchNormalizer.extractQueryConcepts(normalizedKeyword, tokens, exactIdentifiers);
        Query query = searchMode == SearchMode.AI_CHUNK
                ? buildAiChunkQuery(tokens, exactMatchTerms, strict)
                : buildQuery(keyword, tokens, exactMatchTerms, strict);
        if (query instanceof MatchNoDocsQuery) {
            return Collections.emptyList();
        }
        IndexSearcher searcher = MarkdownSearchUtil.acquireIndexSearcher(indexDir);
        int fetchLimit = searchMode == SearchMode.AI_CHUNK
                ? Math.max(limit, limit * AI_RESULT_FETCH_MULTIPLIER)
                : limit;
        TopDocs topDocs = searcher.search(query, fetchLimit);
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
        if (searchMode == SearchMode.AI_CHUNK) {
            return limitAiChunkResultsPerDocument(results, limit);
        }
        return results;
    }

    private List<LuceneSearcher.SearchResult> limitAiChunkResultsPerDocument(List<LuceneSearcher.SearchResult> results, int limit) {
        if (results == null || results.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        Map<String, Integer> countsByPath = new HashMap<>();
        List<LuceneSearcher.SearchResult> limitedResults = new ArrayList<>(Math.min(results.size(), limit));
        for (LuceneSearcher.SearchResult result : results) {
            if (result == null) {
                continue;
            }
            String pathKey = result.path == null ? "" : result.path.trim();
            int count = countsByPath.getOrDefault(pathKey, 0);
            if (count >= MAX_AI_CHUNKS_PER_DOCUMENT) {
                continue;
            }
            limitedResults.add(result);
            countsByPath.put(pathKey, count + 1);
            if (limitedResults.size() >= limit) {
                break;
            }
        }
        return limitedResults;
    }

    private Query buildQuery(String keyword,
                             List<String> tokens,
                             MarkdownSearchNormalizer.ExactMatchTerms exactMatchTerms,
                             boolean strict) {
        BooleanQuery.Builder root = new BooleanQuery.Builder();
        root.setMinimumNumberShouldMatch(1);

        addQuery(root, buildExactNameQuery(keyword), BooleanClause.Occur.SHOULD);
        addQuery(root, buildExactIdentifierQuery(exactMatchTerms.allIdentifiers()), BooleanClause.Occur.SHOULD);
        addQuery(root, buildExactIdentifierQuery(exactMatchTerms.errorCodes(),
                LuceneIndexer.FIELD_ERROR_CODE_EXACT, strict ? 24.0f : 21.0f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildExactIdentifierQuery(exactMatchTerms.commands(),
                LuceneIndexer.FIELD_COMMAND_EXACT, strict ? 19.0f : 17.0f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildExactIdentifierQuery(exactMatchTerms.environmentVariables(),
                LuceneIndexer.FIELD_ENV_VAR_EXACT, strict ? 21.0f : 18.0f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildExactIdentifierQuery(exactMatchTerms.systemObjects(),
                LuceneIndexer.FIELD_SYSTEM_OBJECT_EXACT, strict ? 20.0f : 18.0f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildHanIntentQuery(tokens, strict), BooleanClause.Occur.SHOULD);
        if (tokens != null && tokens.size() > 1) {
            int allTerms = tokens.size();
            addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_FILENAME_TEXT, tokens, allTerms, strict ? 4.2f : 3.8f),
                    BooleanClause.Occur.SHOULD);
            addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_PATH_TEXT, tokens, allTerms, strict ? 3.6f : 3.0f),
                    BooleanClause.Occur.SHOULD);
            addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_CONTENT, tokens, allTerms, strict ? 8.0f : 6.5f),
                    BooleanClause.Occur.SHOULD);
        }
        addQuery(root, buildPhraseQuery(LuceneIndexer.FIELD_FILENAME_TEXT, tokens, strict ? 2.4f : 2.0f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildPhraseQuery(LuceneIndexer.FIELD_CONTENT, tokens, strict ? 7.0f : 5.5f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_FILENAME_TEXT, tokens, minimumShouldMatch(tokens.size(), strict, true),
                strict ? 1.8f : 1.5f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_CONTENT, tokens, minimumShouldMatch(tokens.size(), strict, false),
                strict ? 4.5f : 3.5f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_PATH_TEXT, tokens, 1, strict ? 0.3f : 0.5f), BooleanClause.Occur.SHOULD);

        BooleanQuery built = root.build();
        if (built.clauses().isEmpty()) {
            return new MatchNoDocsQuery();
        }
        return built;
    }

    private Query buildAiChunkQuery(List<String> tokens,
                                    MarkdownSearchNormalizer.ExactMatchTerms exactMatchTerms,
                                    boolean strict) {
        BooleanQuery.Builder root = new BooleanQuery.Builder();
        root.setMinimumNumberShouldMatch(1);

        addQuery(root, buildExactIdentifierQuery(exactMatchTerms.allIdentifiers(),
                LuceneIndexer.FIELD_AI_IDENTIFIER_EXACT, 17.0f),
                BooleanClause.Occur.SHOULD);
        addQuery(root, buildExactIdentifierQuery(exactMatchTerms.errorCodes(),
                LuceneIndexer.FIELD_AI_ERROR_CODE_EXACT, strict ? 28.0f : 25.0f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildExactIdentifierQuery(exactMatchTerms.commands(),
                LuceneIndexer.FIELD_AI_COMMAND_EXACT, strict ? 22.0f : 19.0f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildExactIdentifierQuery(exactMatchTerms.environmentVariables(),
                LuceneIndexer.FIELD_AI_ENV_VAR_EXACT, strict ? 24.0f : 20.0f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildExactIdentifierQuery(exactMatchTerms.systemObjects(),
                LuceneIndexer.FIELD_AI_SYSTEM_OBJECT_EXACT, strict ? 24.0f : 21.0f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildAiHanIntentQuery(tokens, strict), BooleanClause.Occur.SHOULD);
        if (tokens != null && tokens.size() > 1) {
            int allTerms = tokens.size();
            addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_AI_FILENAME_TEXT, tokens, allTerms, strict ? 4.0f : 3.6f),
                    BooleanClause.Occur.SHOULD);
            addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_AI_PATH_TEXT, tokens, allTerms, strict ? 3.2f : 2.8f),
                    BooleanClause.Occur.SHOULD);
            addQuery(root, buildTermSetQuery(LuceneIndexer.FIELD_AI_CONTENT, tokens, allTerms, strict ? 9.2f : 7.4f),
                    BooleanClause.Occur.SHOULD);
        }
        addQuery(root, buildPhraseQuery(LuceneIndexer.FIELD_AI_CONTENT, tokens, strict ? 7.8f : 6.4f), BooleanClause.Occur.SHOULD);
        addQuery(root, buildPhraseQuery(LuceneIndexer.FIELD_AI_PATH_TEXT, tokens, strict ? 2.6f : 2.1f), BooleanClause.Occur.SHOULD);
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
                LuceneIndexer.FIELD_PATH_TEXT,
                LuceneIndexer.FIELD_FILENAME_TEXT,
                strict ? 10.0f : 8.5f,
                strict ? 8.0f : 6.5f,
                strict ? 6.0f : 5.0f,
                strict ? 5.0f : 4.0f);
    }

    private Query buildAiHanIntentQuery(List<String> tokens, boolean strict) {
        return buildHanIntentQuery(tokens,
                LuceneIndexer.FIELD_AI_PATH_TEXT,
                LuceneIndexer.FIELD_AI_FILENAME_TEXT,
                strict ? 11.0f : 9.0f,
                strict ? 8.5f : 7.0f,
                strict ? 6.6f : 5.6f,
                strict ? 5.5f : 4.5f);
    }

    private Query buildHanIntentQuery(List<String> tokens,
                                      String pathField,
                                      String filenameField,
                                      float pathTermBoost,
                                      float filenameTermBoost,
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
        addQuery(builder, buildTermSetQuery(pathField, hanTokens, allHanTerms, pathTermBoost), BooleanClause.Occur.SHOULD);
        addQuery(builder, buildTermSetQuery(filenameField, hanTokens, allHanTerms, filenameTermBoost), BooleanClause.Occur.SHOULD);
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
            if (isPathLikeField(field) || isFilenameLikeField(field)) {
                return 2.8f;
            }
            return 2.0f;
        }
        if (normalizedToken.matches("[a-z0-9_]+")) {
            if (isPathLikeField(field)) {
                return 0.35f;
            }
            if (isFilenameLikeField(field)) {
                return 0.65f;
            }
            return 0.85f;
        }
        return 1.1f;
    }

    private boolean isPathLikeField(String field) {
        return LuceneIndexer.FIELD_PATH_TEXT.equals(field) || LuceneIndexer.FIELD_AI_PATH_TEXT.equals(field);
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

    private float computeHeuristicBonus(String path,
                                        String content,
                                        List<String> queryConcepts) {
        if (queryConcepts == null || queryConcepts.isEmpty()) {
            return 0f;
        }
        String pathText = MarkdownSearchNormalizer.extractDirectoryPath(path).toLowerCase();
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
