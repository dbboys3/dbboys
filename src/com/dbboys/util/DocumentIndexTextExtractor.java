package com.dbboys.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class DocumentIndexTextExtractor {
    private static final Logger log = LogManager.getLogger(DocumentIndexTextExtractor.class);
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("md", "markdown", "pdf", "docx", "docm", "doc");
    private static final Pattern PDF_STREAM_PATTERN =
            Pattern.compile("(?s)(<<.*?>>)\\s*stream\\r?\\n(.*?)\\r?\\nendstream");
    private static final Pattern PDF_TEXT_BLOCK_PATTERN = Pattern.compile("(?s)BT(.*?)ET");
    private static final int MAX_EXTRACTED_LENGTH = 1_000_000;
    private static final Path PDFBOX_FONT_CACHE_DIR = Path.of("data", "pdfbox-font-cache");

    private DocumentIndexTextExtractor() {
    }

    static boolean isSupported(Path file) {
        return SUPPORTED_EXTENSIONS.contains(getExtension(file));
    }

    static String extractText(Path file) throws IOException {
        String extension = getExtension(file);
        String text = switch (extension) {
            case "md", "markdown" -> Files.readString(file);
            case "docx", "docm" -> extractDocxText(file);
            case "doc" -> extractLegacyWordText(file);
            case "pdf" -> extractPdfText(file);
            default -> "";
        };
        return limitLength(normalizeExtractedText(text));
    }

    private static String extractDocxText(Path file) throws IOException {
        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(file));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        } catch (Exception ex) {
            log.warn("POI DOCX extraction failed for {}, falling back to XML parsing", file, ex);
        }
        return extractDocxXmlFallback(file);
    }

    private static String extractDocxXmlFallback(Path file) throws IOException {
        List<ZipEntry> xmlEntries = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(file.toFile(), ZipFile.OPEN_READ, StandardCharsets.UTF_8)) {
            Collections.list(zipFile.entries()).stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> {
                        String name = normalizeZipEntryName(entry.getName());
                        return name.startsWith("word/") && name.endsWith(".xml");
                    })
                    .sorted(Comparator
                            .comparingInt((ZipEntry entry) -> docxEntryOrder(normalizeZipEntryName(entry.getName())))
                            .thenComparing(entry -> normalizeZipEntryName(entry.getName())))
                    .forEach(xmlEntries::add);

            StringBuilder text = new StringBuilder();
            for (ZipEntry entry : xmlEntries) {
                String xml = decodeText(zipFile.getInputStream(entry).readAllBytes());
                appendText(text, extractDocxXmlText(xml));
            }
            return text.toString();
        }
    }

    private static int docxEntryOrder(String name) {
        if ("word/document.xml".equals(name)) {
            return 0;
        }
        if (name.startsWith("word/header")) {
            return 1;
        }
        if (name.startsWith("word/footer")) {
            return 2;
        }
        if (name.startsWith("word/footnotes")) {
            return 3;
        }
        if (name.startsWith("word/endnotes")) {
            return 4;
        }
        if (name.startsWith("word/comments")) {
            return 5;
        }
        return 10;
    }

    private static String normalizeZipEntryName(String name) {
        return name == null ? "" : name.replace('\\', '/');
    }

    private static String extractDocxXmlText(String xml) {
        if (xml == null || xml.isBlank()) {
            return "";
        }
        String text = xml
                .replaceAll("(?i)<w:tab[^>]*/>", "\t")
                .replaceAll("(?i)<w:(br|cr)[^>]*/>", "\n")
                .replaceAll("(?i)</w:p>", "\n")
                .replaceAll("(?i)</w:tr>", "\n")
                .replaceAll("(?i)</w:tc>", "\t")
                .replaceAll("(?s)<[^>]+>", "");
        return xmlUnescape(text);
    }

    private static String extractLegacyWordText(Path file) throws IOException {
        try {
            Class<?> documentClass = Class.forName("org.apache.poi.hwpf.HWPFDocument");
            Class<?> extractorClass = Class.forName("org.apache.poi.hwpf.extractor.WordExtractor");
            try (InputStream input = Files.newInputStream(file);
                 AutoCloseable document = (AutoCloseable) documentClass.getConstructor(InputStream.class).newInstance(input);
                 AutoCloseable extractor = (AutoCloseable) extractorClass.getConstructor(documentClass).newInstance(document)) {
                return (String) extractorClass.getMethod("getText").invoke(extractor);
            }
        } catch (ClassNotFoundException ex) {
            log.info("POI HWPF support is unavailable, falling back to binary extraction for {}", file);
        } catch (Exception ex) {
            log.warn("POI DOC extraction failed for {}, falling back to binary extraction", file, ex);
        }
        byte[] bytes = Files.readAllBytes(file);
        return extractBinaryTextRuns(bytes, 4, 6);
    }

    private static String extractPdfText(Path file) throws IOException {
        initializePdfBoxFontCache();
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setSuppressDuplicateOverlappingText(true);
            stripper.setAddMoreFormatting(true);
            return stripper.getText(document);
        }
    }

    private static void initializePdfBoxFontCache() throws IOException {
        if (System.getProperty("pdfbox.fontcache") != null && !System.getProperty("pdfbox.fontcache").isBlank()) {
            return;
        }
        Files.createDirectories(PDFBOX_FONT_CACHE_DIR);
        System.setProperty("pdfbox.fontcache", PDFBOX_FONT_CACHE_DIR.toAbsolutePath().toString());
    }

    private static String decodePdfStream(String dictionary, byte[] streamBytes) {
        if (dictionary != null
                && dictionary.contains("FlateDecode")
                && !dictionary.contains("ASCII85Decode")
                && !dictionary.contains("LZWDecode")) {
            try {
                return inflatePdfStream(streamBytes);
            } catch (Exception ex) {
                log.debug("Failed to inflate PDF stream", ex);
            }
        }
        return new String(streamBytes, StandardCharsets.ISO_8859_1);
    }

    private static String inflatePdfStream(byte[] streamBytes) throws IOException {
        IOException firstException = null;
        for (boolean nowrap : new boolean[]{false, true}) {
            try (ByteArrayInputStream input = new ByteArrayInputStream(trimPdfStreamBoundary(streamBytes));
                 InflaterInputStream inflaterStream =
                         new InflaterInputStream(input, new Inflater(nowrap));
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                inflaterStream.transferTo(output);
                return new String(output.toByteArray(), StandardCharsets.ISO_8859_1);
            } catch (IOException ex) {
                if (firstException == null) {
                    firstException = ex;
                }
            }
        }
        throw firstException == null ? new IOException("Unable to inflate PDF stream") : firstException;
    }

    private static byte[] trimPdfStreamBoundary(byte[] data) {
        int start = 0;
        int end = data.length;
        while (start < end && (data[start] == '\r' || data[start] == '\n')) {
            start++;
        }
        while (end > start && (data[end - 1] == '\r' || data[end - 1] == '\n')) {
            end--;
        }
        byte[] trimmed = new byte[end - start];
        System.arraycopy(data, start, trimmed, 0, trimmed.length);
        return trimmed;
    }

    private static String extractPdfTextObjects(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        Matcher blockMatcher = PDF_TEXT_BLOCK_PATTERN.matcher(text);
        while (blockMatcher.find()) {
            appendText(result, extractPdfStrings(blockMatcher.group(1)));
        }
        if (result.isEmpty()) {
            appendText(result, extractPdfStrings(text));
        }
        return result.toString();
    }

    private static String extractPdfStrings(String source) {
        StringBuilder result = new StringBuilder();
        int index = 0;
        while (index < source.length()) {
            char ch = source.charAt(index);
            if (ch == '(') {
                ParsedPdfToken token = readPdfLiteralString(source, index + 1);
                appendPdfToken(result, decodePdfLiteralString(token.text()));
                index = token.nextIndex();
                continue;
            }
            if (ch == '<' && (index + 1 >= source.length() || source.charAt(index + 1) != '<')) {
                int end = source.indexOf('>', index + 1);
                if (end > index) {
                    appendPdfToken(result, decodePdfHexString(source.substring(index + 1, end)));
                    index = end + 1;
                    continue;
                }
            }
            if (ch == '\'' || ch == '"') {
                appendPdfLineBreak(result);
            }
            index++;
        }
        return result.toString();
    }

    private static ParsedPdfToken readPdfLiteralString(String source, int startIndex) {
        StringBuilder token = new StringBuilder();
        int depth = 1;
        int index = startIndex;
        while (index < source.length()) {
            char ch = source.charAt(index);
            if (ch == '\\') {
                if (index + 1 < source.length()) {
                    token.append(ch).append(source.charAt(index + 1));
                    index += 2;
                    continue;
                }
                token.append(ch);
                index++;
                continue;
            }
            if (ch == '(') {
                depth++;
                token.append(ch);
                index++;
                continue;
            }
            if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return new ParsedPdfToken(token.toString(), index + 1);
                }
                token.append(ch);
                index++;
                continue;
            }
            token.append(ch);
            index++;
        }
        return new ParsedPdfToken(token.toString(), source.length());
    }

    private static String decodePdfLiteralString(String token) {
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            if (ch != '\\') {
                decoded.append(ch);
                continue;
            }
            if (i + 1 >= token.length()) {
                break;
            }
            char next = token.charAt(++i);
            switch (next) {
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case 'b' -> decoded.append('\b');
                case 'f' -> decoded.append('\f');
                case '\\', '(', ')' -> decoded.append(next);
                case '\r' -> {
                    if (i + 1 < token.length() && token.charAt(i + 1) == '\n') {
                        i++;
                    }
                }
                case '\n' -> {
                }
                default -> {
                    if (next >= '0' && next <= '7') {
                        int value = next - '0';
                        int count = 1;
                        while (count < 3 && i + 1 < token.length()) {
                            char oct = token.charAt(i + 1);
                            if (oct < '0' || oct > '7') {
                                break;
                            }
                            i++;
                            value = (value * 8) + (oct - '0');
                            count++;
                        }
                        decoded.append((char) value);
                    } else {
                        decoded.append(next);
                    }
                }
            }
        }
        return decoded.toString();
    }

    private static String decodePdfHexString(String token) {
        String hex = token.replaceAll("\\s+", "");
        if (hex.isEmpty()) {
            return "";
        }
        if (!hex.matches("[0-9A-Fa-f]+")) {
            return "";
        }
        if ((hex.length() & 1) == 1) {
            hex = hex + "0";
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            bytes[i] = (byte) value;
        }
        if (startsWith(bytes, (byte) 0xFE, (byte) 0xFF) || looksLikeUtf16Be(bytes)) {
            return new String(bytes, StandardCharsets.UTF_16BE);
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static boolean looksLikeUtf16Be(byte[] bytes) {
        if (bytes.length < 4 || (bytes.length & 1) == 1) {
            return false;
        }
        int zeroCount = 0;
        int inspect = Math.min(bytes.length, 24);
        for (int i = 0; i < inspect; i += 2) {
            if (bytes[i] == 0) {
                zeroCount++;
            }
        }
        return zeroCount >= Math.max(1, inspect / 6);
    }

    private static boolean startsWith(byte[] bytes, byte first, byte second) {
        return bytes.length >= 2 && bytes[0] == first && bytes[1] == second;
    }

    private static void appendPdfToken(StringBuilder result, String token) {
        String cleaned = normalizeWhitespace(token);
        if (!isUsefulText(cleaned)) {
            return;
        }
        if (!result.isEmpty() && result.charAt(result.length() - 1) != '\n') {
            result.append(' ');
        }
        result.append(cleaned);
    }

    private static void appendPdfLineBreak(StringBuilder result) {
        if (!result.isEmpty() && result.charAt(result.length() - 1) != '\n') {
            result.append('\n');
        }
    }

    private static String extractBinaryTextRuns(byte[] bytes, int minUnicodeChars, int minAsciiChars) {
        LinkedHashSet<String> runs = new LinkedHashSet<>();
        collectUtf16LeRuns(bytes, 0, minUnicodeChars, runs);
        collectUtf16LeRuns(bytes, 1, minUnicodeChars, runs);
        collectAsciiRuns(bytes, minAsciiChars, runs);
        StringBuilder text = new StringBuilder();
        for (String run : runs) {
            appendText(text, run);
        }
        return text.toString();
    }

    private static void collectUtf16LeRuns(byte[] bytes, int offset, int minChars, Set<String> runs) {
        StringBuilder current = new StringBuilder();
        int usefulChars = 0;
        for (int i = offset; i + 1 < bytes.length; i += 2) {
            char ch = (char) (((bytes[i + 1] & 0xFF) << 8) | (bytes[i] & 0xFF));
            if (isLikelyTextChar(ch)) {
                current.append(ch);
                if (isUsefulChar(ch)) {
                    usefulChars++;
                }
            } else {
                addTextRun(runs, current, usefulChars, minChars);
                current.setLength(0);
                usefulChars = 0;
            }
        }
        addTextRun(runs, current, usefulChars, minChars);
    }

    private static void collectAsciiRuns(byte[] bytes, int minChars, Set<String> runs) {
        StringBuilder current = new StringBuilder();
        int usefulChars = 0;
        for (byte value : bytes) {
            char ch = (char) (value & 0xFF);
            if ((ch >= 32 && ch <= 126) || ch == '\r' || ch == '\n' || ch == '\t') {
                current.append(ch);
                if (isUsefulChar(ch)) {
                    usefulChars++;
                }
            } else {
                addTextRun(runs, current, usefulChars, minChars);
                current.setLength(0);
                usefulChars = 0;
            }
        }
        addTextRun(runs, current, usefulChars, minChars);
    }

    private static void addTextRun(Set<String> runs, StringBuilder current, int usefulChars, int minChars) {
        if (current.isEmpty() || usefulChars < minChars) {
            return;
        }
        String normalized = normalizeWhitespace(current.toString());
        if (isUsefulText(normalized)) {
            runs.add(normalized);
        }
    }

    private static String decodeText(byte[] bytes) {
        if (startsWith(bytes, (byte) 0xEF, (byte) 0xBB) && bytes.length >= 3 && bytes[2] == (byte) 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (startsWith(bytes, (byte) 0xFE, (byte) 0xFF)) {
            return new String(bytes, StandardCharsets.UTF_16BE);
        }
        if (startsWith(bytes, (byte) 0xFF, (byte) 0xFE)) {
            return new String(bytes, StandardCharsets.UTF_16LE);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void appendText(StringBuilder builder, String text) {
        String normalized = normalizeExtractedText(text);
        if (normalized.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(normalized);
    }

    private static String normalizeExtractedText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u0000', ' ')
                .replace('\u00A0', ' ');
        normalized = normalized.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", " ");
        normalized = normalized.replaceAll("[ \\t\\f\\u000B]+", " ");
        normalized = normalized.replaceAll(" *\\n *", "\n");
        normalized = normalized.replaceAll("\\n{3,}", "\n\n");
        return normalized.trim();
    }

    private static String normalizeWhitespace(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String xmlUnescape(String text) {
        return text
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private static String limitLength(String text) {
        if (text.length() <= MAX_EXTRACTED_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_EXTRACTED_LENGTH);
    }

    private static boolean isUsefulText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        int useful = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isUsefulChar(text.charAt(i))) {
                useful++;
            }
        }
        return useful >= 2;
    }

    private static boolean isUsefulChar(char ch) {
        return Character.isLetterOrDigit(ch) || isCjk(ch);
    }

    private static boolean isLikelyTextChar(char ch) {
        if (Character.isWhitespace(ch)) {
            return true;
        }
        if (Character.isISOControl(ch)) {
            return false;
        }
        if (Character.isLetterOrDigit(ch) || isCjk(ch)) {
            return true;
        }
        return ",.;:!?()[]{}<>+-_=/#@%&*'\"\\|".indexOf(ch) >= 0;
    }

    private static boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    private static String getExtension(Path file) {
        if (file == null || file.getFileName() == null) {
            return "";
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private record ParsedPdfToken(String text, int nextIndex) {
    }
}
