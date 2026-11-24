package com.example.demo.util.crudgen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CREATE TABLE 문 → 테이블/컬럼 정보.
 *
 * DB 툴에서 복사한 DDL 이나 SHOW CREATE TABLE 결과를 그대로 붙여넣을 수 있게 하려는 용도.
 * 완전한 SQL 파서가 아니라 CRUD 생성에 필요한 정보(이름, 타입, NOT NULL, PK, AUTO_INCREMENT, COMMENT)만 읽는다.
 */
final class DdlParser {

    /**
     * @param tableName 테이블명 (스키마명·따옴표 제거)
     * @param columns   컬럼 목록 (DDL 순서)
     */
    record Table(String tableName, List<Column> columns) {
    }

    /**
     * @param name          DB 컬럼명 (예: user_name)
     * @param type          DB 타입 (예: VARCHAR(50), BIGINT, DECIMAL(10,2), NUMBER(10))
     * @param pk            PK 여부
     * @param nullable      NULL 허용 여부 (PK 는 항상 false)
     * @param autoIncrement AUTO_INCREMENT / IDENTITY / SERIAL 여부
     * @param comment       컬럼 설명 → 엔티티 필드 주석으로 들어감 (없으면 null)
     */
    record Column(String name, String type, boolean pk, boolean nullable, boolean autoIncrement, String comment) {
    }

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?((?:[`\"\\[]?\\w+[`\"\\]]?\\.)?[`\"\\[]?\\w+[`\"\\]]?)\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    /**
     * 컬럼명 + 타입 + 나머지 옵션.
     * 타입에는 괄호, UNSIGNED, WITH/WITHOUT TIME ZONE (Oracle, PostgreSQL), 배열 표시 [] 까지 포함한다.
     */
    private static final Pattern COLUMN = Pattern.compile(
            "^([`\"\\[]?\\w+[`\"\\]]?)\\s+("
                    + "[A-Za-z][A-Za-z0-9_]*(?:\\s+PRECISION|\\s+VARYING)?"
                    + "(?:\\s*\\([^)]*\\))?"
                    + "(?:\\s+UNSIGNED|\\s+WITH\\s+LOCAL\\s+TIME\\s+ZONE|\\s+WITH\\s+TIME\\s+ZONE|\\s+WITHOUT\\s+TIME\\s+ZONE)?"
                    + "(?:\\s*\\[\\s*\\d*\\s*\\])*"
                    + ")(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern PRIMARY_KEY = Pattern.compile("PRIMARY\\s+KEY\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    /** MySQL: 컬럼 정의 안의 COMMENT '...' */
    private static final Pattern COMMENT = Pattern.compile("COMMENT\\s+'((?:[^']|'')*)'", Pattern.CASE_INSENSITIVE);
    /** Oracle, PostgreSQL: 별도 문장 COMMENT ON COLUMN [스키마.]테이블.컬럼 IS '...' */
    private static final Pattern COMMENT_ON_COLUMN = Pattern.compile(
            "COMMENT\\s+ON\\s+COLUMN\\s+([`\"\\[\\]\\w.]+)\\s+IS\\s+'((?:[^']|'')*)'", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_NULL = Pattern.compile("\\bNOT\\s+NULL\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_PK = Pattern.compile("\\bPRIMARY\\s+KEY\\b", Pattern.CASE_INSENSITIVE);
    /**
     * 자동 증가: MySQL AUTO_INCREMENT, SQLite AUTOINCREMENT, Oracle 12c+/PostgreSQL/SQL Server IDENTITY,
     * 시퀀스 기본값 (PostgreSQL DEFAULT nextval('seq'), Oracle DEFAULT seq.NEXTVAL)
     */
    private static final Pattern AUTO = Pattern.compile(
            "\\b(AUTO_INCREMENT|AUTOINCREMENT|IDENTITY|NEXTVAL)\\b", Pattern.CASE_INSENSITIVE);
    /** PostgreSQL SERIAL 계열 (SMALLSERIAL, SERIAL, BIGSERIAL, SERIAL2/4/8) */
    private static final Pattern SERIAL = Pattern.compile("(SMALL|BIG)?SERIAL[248]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONSTRAINT_START = Pattern.compile(
            "^(CONSTRAINT|PRIMARY|UNIQUE|KEY|INDEX|FOREIGN|CHECK|FULLTEXT|SPATIAL)\\b", Pattern.CASE_INSENSITIVE);

    private DdlParser() {
    }

    static Table parse(String ddl) {
        if (ddl == null || ddl.isBlank()) {
            throw new IllegalArgumentException("Body 가 비어 있습니다. raw → Text 에 CREATE TABLE 문을 붙여넣어 주세요.");
        }
        String sql = stripComments(ddl);

        Matcher m = CREATE_TABLE.matcher(sql);
        if (!m.find()) {
            throw new IllegalArgumentException("CREATE TABLE 문을 찾을 수 없습니다.");
        }
        String tableName = lastPart(unquote(m.group(1)));
        String body = extractParenBody(sql, m.end() - 1);

        List<String[]> rawColumns = new ArrayList<>(); // [name, type, rest]
        Set<String> pkNames = new LinkedHashSet<>();

        for (String def : splitTopLevel(body)) {
            String d = def.trim();
            if (d.isEmpty()) {
                continue;
            }
            if (CONSTRAINT_START.matcher(d).find()) {
                Matcher pk = PRIMARY_KEY.matcher(d);
                if (pk.find()) {
                    for (String c : pk.group(1).split(",")) {
                        pkNames.add(unquote(c.trim()).toLowerCase(Locale.ROOT));
                    }
                }
                continue;
            }
            Matcher cm = COLUMN.matcher(d);
            if (!cm.matches()) {
                throw new IllegalArgumentException("컬럼 정의를 해석할 수 없습니다: " + d);
            }
            String name = unquote(cm.group(1));
            String rest = cm.group(3);
            if (INLINE_PK.matcher(rest).find()) {
                pkNames.add(name.toLowerCase(Locale.ROOT));
            }
            rawColumns.add(new String[]{name, cm.group(2).trim(), rest});
        }

        Map<String, String> commentsOn = commentOnColumns(sql, tableName);

        List<Column> columns = new ArrayList<>();
        for (String[] c : rawColumns) {
            String type = c[1];
            String rest = c[2];
            String key = c[0].toLowerCase(Locale.ROOT);
            boolean pk = pkNames.contains(key);
            boolean serial = SERIAL.matcher(type).matches();
            Matcher inline = COMMENT.matcher(rest);
            String comment = inline.find() ? inline.group(1).replace("''", "'") : commentsOn.get(key);
            columns.add(new Column(
                    c[0],
                    type,
                    pk,
                    !(pk || NOT_NULL.matcher(rest).find()),
                    serial || AUTO.matcher(rest).find(),
                    comment));
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("컬럼을 하나도 찾지 못했습니다.");
        }
        return new Table(tableName, columns);
    }

    /** COMMENT ON COLUMN 문에서 이 테이블의 컬럼 주석만 모은다. (컬럼명 소문자 → 주석) */
    private static Map<String, String> commentOnColumns(String sql, String tableName) {
        Map<String, String> comments = new HashMap<>();
        Matcher m = COMMENT_ON_COLUMN.matcher(sql);
        while (m.find()) {
            String[] parts = unquote(m.group(1)).split("\\.");
            if (parts.length >= 2 && parts[parts.length - 2].equalsIgnoreCase(tableName)) {
                comments.put(parts[parts.length - 1].toLowerCase(Locale.ROOT), m.group(2).replace("''", "'"));
            }
        }
        return comments;
    }

    /** SQL 주석(한 줄 --, 블록) 제거 */
    private static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--[^\\n]*", " ");
    }

    /** openIdx 위치의 '(' 와 짝이 맞는 ')' 사이 내용 */
    private static String extractParenBody(String sql, int openIdx) {
        int depth = 0;
        boolean inQuote = false;
        for (int i = openIdx; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote && ch == '(') {
                depth++;
            } else if (!inQuote && ch == ')') {
                depth--;
                if (depth == 0) {
                    return sql.substring(openIdx + 1, i);
                }
            }
        }
        throw new IllegalArgumentException("CREATE TABLE 의 괄호가 닫히지 않았습니다.");
    }

    /** 괄호/따옴표 밖의 쉼표로만 나눈다. (DECIMAL(10,2), COMMENT '가,나' 보호) */
    private static List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inQuote = false;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote) {
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                } else if (ch == ',' && depth == 0) {
                    parts.add(body.substring(start, i));
                    start = i + 1;
                }
            }
        }
        parts.add(body.substring(start));
        return parts;
    }

    private static String lastPart(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }

    private static String unquote(String s) {
        return s.replaceAll("[`\"\\[\\]]", "");
    }
}
