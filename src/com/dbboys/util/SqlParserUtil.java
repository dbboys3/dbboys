package com.dbboys.util;

import com.dbboys.vo.Sql;

import java.util.*;
import java.util.regex.*;

public class SqlParserUtil {
    public static final Pattern STRING_PATTERN = Pattern.compile("'([^'\\\\]*(\\\\.[^'\\\\]*)*)'"+"|" + "'[\\s\\S]*");
    public static final Pattern DOUBLE_STRING_PATTERN = Pattern.compile("\"[^\"]*\""+"|" + "\"[\\s\\S]*");
    public static final Pattern FANYINHAO_STRING_PATTERN = Pattern.compile("`[^`]*`"+"|" + "`[\\s\\S]*");
    public static final Pattern COMMENT_PATTERN = Pattern.compile("--[^\n]*" + "|"+"/\\*[\\s\\S]*?\\*/"+"|"+"/\\*[\\s\\S]*" +"|"+"\\{[\\s\\S]*?\\}");
    private static final String STRING_PATTERN_TEXT = "'([^'\\\\]*(\\\\.[^'\\\\]*)*)'" + "|" + "'[\\s\\S]*";
    private static final String DOUBLE_STRING_PATTERN_TEXT = "\"[^\"]*\"" + "|" + "\"[\\s\\S]*";
    private static final String COMMENT_PATTERN_TEXT = "--[^\\n]*" + "|"+"/\\*[\\s\\S]*?\\*/"+"|"+"/\\*[\\s\\S]*" +"|"+"\\{[\\s\\S]*?\\}";
    private static final String MULTI_LINE_START =
            "(?i)\\bcreate\\s+\\bprocedure\\s+(if\\s+not\\s+exists\\s+)?([a-zA-Z0-9_$.\"]*)\\s*\\([\\s\\S]*\\)\\s+(?!(as|is)\\b)" + "|" +
            "(?i)\\bcreate\\s+\\bfunction\\s+(if\\s+not\\s+exists\\s+)?([a-zA-Z0-9_$.\"]*)\\s*\\([\\s\\S]*\\)\\s+\\bRETURNING\\s+[a-zA-Z_][a-zA-Z0-9_$.]*\\s+(?!(as|is)\\b)" + "|" +
            "(?i)\\bcreate\\s+(OR\\s+REPLACE\\s+)?(package|procedure)\\s+(body\\s+)?([a-zA-Z_][a-zA-Z0-9_$.]*)\\s*(\\([\\s\\S]*?\\)\\s+)?(AS|IS)" + "|" +
            "(?i)\\bcreate\\s+(OR\\s+REPLACE\\s+)?(function)\\s+([a-zA-Z0-9_$.]*)\\s*(\\([\\s\\S]*?\\)\\s*)?return\\s+([a-zA-Z0-9_$.()]+)\\s+(AS|IS)" + "|" +
            "(?i)\\bcreate\\s+(OR\\s+REPLACE\\s+)?trigger\\s+([a-zA-Z0-9_$.\"]+)\\b";
    private static final String NO_NAME_BLOCK = "(?i)^\\s*\\b(begin)(?!\\s*(;|work))|(?i)^\\s*\\b(DECLARE)(?!\\s*;)";
    private static final String MULTI_LINE_END =
            "(?i)\\bend\\s+(procedure|function)\\s*;?" + "|" +
            "(?i)\\bend\\s*;\\s*/" + "|" +
            "(?i)\\bend\\b\\s+([a-zA-Z_][a-zA-Z0-9_$.]*)?\\s*/" + "|" +
            "(?m)^\\s*/\\s*$";
    private static final String DROP_DATABASE = "(?i)(?:drop\\s+)+database\\s+(\\w+)";
    private static final String CREATE_DATABASE = "(?i)(?:create\\s+)?database\\s+(?<dbname>(\\w+))";
    private static final String PACKAGE_BODY_PATTERN = "(?i)\\bcreate\\s+(OR\\s+REPLACE\\s+)?(package)\\s+body\\s+([a-zA-Z_][a-zA-Z0-9_$.]*)\\s+(AS|IS)";
    private static final String PACKAGE_MEMBER_PATTERN =
            "(?i)\\bfunction\\s+(?<FUNC>[a-zA-Z0-9_$.]+)\\s*(\\([\\s\\S]*?\\))?\\s+return\\s+([a-zA-Z0-9_$.]+)\\s*(PIPELINED\\s+|DETERMINISTIC\\s+|RESULT_CACHE\\s+)?(AS|IS|;)"
            + "|"
            + "(?i)\\bprocedure\\s+(?<PROC>[a-zA-Z0-9_$.]+)\\s*(\\([\\s\\S]*?\\))?\\s*(AS|IS|;)";

    public static class PackageMember {
        private final String name;
        private final String type;

        public PackageMember(String name, String type) {
            this.name = name;
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }
    }

    public static class Segment {
        private final String text;
        private final int endIndex;

        public Segment(String text, int endIndex) {
            this.text = text;
            this.endIndex = endIndex;
        }

        public String getText() {
            return text;
        }

        public int getEndIndex() {
            return endIndex;
        }
    }

    public static boolean isSingleStatement(String sql) {
        if (sql == null || sql.isEmpty()) {
            return true;
        }
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inBrackets = false;

        int length = sql.length();
        for (int i = 0; i < length; i++) {
            char current = sql.charAt(i);
            char next = (i + 1 < length) ? sql.charAt(i + 1) : '\0';

            if (!inSingleQuote && !inDoubleQuote && !inBlockComment && !inBrackets) {
                if (!inLineComment && current == '-' && next == '-') {
                    inLineComment = true;
                    i++;
                    continue;
                } else if (inLineComment && current == '\n') {
                    inLineComment = false;
                }
            }

            if (!inSingleQuote && !inDoubleQuote && !inLineComment && !inBrackets) {
                if (!inBlockComment && current == '/' && next == '*') {
                    inBlockComment = true;
                    i++;
                    continue;
                } else if (inBlockComment && current == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                    continue;
                }
            }

            if (!inDoubleQuote && !inLineComment && !inBlockComment && !inBrackets && current == '\'') {
                inSingleQuote = !inSingleQuote;
            }

            if (!inSingleQuote && !inLineComment && !inBlockComment && !inBrackets && current == '\"') {
                inDoubleQuote = !inDoubleQuote;
            }

            if (!inSingleQuote && !inDoubleQuote && !inLineComment && !inBlockComment) {
                if (current == '{') {
                    inBrackets = true;
                } else if (current == '}') {
                    inBrackets = false;
                }
            }

            if (!inSingleQuote && !inDoubleQuote && !inLineComment && !inBlockComment && !inBrackets && current == ';') {
                if (hasNonWhitespaceAfter(sql, i + 1)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasNonWhitespaceAfter(String sql, int start) {
        for (int i = start; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return true;
            }
        }
        return false;
    }

    public static List<Segment> split(String sql) {
        List<Segment> segments = new ArrayList<>();
        if (sql == null || sql.isEmpty()) {
            return segments;
        }
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inBrackets = false;

        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            char next = (i + 1 < sql.length()) ? sql.charAt(i + 1) : '\0';
            buffer.append(current);

            if (!inSingleQuote && !inDoubleQuote && !inBlockComment && !inBrackets) {
                if (!inLineComment && current == '-' && next == '-') {
                    inLineComment = true;
                    buffer.append(next);
                    i++;
                    continue;
                } else if (inLineComment && current == '\n') {
                    inLineComment = false;
                }
            }

            if (!inSingleQuote && !inDoubleQuote && !inLineComment && !inBrackets) {
                if (!inBlockComment && current == '/' && next == '*') {
                    inBlockComment = true;
                    buffer.append(next);
                    i++;
                    continue;
                } else if (inBlockComment && current == '*' && next == '/') {
                    inBlockComment = false;
                    buffer.append(next);
                    i++;
                    continue;
                }
            }

            if (!inDoubleQuote && !inLineComment && !inBlockComment && !inBrackets && current == '\'') {
                inSingleQuote = !inSingleQuote;
            }

            if (!inSingleQuote && !inLineComment && !inBlockComment && !inBrackets && current == '\"') {
                inDoubleQuote = !inDoubleQuote;
            }

            if (!inSingleQuote && !inDoubleQuote && !inLineComment && !inBlockComment) {
                if (current == '{') {
                    inBrackets = true;
                } else if (current == '}') {
                    inBrackets = false;
                }
            }

            if (i == sql.length() - 1
                    || (!inSingleQuote && !inDoubleQuote && !inLineComment && !inBlockComment && !inBrackets && current == ';')) {
                segments.add(new Segment(buffer.toString(), i));
                buffer.setLength(0);
            }
        }
        return segments;
    }

    public static Sql modifySql(Sql sql, String addSql) {
        if (!sql.getSqlRemainder().trim().isEmpty()) {
            addSql = sql.getSqlRemainder() + addSql;
            sql.setSqlRemainder("");
        }
        sql.setSqlEnd(false);

        Pattern pattern = Pattern.compile(
                STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT
        );
        Matcher matcherAll = pattern.matcher(addSql);
        String checkText = addSql.trim();

        if (matcherAll.find()) {
            checkText = matcherAll.replaceAll("").trim();
        }

        if (sql.getSqlstr().isEmpty()) {
            if (checkText.toUpperCase().startsWith("SELECT") || checkText.toUpperCase().startsWith("WITH")) {
                pattern = Pattern.compile("(?i)\\binto\\b");
                Matcher matcher = pattern.matcher(checkText);
                if (matcher.find()) {
                    sql.setSqlType("SELECT_INTO");
                } else {
                    sql.setSqlType("SELECT");
                }
                sql.setSqlEnd(true);
            } else if (checkText.toUpperCase().startsWith("CALL") || checkText.toUpperCase().startsWith("EXECUTE")) {
                sql.setSqlType("CALL");
                sql.setSqlEnd(true);
            } else {
                pattern = Pattern.compile(
                        STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT
                                + "|(?<START>" + MULTI_LINE_START + ")"
                );
                Matcher matcher = pattern.matcher(addSql);
                while (matcher.find()) {
                    if (matcher.group("START") != null) {
                        sql.setSqlType("MULTI_LINE_SQL");
                        break;
                    }
                }

                pattern = Pattern.compile(
                        STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT
                                + "|(?<BLOCK>" + NO_NAME_BLOCK + ")"
                );
                matcher = pattern.matcher(addSql);
                while (matcher.find()) {
                    if (matcher.group("BLOCK") != null) {
                        sql.setSqlType("CALL_BLOCK");
                        break;
                    }
                }

                pattern = Pattern.compile(
                        STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT
                                + "|(?<END>" + MULTI_LINE_END + ")"
                );
                matcher = pattern.matcher(addSql);
                while (matcher.find()) {
                    if (matcher.group("END") != null) {
                        sql.setSqlRemainder(addSql.substring(matcher.end("END")));
                        addSql = addSql.substring(0, matcher.end("END") - 1);
                        sql.setSqlEnd(true);
                        break;
                    }
                }

                if (!sql.getSqlType().equals("MULTI_LINE_SQL") && !sql.getSqlType().equals("CALL_BLOCK")) {
                    sql.setSqlEnd(true);
                    pattern = Pattern.compile(
                            STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT + "|" + DROP_DATABASE
                                    + "|" + CREATE_DATABASE
                    );
                    matcher = pattern.matcher(addSql);
                    while (matcher.find()) {
                        if (matcher.group("dbname") != null) {
                            sql.setSqlType("DATABASE " + matcher.group("dbname").toLowerCase());
                        }
                    }
                }
            }
            sql.setSqlStr(addSql);
        } else {
            if (sql.getSqlType().equals("MULTI_LINE_SQL") || sql.getSqlType().equals("CALL_BLOCK")) {
                pattern = Pattern.compile(
                        STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT
                                + "|(?<END>" + MULTI_LINE_END + ")"
                );
                Matcher matcher = pattern.matcher(addSql);
                while (matcher.find()) {
                    if (matcher.group("END") != null) {
                        sql.setSqlRemainder(addSql.substring(matcher.end("END")));
                        addSql = addSql.substring(0, matcher.end("END") - 1);
                        sql.setSqlEnd(true);
                        break;
                    }
                }
                sql.setSqlStr(sql.getSqlstr() + addSql);
            }
        }

        return sql;
    }

    public static boolean sqlContrainCommit(String remainderSql) {
        boolean result = false;
        Pattern pattern = Pattern.compile(
                STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT
                        + "|(?<END>" + MULTI_LINE_END + ")"
        );
        Matcher matcher = pattern.matcher(remainderSql);
        while (matcher.find()) {
            if (matcher.group("END") != null) {
                result = true;
            }
            break;
        }

        if (!result) {
            boolean containBegin = false;
            pattern = Pattern.compile(
                    STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT
                            + "|(?<START>" + MULTI_LINE_START + ")"
            );
            matcher = pattern.matcher(remainderSql);
            while (matcher.find()) {
                if (matcher.group("START") != null) {
                    containBegin = true;
                }
                break;
            }
            if (!containBegin && !remainderSql.trim().isEmpty()) {
                result = true;
            }
        }

        return result;
    }

    public static boolean sqlContrainMoreThanOneCommit(String remainderSql) {
        boolean result = false;
        Pattern pattern = Pattern.compile(
                STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT
                        + "|(?<END>" + MULTI_LINE_END + ")"
        );
        int count = 0;
        Matcher matcher = pattern.matcher(remainderSql);
        while (matcher.find()) {
            if (matcher.group("END") != null) {
                count++;
            }
            if (count > 1) {
                result = true;
            }
        }
        return result;
    }

    public static String getFromTable(String sql) {
        String fromTable = null;
        Pattern pattern = Pattern.compile(
                "(?<STRING>" + STRING_PATTERN_TEXT + ")"
                        + "|(?<DOUBLESTRING>" + DOUBLE_STRING_PATTERN_TEXT + ")"
                        + "|(?<COMMENT>" + COMMENT_PATTERN_TEXT + ")"
        );
        Matcher matcherAll = pattern.matcher(sql);
        String checkText = sql.trim();

        if (matcherAll.find()) {
            checkText = matcherAll.replaceAll("").trim();
        }

        while (checkText.contains("(")) {
            checkText = checkText.replaceAll("\\([^()]*\\)", "");
        }
        pattern = Pattern.compile("(?i)\\b(WHERE|ORDER|GROUP)\\b.*", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(checkText);
        if (matcher.find()) {
            checkText = matcher.replaceAll("").trim();
        }

        pattern = Pattern.compile("(?i)\\bfrom\\b\\s+(\\S+)(?!.*,|.*\\bjoin\\b.*)", Pattern.DOTALL);
        matcher = pattern.matcher(checkText);
        if (matcher.find()) {
            fromTable = matcher.group(1).replaceAll(";", "");
        }
        return fromTable;
    }

    public static List<String> getSelectedCols(String sql, List<String> cols) {
        String regex = "(?i)SELECT\\s+(.*?)\\s+FROM(?![^(]*\\))";

        Pattern pattern = Pattern.compile(regex, Pattern.DOTALL);
        Matcher matcher = pattern.matcher(sql);
        String selectContent = null;
        if (matcher.find()) {
            selectContent = matcher.group(1).trim();
        }

        String input = selectContent;
        List<String> result = new ArrayList<>();
        StringBuilder currentPart = new StringBuilder();
        int parenthesesLevel = 0;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (ch == '(') {
                parenthesesLevel++;
                currentPart.append(ch);
            } else if (ch == ')') {
                parenthesesLevel--;
                currentPart.append(ch);
            } else if (ch == ',' && parenthesesLevel == 0) {
                result.add(currentPart.toString().replaceAll("(?i)\\b+AS\\b(?s).*", "").trim().replaceAll("\\s[^\\s]*$", "").toLowerCase());
                currentPart.setLength(0);
            } else {
                currentPart.append(ch);
            }
        }

        if (currentPart.length() > 0) {
            result.add(currentPart.toString().replaceAll("(?i)\\b+AS\\b(?s).*", "").trim().replaceAll("\\s[^\\s]*$", "").toLowerCase());
        }
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i).trim().equals("*")) {
                result.remove(i);
                result.addAll(i, cols);
            }
        }
        return result;
    }

    public static List<PackageMember> parsePackageMembers(String packageDdl) {
        List<PackageMember> members = new ArrayList<>();
        if (packageDdl == null || packageDdl.isEmpty()) {
            return members;
        }

        Pattern bodyPattern = Pattern.compile(
                "(?<STRING>" + STRING_PATTERN_TEXT + ")"
                        + "|(?<DOUBLESTRING>" + DOUBLE_STRING_PATTERN_TEXT + ")"
                        + "|(?<COMMENT>" + COMMENT_PATTERN_TEXT + ")"
                        + "|(?<BODY>" + PACKAGE_BODY_PATTERN + ")"
        );
        Matcher bodyMatcher = bodyPattern.matcher(packageDdl);
        String bodySql = "";
        while (bodyMatcher.find()) {
            if (bodyMatcher.group("BODY") != null) {
                bodySql = packageDdl.substring(bodyMatcher.start("BODY"));
            }
        }
        if (bodySql.isEmpty()) {
            bodySql = packageDdl;
        }

        Pattern memberPattern = Pattern.compile(
                STRING_PATTERN_TEXT
                        + "|" + DOUBLE_STRING_PATTERN_TEXT
                        + "|" + COMMENT_PATTERN_TEXT
                        + "|" + PACKAGE_MEMBER_PATTERN
        );
        Matcher memberMatcher = memberPattern.matcher(bodySql);
        while (memberMatcher.find()) {
            if (memberMatcher.group("FUNC") != null) {
                members.add(new PackageMember(memberMatcher.group("FUNC"), "FUNC"));
            }
            if (memberMatcher.group("PROC") != null) {
                members.add(new PackageMember(memberMatcher.group("PROC"), "PROC"));
            }
        }

        return members;
    }

    public static String printPackageFunction(String packagesql, String function) {
        if (packagesql == null || packagesql.isBlank() || function == null || function.isBlank()) {
            return "";
        }

        String functionString = "";
        String stringPattern = "'([^'\\\\]*(\\\\.[^'\\\\]*)*)'" + "|" + "'[\\s\\S]*";
        String doubleStringPattern = "\"[^\"]*\"" + "|" + "\"[\\s\\S]*";
        String commentPattern = "--[^\\n]*" + "|" + "/\\*[\\s\\S]*?\\*/" + "|" + "/\\*[\\s\\S]*" + "|" + "\\{[\\s\\S]*?\\}";
        String bodyPattern = "(?i)\\bcreate\\s+(OR\\s+REPLACE\\s+)?package\\s+body\\b[\\s\\S]*?\\b(AS|IS)\\b";

        String normalizedName = function.trim().replace("\"", "");
        String escapedName = Pattern.quote(normalizedName);
        String namePattern = "\\\"?" + escapedName + "\\\"?";
        String functionPattern =
                "(?is)\\bfunction\\s+" + namePattern +
                        "\\s*(\\([\\s\\S]*?\\))?\\s+return\\s+([a-zA-Z0-9_$.\\\"]+)" +
                        "\\s*(PIPELINED\\s+|DETERMINISTIC\\s+|RESULT_CACHE\\s+)?(AS|IS)\\b" +
                        "[\\s\\S]*?\\bend\\s*(" + namePattern + ")?\\s*;"
                        + "|"
                        + "(?is)\\bprocedure\\s+" + namePattern +
                        "\\s*(\\([\\s\\S]*?\\))?\\s*(AS|IS)\\b" +
                        "[\\s\\S]*?\\bend\\s*(" + namePattern + ")?\\s*;";

        Pattern pattern = Pattern.compile(
                "(?<STRING>" + stringPattern + ")"
                        + "|(?<DOUBLESTRING>" + doubleStringPattern + ")"
                        + "|(?<COMMENT>" + commentPattern + ")"
                        + "|(?<BODY>" + bodyPattern + ")"
        );
        Matcher matcher = pattern.matcher(packagesql);
        String bodySql = "";
        while (matcher.find()) {
            if (matcher.group("BODY") != null) {
                bodySql = packagesql.substring(matcher.start("BODY"));
            }
        }
        if (bodySql.isEmpty()) {
            bodySql = packagesql;
        }

        pattern = Pattern.compile(
                "(?<STRING>" + stringPattern + ")"
                        + "|(?<DOUBLESTRING>" + doubleStringPattern + ")"
                        + "|(?<COMMENT>" + commentPattern + ")"
                        + "|(?<FUNC>" + functionPattern + ")"
        );
        matcher = pattern.matcher(bodySql);
        while (matcher.find()) {
            if (matcher.group("FUNC") != null) {
                functionString = matcher.group("FUNC");
                break;
            }
        }
        return functionString;
    }

    public static String formatSql(String sql) {

        Map<String, String> placeholders = new HashMap<>();
        int[] index = {0};

        // 1. 保护字符串和注释
        sql = protectPattern(sql, STRING_PATTERN, placeholders, index);
        sql = protectPattern(sql, DOUBLE_STRING_PATTERN, placeholders, index);
        sql = protectPattern(sql, FANYINHAO_STRING_PATTERN, placeholders, index);
        sql = protectPattern(sql, COMMENT_PATTERN, placeholders, index);
        // 1. 去掉多余空格

        // 3. 用栈处理括号缩进
        StringBuilder result = new StringBuilder();
        Stack<Integer> indentStack = new Stack<>();
        int currentIndent = 0;
        //String tab = "\t";
        String tab = "  ";


        String[] sqls = sql.split("(?<=;)");
        for(String subsql:sqls){
            String checkSql=subsql.trim().replaceAll("[ \\t]+", " ")    // 连续空格/tab → 单个空格
                    .replaceAll("\\s*([=<>+*/-])\\s*", " $1 ") // 操作符两边保留一个空格
                    .replaceAll("\\s+", " ")       // 再压缩一次多余空格
                    .trim();
            //去掉(后面及)前面的空格
            checkSql = checkSql.replaceAll("(?i)(\\()\\s+", "$1").replaceAll("(?i)\\s+(\\))", "$1");
            //去掉两个符合之间的空格
            checkSql = checkSql.replaceAll("([=<>+*/-]) ([=<>+*/-])", "$1$2");

            if(checkSql.toLowerCase().startsWith("select ")
                    || checkSql.toLowerCase().startsWith("with ")
                    || checkSql.toLowerCase().startsWith("create table ")
                    || checkSql.toLowerCase().startsWith("create view ")
                    || checkSql.toLowerCase().startsWith("create index ")
                    || checkSql.toLowerCase().startsWith("create sequence ")
                    || checkSql.toLowerCase().startsWith("create trigger ")
                    || checkSql.toLowerCase().startsWith("alter table ")
                    || checkSql.toLowerCase().startsWith("alter fragment ")
                    || checkSql.toLowerCase().startsWith("rename ")
                    || checkSql.toLowerCase().startsWith("comment on ")
                    || checkSql.toLowerCase().startsWith("delete from ")
                    || checkSql.toLowerCase().startsWith("drop ")
                    || checkSql.toLowerCase().startsWith("insert ")
                    || checkSql.toLowerCase().startsWith("update ")
                    || checkSql.toLowerCase().startsWith("delete ")
                    || checkSql.toLowerCase().startsWith("truncate ")
            ){
                subsql=checkSql;
                String[] tokens = subsql.split("(?=\\()|(?<=\\))"); // 按括号拆分
                String appendSql="";
                for (String token : tokens) {
                    if (token.isEmpty()) continue;
                    if (token.matches("(?i)^\\(\\s*select[\\s\\S]+")||(token.contains("(")&&!token.contains(")"))) {
                        currentIndent++;
                        indentStack.push(currentIndent);
                        token=token.replaceAll("(?i)(__PLACEHOLDER_COMMENT_[0-9]+__)\n?\\s*","\n"+tab.repeat(currentIndent+1)+"$0\n"+tab.repeat(currentIndent+1));
                        token="\n"+tab.repeat(currentIndent)+"("+"\n"+tab.repeat(currentIndent)+token.substring(1).trim();
                        token=token.replaceAll("\\s*,\\s*",",\n"+tab.repeat(currentIndent+1)).replaceAll("(?i)\\b(FROM|LEFT JOIN|WHERE|GROUP BY|ORDER BY|JOIN|RIGHT JOIN|UNION)\\b","\n"+tab.repeat(currentIndent)+"$0").replaceAll("(?i)\\b(AND|OR|ON|HAVING)\\b","\n"+tab.repeat(currentIndent+1)+"$0");
                        token=token.replaceAll(";",";\n");
                        if(token.contains(")")){
                            indentStack.pop();
                            currentIndent--;
                            token=token.replaceAll("\\)","\n"+tab.repeat(currentIndent+1)+")");
                            token=token.replaceAll(";",";\n");
                        }
                    } else if (token.contains(")")&&!token.contains("(")) {
                        token=token.replaceAll("(?i)(__PLACEHOLDER_COMMENT_[0-9]+__)\n?\\s*","\n"+tab.repeat(currentIndent+1)+"$0\n"+tab.repeat(currentIndent+1));
                        //token="\n"+tab.repeat(currentIndent)+token.replaceAll("\\)","\n"+tab.repeat(currentIndent)+")");
                        token=token.replaceAll("\\)","\n"+tab.repeat(currentIndent)+")");
                        token=token.replaceAll("\\s*,\\s*",",\n"+tab.repeat(currentIndent+1)).replaceAll("(?i)\\b(FROM|LEFT JOIN|WHERE|GROUP BY|ORDER BY|JOIN|RIGHT JOIN|UNION)\\b","\n"+tab.repeat(currentIndent)+"$0").replaceAll("(?i)\\b(AND|OR|ON|HAVING)\\b","\n"+tab.repeat(currentIndent+1)+"$0");
                        token=token.replaceAll(";",";\n");
                        if (!indentStack.isEmpty()) {
                            currentIndent = indentStack.pop();
                            currentIndent--;
                        }
                    } else {
                        //token=" "+token;
                        token = token.replaceAll("(?i)(__PLACEHOLDER_COMMENT_[0-9]+__)\n?\\s*", "\n" + tab.repeat(currentIndent + 1) + "$0\n"+tab.repeat(currentIndent + 1));
                        if(token.contains("(")&&token.contains(")")){
                            token=token.replaceAll("\\s*,\\s*",",").replaceAll("(?i)\\b(FROM|LEFT JOIN|WHERE|GROUP BY|ORDER BY|JOIN|RIGHT JOIN|UNION)\\b","\n"+tab.repeat(currentIndent)+"$0").replaceAll("(?i)\\b(AND|OR|ON|HAVING)\\b","\n"+tab.repeat(currentIndent+1)+"$0");
                        }else {
                            token = token.replaceAll("\\s*,\\s*", ",\n" + tab.repeat(currentIndent + 1)).replaceAll("(?i)\\b(FROM|LEFT JOIN|WHERE|GROUP BY|ORDER BY|JOIN|RIGHT JOIN|UNION)\\b", "\n" + tab.repeat(currentIndent) + "$0").replaceAll("(?i)\\b(AND|OR|ON|HAVING)\\b", "\n" + tab.repeat(currentIndent + 1) + "$0");
                        }
                        token=token.replaceAll(";",";\n");
                    }
                    appendSql+=token;
                }

                //视图as select换行
                appendSql = appendSql.replaceAll("(?i)(as\\s+)(select\\s+)", "$1\n$2");

                //union后换行
                appendSql = appendSql.replaceAll("(?i)(\\s*)(union\\s+\\w*\\s*)(select\\s+)", "$1$2\n$1$3");
                //去掉函数括号的缩进
                appendSql = appendSql.replaceAll("(?i)(\\w+)\\t+\\(", "$1(");

                //)后面的select换行,主要用于with as语句后的select
                appendSql = appendSql.replaceAll("(?i)(\\s*)\\)\\s*(\\bselect\\b\\s+)", "$1)\n"+"$2");
                //(后面首行缩进调整
                appendSql = appendSql.replaceAll("(?i)(\\(\\n)(\\s+(?!select\\b)\\w+)", "$1"+tab+"$2");
                result.append(appendSql);
            }else{
                result.append("\n"+subsql);
            }
        }
        sql=result.toString();
        sql=sql.replaceAll(";", ";\n");
        sql=sql.replaceAll("\\n\\s*\\n", "\n") // 去掉多余空行
                .trim();


        //把括号后面的别名放到括号后面，避免对不齐
        //sql = sql.replaceAll("(?i)\\)\\s+([as ]?\\w+\\s+,?)", ") $1");
        //sql = sql.replaceAll("(?i)\\)\\s+([as ]?\\w+\\s+)\\s+(FROM|,)", ") $1");
        //sql = sql.replaceAll("(?i)\\)\\s+((as )?\\w+\\s+(,|FROM))", ") $1");



        //恢复注释和字符串
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            sql = sql.replace(entry.getKey(), entry.getValue());
        }

        return sql;

    }


    public static String upperSql(String sql) {
        Map<String, String> placeholders = new HashMap<>();
        int[] index = {0};
        sql = protectPattern(sql, STRING_PATTERN, placeholders, index);
        sql = protectPattern(sql, DOUBLE_STRING_PATTERN, placeholders, index);
        sql = protectPattern(sql, FANYINHAO_STRING_PATTERN, placeholders, index);
        sql = protectPattern(sql, COMMENT_PATTERN, placeholders, index);
        sql=sql.toUpperCase();
        //恢复注释和字符串
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            sql = sql.replace(entry.getKey(), entry.getValue());
        }
        return sql;
    }

    public static String lowerSql(String sql) {
        Map<String, String> placeholders = new HashMap<>();
        int[] index = {0};
        sql = protectPattern(sql, STRING_PATTERN, placeholders, index);
        sql = protectPattern(sql, DOUBLE_STRING_PATTERN, placeholders, index);
        sql = protectPattern(sql, FANYINHAO_STRING_PATTERN, placeholders, index);
        sql = protectPattern(sql, COMMENT_PATTERN, placeholders, index);
        sql=sql.toLowerCase().replaceAll("__placeholder_","__PLACEHOLDER_");
        //恢复注释和字符串
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            sql = sql.replace(entry.getKey(), entry.getValue());
        }
        return sql;
    }
    private static String protectPattern(String sql, Pattern pattern, Map<String, String> placeholders, int[] index) {
        Matcher matcher = pattern.matcher(sql);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String placeholder="";
            String match="";
            if(pattern.equals(COMMENT_PATTERN)){
                placeholder = "__PLACEHOLDER_COMMENT_" + index[0] + "__";
                match = matcher.group();
            }else{
                placeholder = "__PLACEHOLDER_" + index[0] + "__";
                match = matcher.group();
            }
            placeholders.put(placeholder, match);
            matcher.appendReplacement(sb, placeholder);
            index[0]++;
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
    public static void main(String[] args) {
    }
}
