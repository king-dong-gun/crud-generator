package com.example.demo.util.crudgen;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DB 타입 문자열 → 자바 타입.
 * MySQL/MariaDB, Oracle, PostgreSQL, H2, SQLite, SQL Server 에서 자주 쓰는 타입을 다룬다.
 */
final class TypeMapper {

    /**
     * VARCHAR(50), DECIMAL(10, 2), INT UNSIGNED, DOUBLE PRECISION,
     * VARCHAR2(200 BYTE), NUMBER(*,0), TIMESTAMP(6) WITH TIME ZONE ...
     */
    private static final Pattern TYPE = Pattern.compile(
            "^\\s*([A-Za-z][A-Za-z0-9_ ]*?)\\s*"
                    + "(?:\\(\\s*(\\d+|\\*)\\s*(?:BYTE|CHAR)?\\s*(?:,\\s*(-?\\d+)\\s*)?\\))?\\s*"
                    + "(UNSIGNED|WITH\\s+LOCAL\\s+TIME\\s+ZONE|WITH\\s+TIME\\s+ZONE|WITHOUT\\s+TIME\\s+ZONE)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** MySQL ENUM('A','B') / SET('A','B') : 괄호 안이 숫자가 아니라 값 목록이라 따로 처리한다. */
    private static final Pattern VALUE_LIST_TYPE = Pattern.compile(
            "^\\s*(ENUM|SET)\\s*\\(.*\\)\\s*$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private TypeMapper() {
    }

    /**
     * DB 타입을 이름/크기/소수 자리로 쪼갠 결과
     *
     * @param withTimeZone TIMESTAMP/TIME ... WITH TIME ZONE (WITH LOCAL TIME ZONE 은 false)
     */
    record SqlType(String name, Integer size, Integer scale, boolean unsigned, boolean withTimeZone) {
    }

    /**
     * @param simpleName 코드에 쓰는 이름 (String, Long, BigDecimal ...)
     * @param importName import 가 필요하면 FQCN, 아니면 null
     */
    record JavaType(String simpleName, String importName) {
        boolean isString() {
            return "String".equals(simpleName);
        }
    }

    static SqlType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("컬럼 타입이 비어 있습니다.");
        }
        if (raw.contains("[")) {
            throw new IllegalArgumentException("배열 타입은 지원하지 않습니다: " + raw.trim());
        }
        Matcher valueList = VALUE_LIST_TYPE.matcher(raw);
        if (valueList.matches()) {
            return new SqlType(valueList.group(1).toUpperCase(Locale.ROOT), null, null, false, false);
        }
        Matcher m = TYPE.matcher(raw);
        if (!m.matches()) {
            throw new IllegalArgumentException("타입 '" + raw + "' 을(를) 해석할 수 없습니다.");
        }
        String name = m.group(1).trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        // NUMBER(*,0) 의 * 는 "최대 자릿수" → 크기 없음으로 본다.
        Integer size = m.group(2) == null || "*".equals(m.group(2)) ? null : Integer.valueOf(m.group(2));
        Integer scale = m.group(3) == null ? null : Integer.valueOf(m.group(3));
        String suffix = m.group(4) == null ? "" : m.group(4).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        return new SqlType(name, size, scale, suffix.equals("UNSIGNED"), suffix.equals("WITH TIME ZONE"));
    }

    static JavaType toJava(SqlType t) {
        String n = t.name();
        if (n.startsWith("TIMESTAMP")) { // TIMESTAMP, TIMESTAMPTZ
            return n.equals("TIMESTAMPTZ") || t.withTimeZone()
                    ? type("OffsetDateTime", "java.time.OffsetDateTime")
                    : type("LocalDateTime", "java.time.LocalDateTime");
        }
        switch (n) {
            case "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2", "CHAR", "NCHAR", "CHARACTER", "CHARACTER VARYING",
                 "BPCHAR", "CITEXT", "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "NTEXT", "CLOB", "NCLOB",
                 "JSON", "JSONB", "ENUM", "SET":
                return type("String", null);
            case "UUID", "UNIQUEIDENTIFIER":
                return type("UUID", "java.util.UUID");
            case "TINYINT":
                return Integer.valueOf(1).equals(t.size()) ? type("Boolean", null) : type("Integer", null);
            case "SMALLINT", "MEDIUMINT", "YEAR", "INT2", "INT4", "SMALLSERIAL", "SERIAL2", "SERIAL4":
                return type("Integer", null);
            case "INT", "INTEGER":
                return t.unsigned() ? type("Long", null) : type("Integer", null);
            case "BIGINT", "INT8", "SERIAL", "SERIAL8", "BIGSERIAL":
                return t.unsigned() ? type("BigInteger", "java.math.BigInteger") : type("Long", null);
            case "BIT", "BOOLEAN", "BOOL":
                return type("Boolean", null);
            case "DECIMAL", "NUMERIC", "MONEY":
                return type("BigDecimal", "java.math.BigDecimal");
            case "NUMBER":
                return oracleNumber(t);
            // FLOAT(p): p 가 24 를 넘으면 배정밀도 (MySQL, PostgreSQL 규칙. Oracle FLOAT(126) 도 Double 로)
            case "FLOAT":
                return t.size() != null && t.size() > 24 ? type("Double", null) : type("Float", null);
            case "REAL", "FLOAT4", "BINARY_FLOAT":
                return type("Float", null);
            case "DOUBLE", "DOUBLE PRECISION", "FLOAT8", "BINARY_DOUBLE":
                return type("Double", null);
            case "DATE":
                return type("LocalDate", "java.time.LocalDate");
            case "DATETIME", "DATETIME2", "SMALLDATETIME":
                return type("LocalDateTime", "java.time.LocalDateTime");
            case "DATETIMEOFFSET":
                return type("OffsetDateTime", "java.time.OffsetDateTime");
            case "TIME":
                return t.withTimeZone()
                        ? type("OffsetTime", "java.time.OffsetTime") : type("LocalTime", "java.time.LocalTime");
            case "TIMETZ":
                return type("OffsetTime", "java.time.OffsetTime");
            case "BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB", "BINARY", "VARBINARY", "BYTEA", "RAW":
                return type("byte[]", null);
            default:
                throw new IllegalArgumentException("지원하지 않는 DB 타입입니다: " + n);
        }
    }

    /** JSON 컬럼은 엔티티에 @JdbcTypeCode(SqlTypes.JSON) 이 필요하다. (PostgreSQL jsonb 에 문자열을 그냥 넣으면 에러) */
    static boolean isJson(SqlType t) {
        return "JSON".equals(t.name()) || "JSONB".equals(t.name());
    }

    /** Oracle NUMBER(p, s): 소수점이 있으면 BigDecimal, 자릿수에 따라 Integer/Long */
    private static JavaType oracleNumber(SqlType t) {
        if (t.scale() != null && t.scale() > 0) {
            return type("BigDecimal", "java.math.BigDecimal");
        }
        if (t.size() == null || t.size() > 18) {
            return t.size() == null ? type("Long", null) : type("BigDecimal", "java.math.BigDecimal");
        }
        return t.size() <= 9 ? type("Integer", null) : type("Long", null);
    }

    private static JavaType type(String simple, String importName) {
        return new JavaType(simple, importName);
    }
}
