package com.dbboys.ui.util;

import com.dbboys.infra.i18n.I18n;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.model.Connect;
import javafx.application.Platform;

import java.util.Locale;
import java.util.regex.Pattern;

public final class ReadOnlyGuard {
    private static final Pattern WITH_MUTATION_PATTERN = Pattern.compile(
            "(?is)\\b(INSERT|UPDATE|DELETE|MERGE|CREATE|ALTER|DROP|TRUNCATE|CALL|EXEC|EXECUTE)\\b"
    );

    private ReadOnlyGuard() {
    }

    public static boolean isReadOnly(Connect connect) {
        return connect != null && Boolean.TRUE.equals(connect.getReadonly());
    }

    public static boolean canExecuteSql(Connect connect, String sql) {
        return !isReadOnly(connect) || isReadOnlySql(sql);
    }

    public static boolean isReadOnlySql(String sql) {
        String keyword = firstKeyword(sql);
        if (keyword.isEmpty() || "SELECT".equals(keyword)) {
            return true;
        }
        return "WITH".equals(keyword) && !WITH_MUTATION_PATTERN.matcher(stripLeadingTrivia(sql)).find();
    }

    public static String message() {
        return I18n.t("readonly.error.write_disabled", "当前连接为只读，禁止执行变更操作。");
    }

    public static boolean showBlockedAlertIfReadOnly(Connect connect) {
        if (!isReadOnly(connect)) {
            return false;
        }
        Platform.runLater(() -> AlertUtil.CustomAlert(I18n.t("common.error"), message()));
        return true;
    }

    private static String firstKeyword(String sql) {
        String text = stripLeadingTrivia(sql);
        if (text.isEmpty()) {
            return "";
        }
        int i = 0;
        while (i < text.length() && (Character.isLetter(text.charAt(i)) || text.charAt(i) == '_')) {
            i++;
        }
        return i == 0 ? "" : text.substring(0, i).toUpperCase(Locale.ROOT);
    }

    private static String stripLeadingTrivia(String sql) {
        if (sql == null) {
            return "";
        }
        int i = 0;
        while (i < sql.length()) {
            while (i < sql.length() && (Character.isWhitespace(sql.charAt(i)) || sql.charAt(i) == ';')) {
                i++;
            }
            if (i + 1 < sql.length() && sql.charAt(i) == '-' && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
                continue;
            }
            if (i + 1 < sql.length() && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    return "";
                }
                i = end + 2;
                continue;
            }
            break;
        }
        return i >= sql.length() ? "" : sql.substring(i).trim();
    }
}
