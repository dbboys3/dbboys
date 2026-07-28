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

final class MarkdownSearchNormalizer {
    private static final Pattern ASCII_HAN_BOUNDARY_1 = Pattern.compile("(?<=[A-Za-z0-9_])(?=\\p{IsHan})");
    private static final Pattern ASCII_HAN_BOUNDARY_2 = Pattern.compile("(?<=\\p{IsHan})(?=[A-Za-z0-9_])");
    private static final Pattern ASCII_TOKEN_SPACES = Pattern.compile("(?<=[A-Za-z0-9_])\\s+(?=[A-Za-z0-9_])");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern QUERY_CONCEPT_PATTERN = Pattern.compile("[A-Za-z0-9_]+|\\p{IsHan}+");
    private static final Pattern EXACT_IDENTIFIER_PATTERN =
            Pattern.compile("(?<![A-Za-z0-9_])([A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)+)(?![A-Za-z0-9_])");
    private static final Pattern ASCII_EXACT_TOKEN_PATTERN =
            Pattern.compile("(?<![A-Za-z0-9_])([A-Za-z][A-Za-z0-9_]*(?:-[A-Za-z0-9_]+)*)(?![A-Za-z0-9_])");
    private static final Pattern ERROR_CODE_PATTERN =
            Pattern.compile("(?<![A-Za-z0-9])(-\\d{3,6}|\\d{5,6})(?![A-Za-z0-9])");
    private static final Pattern QUALIFIED_SYSTEM_OBJECT_PATTERN =
            Pattern.compile("(?i)(?<![A-Za-z0-9_])([A-Za-z0-9_]+@[A-Za-z0-9_]+:[A-Za-z0-9_]+|[A-Za-z0-9_]+:[A-Za-z0-9_]+)(?![A-Za-z0-9_])");
    private static final Pattern SYSTEM_OBJECT_PATTERN =
            Pattern.compile("(?i)(?<![A-Za-z0-9_])(sys[a-z0-9_]{2,})(?![A-Za-z0-9_])");
    private static final Set<String> KNOWN_COMMANDS = Set.of(
            "onstat", "onmode", "onbar", "ontape", "oninit", "oncheck", "onspaces", "ondblog",
            "onparams", "onload", "onunload", "dbaccess", "dbexport", "dbimport", "dbschema",
            "tbmode", "xctl");
    private static final Map<String, String> COMMAND_ALIASES = Map.of(
            "db-access", "dbaccess"
    );
    private static final Set<String> KNOWN_ENV_VARS = Set.of(
            "gbasedbtserver", "gbasedbtdir", "gbasedbtsqlhosts", "db_locale", "client_locale",
            "server_locale", "dblang", "lang", "onconfig", "dbservername", "dbserveraliases",
            "ifx_lock_mode_wait", "ifx_isolation_level", "ifx_trimtrailingspaces",
            "allow_newline", "auto_reprepare", "ltapedev", "msgpath");
    private static final Set<String> KNOWN_SYSTEM_OBJECTS = Set.of(
            "sysmaster", "systables", "sysindexes", "syscolumns", "sysdatabases", "sysdbslocale",
            "syssynonyms", "sysnewdepend", "sysuser", "sysusers", "sysadmin", "dual");
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

    record ExactMatchTerms(List<String> allIdentifiers,
                           List<String> errorCodes,
                           List<String> commands,
                           List<String> environmentVariables,
                           List<String> systemObjects) {
        static ExactMatchTerms empty() {
            return new ExactMatchTerms(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList());
        }
    }

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

    static String extractDirectoryPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "";
        }
        int slash = Math.max(rawPath.lastIndexOf('/'), rawPath.lastIndexOf('\\'));
        if (slash <= 0) {
            return "";
        }
        return rawPath.substring(0, slash).trim();
    }

    static String directoryPathSearchText(String rawPath) {
        String directoryPath = extractDirectoryPath(rawPath);
        if (directoryPath.isBlank()) {
            return "";
        }
        return enrichIndexText(directoryPath.replace('\\', ' ').replace('/', ' '));
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
        return extractExactMatchTerms(text).allIdentifiers();
    }

    static ExactMatchTerms extractExactMatchTerms(String text) {
        if (text == null || text.isBlank()) {
            return ExactMatchTerms.empty();
        }
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        LinkedHashSet<String> errorCodes = new LinkedHashSet<>();
        LinkedHashSet<String> commands = new LinkedHashSet<>();
        LinkedHashSet<String> environmentVariables = new LinkedHashSet<>();
        LinkedHashSet<String> systemObjects = new LinkedHashSet<>();

        collectPatternMatches(ERROR_CODE_PATTERN, text, errorCodes);
        identifiers.addAll(errorCodes);

        Matcher matcher = EXACT_IDENTIFIER_PATTERN.matcher(text);
        while (matcher.find()) {
            addNormalizedToken(identifiers, matcher.group(1));
        }

        collectPatternMatches(QUALIFIED_SYSTEM_OBJECT_PATTERN, text, systemObjects);
        collectPatternMatches(SYSTEM_OBJECT_PATTERN, text, systemObjects);
        identifiers.addAll(systemObjects);

        Matcher asciiTokenMatcher = ASCII_EXACT_TOKEN_PATTERN.matcher(text);
        while (asciiTokenMatcher.find()) {
            String rawToken = asciiTokenMatcher.group(1);
            String normalizedToken = normalizeConcept(rawToken);
            if (normalizedToken.isBlank()) {
                continue;
            }
            if (rawToken.indexOf('_') >= 0 || looksLikeStrongHyphenatedIdentifier(rawToken)) {
                identifiers.add(normalizedToken);
            }

            String canonicalCommand = canonicalCommand(normalizedToken);
            if (canonicalCommand != null) {
                commands.add(canonicalCommand);
                identifiers.add(canonicalCommand);
                identifiers.add(normalizedToken);
            }

            if (isEnvironmentVariableCandidate(rawToken, normalizedToken)) {
                environmentVariables.add(normalizedToken);
                identifiers.add(normalizedToken);
            }

            if (isBareSystemObjectCandidate(normalizedToken)) {
                systemObjects.add(normalizedToken);
                identifiers.add(normalizedToken);
            }
        }

        return new ExactMatchTerms(
                new ArrayList<>(identifiers),
                new ArrayList<>(errorCodes),
                new ArrayList<>(commands),
                new ArrayList<>(environmentVariables),
                new ArrayList<>(systemObjects));
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

    private static void collectPatternMatches(Pattern pattern, String text, LinkedHashSet<String> target) {
        if (pattern == null || text == null || text.isBlank() || target == null) {
            return;
        }
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            addNormalizedToken(target, matcher.group(1));
        }
    }

    private static void addNormalizedToken(LinkedHashSet<String> target, String token) {
        String normalizedToken = normalizeConcept(token);
        if (!normalizedToken.isBlank()) {
            target.add(normalizedToken);
        }
    }

    private static boolean looksLikeStrongHyphenatedIdentifier(String token) {
        if (token == null || token.isBlank() || token.indexOf('-') < 0) {
            return false;
        }
        String normalized = normalizeConcept(token);
        return normalized.startsWith("db")
                || normalized.startsWith("gbase")
                || normalized.endsWith("admin")
                || token.chars().anyMatch(Character::isUpperCase)
                || token.chars().anyMatch(Character::isDigit);
    }

    private static String canonicalCommand(String normalizedToken) {
        if (normalizedToken == null || normalizedToken.isBlank()) {
            return null;
        }
        String alias = COMMAND_ALIASES.get(normalizedToken);
        if (alias != null) {
            return alias;
        }
        return KNOWN_COMMANDS.contains(normalizedToken) ? normalizedToken : null;
    }

    private static boolean isEnvironmentVariableCandidate(String rawToken, String normalizedToken) {
        if (rawToken == null || rawToken.isBlank() || normalizedToken == null || normalizedToken.isBlank()) {
            return false;
        }
        if (rawToken.indexOf('_') >= 0) {
            return rawToken.equals(rawToken.toUpperCase());
        }
        return KNOWN_ENV_VARS.contains(normalizedToken);
    }

    private static boolean isBareSystemObjectCandidate(String normalizedToken) {
        return normalizedToken != null
                && ((normalizedToken.startsWith("sys") && normalizedToken.length() > 4)
                || KNOWN_SYSTEM_OBJECTS.contains(normalizedToken));
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
        try (Analyzer analyzer = MarkdownSearchAnalyzers.createAnalyzer();
             TokenStream ts = analyzer.tokenStream("content", text)) {
            ts.reset();
            while (ts.incrementToken()) {
                String normalizedToken = normalizeConcept(
                        ts.getAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class).toString());
                if (normalizedToken.isBlank()) {
                    continue;
                }
                filteredTokens.add(normalizedToken);
            }
            ts.end();
        } catch (Exception ignored) {
            for (String token : text.split("\\s+")) {
                String normalizedToken = normalizeConcept(token);
                if (normalizedToken.isBlank()) {
                    continue;
                }
                filteredTokens.add(normalizedToken);
            }
        }
        return String.join(" ", pruneShortQueryTokens(new ArrayList<>(filteredTokens)));
    }
}
