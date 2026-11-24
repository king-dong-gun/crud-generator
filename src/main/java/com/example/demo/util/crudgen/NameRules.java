package com.example.demo.util.crudgen;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * DB 이름(snake_case, 대문자) → 자바 이름(camelCase, PascalCase) 변환과,
 * 생성은 되지만 컴파일/실행에서 터지는 이름을 미리 거르는 검사. (위반 시 IllegalArgumentException → 400)
 *
 * <ol>
 *   <li>엔티티 클래스명이 생성 코드가 쓰는 클래스명과 같음 → 컴파일 에러 (service → Service ↔ @Service)</li>
 *   <li>생성 코드 안의 변수명이 겹침 → 컴파일 에러 (member 테이블의 PK 가 member)</li>
 *   <li>테이블/컬럼명이 SQL 예약어 → JPA 쿼리 실행 시 문법 에러 (order, group, desc ...)</li>
 * </ol>
 */
final class NameRules {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "record", "var", "yield");

    /**
     * 엔티티 클래스를 import 하는 파일(Entity, DTO, Repository, Service)에서 쓰는 클래스명.
     * 엔티티 클래스가 이 이름이면 import 가 겹치거나 java.lang 타입이 가려져 컴파일되지 않는다.
     * (Controller 는 엔티티를 import 하지 않으므로 Valid, ResponseEntity 등은 충돌하지 않는다.)
     * CodeGenerator 에 import 를 추가하면 여기도 추가할 것.
     */
    private static final Set<String> CLASS_NAMES_USED_WITH_ENTITY = Set.of(
            // Entity
            "Entity", "Table", "Id", "Column", "GeneratedValue", "GenerationType",
            "Getter", "NoArgsConstructor", "AccessLevel", "JdbcTypeCode", "SqlTypes",
            // DTO 검증
            "NotBlank", "NotNull", "Size",
            // Repository, Service
            "JpaRepository", "Service", "Transactional", "HttpStatus", "ResponseStatusException",
            "RequiredArgsConstructor", "List",
            // 필드 타입 (컬럼 구성에 따라 쓰이므로 항상 막는다)
            "String", "Integer", "Long", "Boolean", "Float", "Double",
            "BigDecimal", "BigInteger", "LocalDate", "LocalDateTime", "LocalTime",
            "OffsetDateTime", "OffsetTime", "UUID");

    /** 생성 코드(Service.update)가 쓰는 파라미터명. 엔티티 변수명이나 PK 필드명과 겹치면 안 된다. */
    private static final String REQUEST_PARAM = "request";

    /**
     * MySQL, PostgreSQL, Oracle 세 DB 모두에서 예약어인 단어.
     * 셋 중 하나에서만 예약어인 단어(user, comment, key ...)는 다른 DB 에서는 정상이므로 막지 않는다.
     */
    private static final Set<String> SQL_RESERVED = Set.of(
            "ALL", "AND", "AS", "ASC", "CHECK", "COLUMN", "CREATE", "DEFAULT", "DESC", "DISTINCT",
            "ELSE", "FOR", "FROM", "GRANT", "GROUP", "HAVING", "IN", "INTO", "IS", "LIKE",
            "NOT", "NULL", "ON", "OR", "ORDER", "SELECT", "TABLE", "THEN", "TO", "UNION",
            "UNIQUE", "WHERE", "WITH");

    private NameRules() {
    }

    // ------------------------------------------------------------ 이름 변환

    static void requireIdentifier(String name, String what) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException(what + " '" + name + "' 은(는) 영문, 숫자, _ 만 쓸 수 있고 숫자로 시작할 수 없습니다.");
        }
    }

    /** user_name, USER_NAME, userName → userName */
    static String toCamel(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.split("_+")) {
            if (part.isEmpty()) {
                continue;
            }
            String p = part.equals(part.toUpperCase(Locale.ROOT)) ? part.toLowerCase(Locale.ROOT) : part;
            if (sb.length() == 0) {
                sb.append(Character.toLowerCase(p.charAt(0))).append(p.substring(1));
            } else {
                sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            }
        }
        if (sb.length() == 0) {
            throw new IllegalArgumentException("이름 '" + name + "' 을(를) 변환할 수 없습니다.");
        }
        return sb.toString();
    }

    /** 자바 예약어와 겹치면 뒤에 Value 를 붙인다. (class → classValue) */
    static String toFieldName(String column) {
        String camel = toCamel(column);
        return JAVA_KEYWORDS.contains(camel) ? camel + "Value" : camel;
    }

    /** member_order → MemberOrder */
    static String toPascal(String name) {
        String camel = toCamel(name);
        return Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
    }

    /** member_order → memberorder (자바 패키지명) */
    static String toPackageSegment(String name) {
        String seg = toCamel(name).toLowerCase(Locale.ROOT);
        return JAVA_KEYWORDS.contains(seg) ? seg + "s" : seg;
    }

    /** member_order → member-orders (REST 경로) */
    static String toResourcePath(String name) {
        String kebab = toCamel(name).replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
        return pluralize(kebab);
    }

    /** member → members, category → categories, box → boxes, orders → orders (이미 s 로 끝나면 그대로) */
    private static String pluralize(String word) {
        if (word.matches(".*(ss|x|z|ch|sh)$")) {
            return word + "es";
        }
        if (word.endsWith("s")) {
            return word;
        }
        if (word.matches(".*[^aeiou]y$")) {
            return word.substring(0, word.length() - 1) + "ies";
        }
        return word + "s";
    }

    // ------------------------------------------------------------ 충돌 검사

    /** 테이블명이 SQL 예약어인지 */
    static void checkTableReserved(String tableName) {
        if (isSqlReserved(tableName)) {
            throw new IllegalArgumentException(String.format(
                    "테이블명 '%s' 은(는) SQL 예약어라 JPA 쿼리 실행 시 문법 에러가 납니다. 테이블명을 바꿔주세요. (예: %s)",
                    tableName, suggestion(tableName)));
        }
    }

    /** 컬럼명이 SQL 예약어인지 */
    static void checkColumnReserved(String tableName, String column) {
        if (isSqlReserved(column)) {
            throw new IllegalArgumentException(String.format(
                    "컬럼명 '%s' 은(는) SQL 예약어라 JPA 쿼리 실행 시 문법 에러가 납니다. 컬럼명을 바꿔주세요. (예: %s)",
                    column, sameCase(column, tableName + "_" + column)));
        }
    }

    /** 엔티티 클래스명이 생성 코드가 쓰는 클래스명과 겹치는지 */
    static void checkClassName(String className, String tableName) {
        if (CLASS_NAMES_USED_WITH_ENTITY.contains(className)) {
            throw new IllegalArgumentException(String.format(
                    "테이블명 '%s' 로 만든 클래스 %s 가 생성 코드에서 쓰는 %s 와 이름이 같아 컴파일되지 않습니다. "
                            + "테이블명을 바꿔주세요. (예: %s)",
                    tableName, className, className, suggestion(tableName)));
        }
    }

    /** 생성 코드 안의 변수명이 겹치는지 (Service.update 의 파라미터/지역 변수) */
    static void checkVariables(String tableName, String varName, String pkColumn, String pkField) {
        if (pkField.equals(varName)) {
            throw new IllegalArgumentException(String.format(
                    "PK 컬럼명 '%s' 이(가) 테이블명 '%s' 과(와) 같아 생성 코드의 변수명(%s)이 겹칩니다. "
                            + "PK 컬럼명을 바꿔주세요. (예: %s)",
                    pkColumn, tableName, varName, sameCase(pkColumn, pkColumn + "_id")));
        }
        if (REQUEST_PARAM.equals(varName)) {
            throw new IllegalArgumentException(String.format(
                    "테이블명 '%s' 은(는) 생성 코드의 파라미터명 request 와 겹쳐 컴파일되지 않습니다. "
                            + "테이블명을 바꿔주세요. (예: %s)",
                    tableName, suggestion(tableName)));
        }
        if (REQUEST_PARAM.equals(pkField)) {
            throw new IllegalArgumentException(String.format(
                    "PK 컬럼명 '%s' 은(는) 생성 코드의 파라미터명 request 와 겹쳐 컴파일되지 않습니다. "
                            + "PK 컬럼명을 바꿔주세요. (예: %s)",
                    pkColumn, sameCase(pkColumn, pkColumn + "_id")));
        }
    }

    private static boolean isSqlReserved(String dbName) {
        return SQL_RESERVED.contains(dbName.toUpperCase(Locale.ROOT));
    }

    /**
     * 테이블명 대안: order → tb_order, order_info.
     * (복수형은 group → groups 처럼 다른 DB 의 예약어가 될 수 있어 쓰지 않는다)
     */
    private static String suggestion(String tableName) {
        return sameCase(tableName, "tb_" + tableName) + ", " + sameCase(tableName, tableName + "_info");
    }

    /** 원래 이름이 대문자(Oracle 스타일)면 제안도 대문자로 */
    private static String sameCase(String original, String suggested) {
        return original.equals(original.toUpperCase(Locale.ROOT))
                ? suggested.toUpperCase(Locale.ROOT)
                : suggested.toLowerCase(Locale.ROOT);
    }
}
