package com.dbboys.ui.component.completion.provider;

import com.dbboys.infra.util.KeywordsHighlightUtil;
import com.dbboys.ui.component.completion.CandidateProvider;
import com.dbboys.ui.component.completion.CompletionContext;
import com.dbboys.ui.component.completion.CompletionItem;
import com.dbboys.ui.component.completion.CompletionKind;

import java.util.*;

/**
 * Provides SQL built-in function completions with signature hints.
 *
 * <p>Always applicable.  Each item's detail shows the function signature
 * (e.g. {@code COUNT(expr)}) so the user can see parameter requirements
 * without leaving the popup.
 *
 * <p>Signature data is defined in a static map populated at class-load time.
 */
public class FunctionProvider implements CandidateProvider {

    // ---- function signature lookup ----
    // NOTE: SIGNATURES must be initialized BEFORE ALL_FUNCTIONS because
    // loadFunctions() -> lookupSignature() reads SIGNATURES.
    private static final Map<String, String> SIGNATURES = buildSignatureMap();

    /** Full completion items (label = function name, detail = signature). */
    private static final CompletionItem[] ALL_FUNCTIONS = loadFunctions();

    private static CompletionItem[] loadFunctions() {
        String[] functions = KeywordsHighlightUtil.getSqlFunctions();
        CompletionItem[] items = new CompletionItem[functions.length];
        for (int i = 0; i < functions.length; i++) {
            String name = functions[i];
            String signature = lookupSignature(name);
            items[i] = new CompletionItem(
                    name,
                    name + "()",
                    CompletionKind.FUNCTION,
                    signature,
                    200
            );
        }
        return items;
    }

    @Override
    public boolean appliesTo(CompletionContext ctx) {
        return !ctx.isDisabled();
    }

    @Override
    public List<CompletionItem> fetch(String prefix, CompletionContext ctx) {
        if (prefix == null || prefix.isEmpty()) {
            return List.of();
        }
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        List<CompletionItem> results = new ArrayList<>();
        int count = 0;
        for (CompletionItem item : ALL_FUNCTIONS) {
            if (item.getLabel().toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                results.add(item);
                if (++count >= MAX_RESULTS) break;
            }
        }
        return results;
    }

    private static Map<String, String> buildSignatureMap() {
        Map<String, String> map = new HashMap<>();
        // Aggregate
        map.put("AVG", "AVG(expr)");
        map.put("COUNT", "COUNT(expr)");
        map.put("COUNT_BIG", "COUNT_BIG(expr)");
        map.put("MAX", "MAX(expr)");
        map.put("MEDIAN", "MEDIAN(expr)");
        map.put("MIN", "MIN(expr)");
        map.put("STDDEV", "STDDEV(expr)");
        map.put("SUM", "SUM(expr)");
        map.put("VARIANCE", "VARIANCE(expr)");
        map.put("LIST", "LIST(expr)");
        map.put("LISTAGG", "LISTAGG(expr, delimiter)");
        map.put("WM_CONCAT", "WM_CONCAT(expr)");
        // Null / conditional
        map.put("CASE", "CASE WHEN cond THEN result [ELSE default] END");
        map.put("COALESCE", "COALESCE(expr1, expr2, ...)");
        map.put("DECODE", "DECODE(expr, search1, result1, ...)");
        map.put("GREATEST", "GREATEST(expr1, expr2, ...)");
        map.put("IIF", "IIF(cond, trueVal, falseVal)");
        map.put("IFNULL", "IFNULL(expr, replacement)");
        map.put("ISNULL", "ISNULL(expr)");
        map.put("LEAST", "LEAST(expr1, expr2, ...)");
        map.put("NULLIF", "NULLIF(expr1, expr2)");
        map.put("NVL", "NVL(expr, replacement)");
        map.put("NVL2", "NVL2(expr, notNullVal, nullVal)");
        // String
        map.put("ASCII", "ASCII(str)");
        map.put("BIT_LENGTH", "BIT_LENGTH(str)");
        map.put("CHAR_LENGTH", "CHAR_LENGTH(str)");
        map.put("CHR", "CHR(n)");
        map.put("CONCAT", "CONCAT(str1, str2)");
        map.put("INITCAP", "INITCAP(str)");
        map.put("INSTR", "INSTR(str, substr)");
        map.put("LEFT", "LEFT(str, n)");
        map.put("LENGTH", "LENGTH(str)");
        map.put("LENGTHB", "LENGTHB(str)");
        map.put("LOCATE", "LOCATE(substr, str)");
        map.put("LOWER", "LOWER(str)");
        map.put("LPAD", "LPAD(str, len, pad)");
        map.put("LTRIM", "LTRIM(str)");
        map.put("OCTET_LENGTH", "OCTET_LENGTH(str)");
        map.put("POSITION", "POSITION(substr IN str)");
        map.put("REGEXP_INSTR", "REGEXP_INSTR(str, pattern)");
        map.put("REGEXP_LIKE", "REGEXP_LIKE(str, pattern)");
        map.put("REGEXP_REPLACE", "REGEXP_REPLACE(str, pattern, repl)");
        map.put("REGEXP_SUBSTR", "REGEXP_SUBSTR(str, pattern)");
        map.put("REPEAT", "REPEAT(str, n)");
        map.put("REPLACE", "REPLACE(str, from, to)");
        map.put("REVERSE", "REVERSE(str)");
        map.put("RIGHT", "RIGHT(str, n)");
        map.put("RPAD", "RPAD(str, len, pad)");
        map.put("RTRIM", "RTRIM(str)");
        map.put("SUBSTR", "SUBSTR(str, start, [len])");
        map.put("SUBSTRING", "SUBSTRING(str, start, [len])");
        map.put("TRANSLATE", "TRANSLATE(str, from, to)");
        map.put("TRIM", "TRIM(str)");
        map.put("UPPER", "UPPER(str)");
        // Numeric
        map.put("ABS", "ABS(n)");
        map.put("ACOS", "ACOS(n)");
        map.put("ASIN", "ASIN(n)");
        map.put("ATAN", "ATAN(n)");
        map.put("ATAN2", "ATAN2(y, x)");
        map.put("CEIL", "CEIL(n)");
        map.put("CEILING", "CEILING(n)");
        map.put("COS", "COS(n)");
        map.put("DEGREES", "DEGREES(n)");
        map.put("EXP", "EXP(n)");
        map.put("FLOOR", "FLOOR(n)");
        map.put("LN", "LN(n)");
        map.put("LOG", "LOG(base, n)");
        map.put("LOG10", "LOG10(n)");
        map.put("MOD", "MOD(n, m)");
        map.put("PI", "PI()");
        map.put("POWER", "POWER(base, exp)");
        map.put("RADIANS", "RADIANS(n)");
        map.put("RAND", "RAND()");
        map.put("RANDOM", "RANDOM()");
        map.put("ROUND", "ROUND(n, [decimals])");
        map.put("SIGN", "SIGN(n)");
        map.put("SIN", "SIN(n)");
        map.put("SQRT", "SQRT(n)");
        map.put("TAN", "TAN(n)");
        map.put("TRUNC", "TRUNC(n, [decimals])");
        // Date
        map.put("ADD_MONTHS", "ADD_MONTHS(date, n)");
        map.put("CURRENT", "CURRENT");
        map.put("CURRENT_DATE", "CURRENT_DATE");
        map.put("CURRENT_TIME", "CURRENT_TIME");
        map.put("CURRENT_TIMESTAMP", "CURRENT_TIMESTAMP");
        map.put("DATEADD", "DATEADD(part, n, date)");
        map.put("DATEDIFF", "DATEDIFF(part, start, end)");
        map.put("DATE_FORMAT", "DATE_FORMAT(date, fmt)");
        map.put("DATE_TRUNC", "DATE_TRUNC(part, date)");
        map.put("DBINFO", "DBINFO(key)");
        map.put("EXTRACT", "EXTRACT(part FROM date)");
        map.put("GETDATE", "GETDATE()");
        map.put("LAST_DAY", "LAST_DAY(date)");
        map.put("MONTHS_BETWEEN", "MONTHS_BETWEEN(date1, date2)");
        map.put("NEXT_DAY", "NEXT_DAY(date, weekday)");
        map.put("NOW", "NOW()");
        map.put("QUARTER", "QUARTER(date)");
        map.put("SYSDATE", "SYSDATE");
        map.put("SYSTIMESTAMP", "SYSTIMESTAMP");
        map.put("TO_CHAR", "TO_CHAR(value, [fmt])");
        map.put("TO_DATE", "TO_DATE(str, fmt)");
        map.put("TODAY", "TODAY");
        map.put("WEEK", "WEEK(date)");
        map.put("YEAR", "YEAR(date)");
        map.put("MONTH", "MONTH(date)");
        map.put("DAY", "DAY(date)");
        map.put("HOUR", "HOUR(datetime)");
        map.put("MINUTE", "MINUTE(datetime)");
        map.put("SECOND", "SECOND(datetime)");
        map.put("FRACTION", "FRACTION(datetime)");
        // Window / analytic
        map.put("DENSE_RANK", "DENSE_RANK() OVER(...)");
        map.put("FIRST_VALUE", "FIRST_VALUE(expr) OVER(...)");
        map.put("LAG", "LAG(expr, [offset], [default]) OVER(...)");
        map.put("LAST_VALUE", "LAST_VALUE(expr) OVER(...)");
        map.put("LEAD", "LEAD(expr, [offset], [default]) OVER(...)");
        map.put("NTH_VALUE", "NTH_VALUE(expr, n) OVER(...)");
        map.put("NTILE", "NTILE(n) OVER(...)");
        map.put("RANK", "RANK() OVER(...)");
        map.put("ROW_NUMBER", "ROW_NUMBER() OVER(...)");
        // Type / conversion
        map.put("CAST", "CAST(expr AS type)");
        map.put("CONVERT", "CONVERT(type, expr)");
        map.put("STR_TO_DATE", "STR_TO_DATE(str, fmt)");
        map.put("TO_MULTI_BYTE", "TO_MULTI_BYTE(str)");
        map.put("TO_NCHAR", "TO_NCHAR(expr)");
        map.put("TO_NUMBER", "TO_NUMBER(str)");
        map.put("TO_SINGLE_BYTE", "TO_SINGLE_BYTE(str)");
        map.put("TRY_CONVERT", "TRY_CONVERT(type, expr)");
        map.put("SYS_GUID", "SYS_GUID()");
        // Crypto / encoding
        map.put("BASE64_DECODE", "BASE64_DECODE(str)");
        map.put("BASE64_ENCODE", "BASE64_ENCODE(str)");
        map.put("DECRYPT", "DECRYPT(str)");
        map.put("ENCRYPT", "ENCRYPT(str)");
        map.put("HASH", "HASH(str)");
        map.put("HEX", "HEX(str)");
        map.put("MD5", "MD5(str)");
        map.put("SHA1", "SHA1(str)");
        map.put("SHA2", "SHA2(str)");
        map.put("UNHEX", "UNHEX(str)");
        // Session / system
        map.put("CURRENT_USER", "CURRENT_USER");
        map.put("DATABASE", "DATABASE()");
        map.put("HOSTNAME", "HOSTNAME()");
        map.put("SESSION_USER", "SESSION_USER");
        map.put("SYSTEM_USER", "SYSTEM_USER");
        map.put("USER", "USER");
        map.put("BITAND", "BITAND(a, b)");
        map.put("BITOR", "BITOR(a, b)");
        map.put("BITXOR", "BITXOR(a, b)");

        return Collections.unmodifiableMap(map);
    }

    private static String lookupSignature(String functionName) {
        return SIGNATURES.getOrDefault(functionName.toUpperCase(Locale.ROOT), functionName + "(…)");
    }
}
