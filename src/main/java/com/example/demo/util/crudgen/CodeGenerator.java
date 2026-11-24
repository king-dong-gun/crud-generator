package com.example.demo.util.crudgen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 테이블 정보 → CRUD 코드 7개.
 *
 * <pre>
 * {basePackage}/{테이블}/
 * ├─ controller/{Class}Controller.java
 * ├─ dto/{Class}CreateRequest.java
 * ├─ dto/{Class}UpdateRequest.java
 * ├─ dto/{Class}Response.java
 * ├─ entity/{Class}.java
 * ├─ repository/{Class}Repository.java
 * └─ service/{Class}Service.java
 * </pre>
 *
 * 생성 코드의 모양을 바꾸려면 아래 entity(), service() ... 메서드를 고친다.
 */
final class CodeGenerator {

    /**
     * 생성된 파일 하나.
     *
     * @param path    프로젝트 루트 기준 경로 (예: src/main/java/com/example/cafe/member/entity/Member.java)
     * @param content 파일 내용
     */
    record GeneratedFile(String path, String content) {
    }

    /** 컬럼 하나에서 만든 자바 필드 */
    private record Field(
            String column,
            String name,
            String getter,
            String type,
            String importName,
            List<String> annotations,
            List<String> validations) {

        /** DTO record 컴포넌트 선언 (검증 애너테이션 포함) */
        String declaration() {
            return validations.isEmpty() ? type + " " + name : String.join(" ", validations) + " " + type + " " + name;
        }
    }

    /** 생성에 필요한 모든 값 */
    private record Model(
            boolean lombok,
            String pkg,
            String tableName,
            String className,
            String varName,
            String path,
            Field pk,
            boolean autoId,
            boolean hasJson,
            List<Field> fields,
            List<Field> createFields,
            List<Field> updateFields) {
    }

    private static final Pattern PACKAGE = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");

    private CodeGenerator() {
    }

    /**
     * @param lombok true 면 @Getter/@RequiredArgsConstructor 사용, false 면 생성자·getter 를 직접 생성
     */
    static List<GeneratedFile> generate(String basePackage, DdlParser.Table table, boolean lombok) {
        Model m = buildModel(basePackage, table, lombok);
        String dir = "src/main/java/" + m.pkg().replace('.', '/') + "/";
        String c = m.className();
        return List.of(
                new GeneratedFile(dir + "entity/" + c + ".java", entity(m)),
                new GeneratedFile(dir + "dto/" + c + "CreateRequest.java", createRequest(m)),
                new GeneratedFile(dir + "dto/" + c + "UpdateRequest.java", updateRequest(m)),
                new GeneratedFile(dir + "dto/" + c + "Response.java", response(m)),
                new GeneratedFile(dir + "repository/" + c + "Repository.java", repository(m)),
                new GeneratedFile(dir + "service/" + c + "Service.java", service(m)),
                new GeneratedFile(dir + "controller/" + c + "Controller.java", controller(m)));
    }

    // ================================================================ model

    private static Model buildModel(String basePackage, DdlParser.Table table, boolean lombok) {
        if (basePackage == null || !PACKAGE.matcher(basePackage).matches()) {
            throw new IllegalArgumentException("basePackage '" + basePackage + "' 형식이 올바르지 않습니다. 예: com.example.cafe");
        }
        String tableName = table.tableName();
        NameRules.requireIdentifier(tableName, "테이블명");
        List<DdlParser.Column> columns = table.columns();

        List<DdlParser.Column> pks = columns.stream().filter(DdlParser.Column::pk).toList();
        if (pks.isEmpty()) {
            throw new IllegalArgumentException("PK 컬럼이 없습니다. PRIMARY KEY 를 지정해주세요.");
        }
        if (pks.size() > 1) {
            throw new IllegalArgumentException("복합 PK는 아직 지원하지 않습니다. (" + pks.size() + "개 지정됨)");
        }
        if (columns.size() < 2) {
            throw new IllegalArgumentException("PK 외에 컬럼이 최소 1개 필요합니다.");
        }

        NameRules.checkTableReserved(tableName);
        String className = NameRules.toPascal(tableName);
        String varName = NameRules.toFieldName(tableName);
        NameRules.checkClassName(className, tableName);
        String pkg = basePackage + "." + NameRules.toPackageSegment(tableName);

        List<Field> fields = new ArrayList<>();
        List<Field> createFields = new ArrayList<>();
        List<Field> updateFields = new ArrayList<>();
        Set<String> fieldNames = new HashSet<>();
        Field pk = null;
        boolean autoId = false;
        boolean hasJson = false;

        for (DdlParser.Column col : columns) {
            NameRules.requireIdentifier(col.name(), "컬럼명");
            NameRules.checkColumnReserved(tableName, col.name());
            TypeMapper.SqlType sqlType = TypeMapper.parse(col.type());
            TypeMapper.JavaType javaType = TypeMapper.toJava(sqlType);
            boolean json = TypeMapper.isJson(sqlType);
            hasJson |= json;
            Integer length = javaType.isString() ? sqlType.size() : null;

            String name = NameRules.toFieldName(col.name());
            if (!fieldNames.add(name)) {
                throw new IllegalArgumentException("필드명이 겹칩니다: " + name + " (컬럼 " + col.name() + ")");
            }
            Field f = new Field(
                    col.name(),
                    name,
                    "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1),
                    javaType.simpleName(),
                    javaType.importName(),
                    entityAnnotations(col, javaType, sqlType, length, json),
                    validations(col, javaType, length));
            fields.add(f);

            if (col.pk()) {
                pk = f;
                autoId = col.autoIncrement();
                if (!autoId) {
                    createFields.add(f);
                }
            } else {
                createFields.add(f);
                updateFields.add(f);
            }
        }

        NameRules.checkVariables(tableName, varName, pks.get(0).name(), pk.name());

        return new Model(lombok, pkg, tableName, className, varName,
                "/api/" + NameRules.toResourcePath(tableName),
                pk, autoId, hasJson, fields, createFields, updateFields);
    }

    private static List<String> entityAnnotations(DdlParser.Column col, TypeMapper.JavaType javaType,
                                                  TypeMapper.SqlType sqlType, Integer length, boolean json) {
        List<String> lines = new ArrayList<>();
        if (col.comment() != null && !col.comment().isBlank()) {
            lines.add("/** " + col.comment().replace("*/", "* /") + " */");
        }
        if (col.pk()) {
            lines.add("@Id");
            if (col.autoIncrement()) {
                lines.add("@GeneratedValue(strategy = GenerationType.IDENTITY)");
            }
        }
        if (json) {
            lines.add("@JdbcTypeCode(SqlTypes.JSON)");
        }
        StringBuilder column = new StringBuilder("@Column(name = \"").append(col.name()).append('"');
        if (!col.pk() && !col.nullable()) {
            column.append(", nullable = false");
        }
        if (javaType.isString() && length != null) {
            column.append(", length = ").append(length);
        }
        if ("BigDecimal".equals(javaType.simpleName()) && sqlType.size() != null) {
            column.append(", precision = ").append(sqlType.size());
            if (sqlType.scale() != null) {
                column.append(", scale = ").append(sqlType.scale());
            }
        }
        lines.add(column.append(')').toString());
        return lines;
    }

    /** 요청 DTO 에 붙일 Bean Validation */
    private static List<String> validations(DdlParser.Column col, TypeMapper.JavaType javaType, Integer length) {
        List<String> v = new ArrayList<>();
        if (col.pk() || !col.nullable()) {
            v.add(javaType.isString() ? "@NotBlank" : "@NotNull");
        }
        if (javaType.isString() && length != null) {
            v.add("@Size(max = " + length + ")");
        }
        return v;
    }

    // ================================================================ files

    private static String entity(Model m) {
        Set<String> imports = new TreeSet<>(List.of(
                "jakarta.persistence.Column", "jakarta.persistence.Entity", "jakarta.persistence.Id",
                "jakarta.persistence.Table"));
        if (m.lombok()) {
            imports.addAll(List.of("lombok.AccessLevel", "lombok.Getter", "lombok.NoArgsConstructor"));
        }
        if (m.autoId()) {
            imports.addAll(List.of("jakarta.persistence.GeneratedValue", "jakarta.persistence.GenerationType"));
        }
        if (m.hasJson()) {
            imports.addAll(List.of("org.hibernate.annotations.JdbcTypeCode", "org.hibernate.type.SqlTypes"));
        }
        imports.addAll(typeImports(m.fields()));

        Src s = header(m.pkg() + ".entity", imports);
        s.line("@Entity");
        s.line("@Table(name = \"" + m.tableName() + "\")");
        if (m.lombok()) {
            s.line("@Getter");
            s.line("@NoArgsConstructor(access = AccessLevel.PROTECTED)");
        }
        s.line("public class " + m.className() + " {");
        for (Field f : m.fields()) {
            s.line();
            f.annotations().forEach(a -> s.line("    " + a));
            s.line("    private " + f.type() + " " + f.name() + ";");
        }
        s.line();
        if (!m.lombok()) {
            s.line("    protected " + m.className() + "() {");
            s.line("    }");
            s.line();
        }
        s.line("    public " + m.className() + "(" + params(m.createFields()) + ") {");
        m.createFields().forEach(f -> s.line("        this." + f.name() + " = " + f.name() + ";"));
        s.line("    }");
        s.line();
        s.line("    public void update(" + params(m.updateFields()) + ") {");
        m.updateFields().forEach(f -> s.line("        this." + f.name() + " = " + f.name() + ";"));
        s.line("    }");
        if (!m.lombok()) {
            for (Field f : m.fields()) {
                s.line();
                s.line("    public " + f.type() + " " + f.getter() + "() {");
                s.line("        return " + f.name() + ";");
                s.line("    }");
            }
        }
        s.line("}");
        return s.toString();
    }

    private static String createRequest(Model m) {
        Set<String> imports = new TreeSet<>(validationImports(m.createFields()));
        imports.addAll(typeImports(m.createFields()));
        imports.add(entityImport(m));

        Src s = header(m.pkg() + ".dto", imports);
        s.line("public record " + m.className() + "CreateRequest(");
        s.lines(m.createFields(), f -> "        " + f.declaration(), ",");
        s.line(") {");
        s.line();
        s.line("    public " + m.className() + " toEntity() {");
        s.line("        return new " + m.className() + "(" + join(m.createFields(), Field::name) + ");");
        s.line("    }");
        s.line("}");
        return s.toString();
    }

    private static String updateRequest(Model m) {
        Set<String> imports = new TreeSet<>(validationImports(m.updateFields()));
        imports.addAll(typeImports(m.updateFields()));

        Src s = header(m.pkg() + ".dto", imports);
        s.line("public record " + m.className() + "UpdateRequest(");
        s.lines(m.updateFields(), f -> "        " + f.declaration(), ",");
        s.line(") {");
        s.line("}");
        return s.toString();
    }

    private static String response(Model m) {
        Set<String> imports = new TreeSet<>(typeImports(m.fields()));
        imports.add(entityImport(m));

        Src s = header(m.pkg() + ".dto", imports);
        s.line("public record " + m.className() + "Response(");
        s.lines(m.fields(), f -> "        " + f.type() + " " + f.name(), ",");
        s.line(") {");
        s.line();
        s.line("    public static " + m.className() + "Response from(" + m.className() + " " + m.varName() + ") {");
        s.line("        return new " + m.className() + "Response(");
        s.lines(m.fields(), f -> "                " + m.varName() + "." + f.getter() + "()", ",");
        s.line("        );");
        s.line("    }");
        s.line("}");
        return s.toString();
    }

    private static String repository(Model m) {
        Set<String> imports = new TreeSet<>(typeImports(List.of(m.pk())));
        imports.add(entityImport(m));
        imports.add("org.springframework.data.jpa.repository.JpaRepository");

        Src s = header(m.pkg() + ".repository", imports);
        s.line("public interface " + m.className() + "Repository extends JpaRepository<"
                + m.className() + ", " + m.pk().type() + "> {");
        s.line("}");
        return s.toString();
    }

    private static String service(Model m) {
        String c = m.className();
        String repo = m.varName() + "Repository";
        String id = m.pk().name();
        String idParam = m.pk().type() + " " + id;

        Set<String> imports = new TreeSet<>(typeImports(List.of(m.pk())));
        imports.addAll(List.of(
                m.pkg() + ".dto." + c + "CreateRequest",
                m.pkg() + ".dto." + c + "Response",
                m.pkg() + ".dto." + c + "UpdateRequest",
                entityImport(m),
                m.pkg() + ".repository." + c + "Repository",
                "java.util.List",
                "org.springframework.http.HttpStatus",
                "org.springframework.stereotype.Service",
                "org.springframework.transaction.annotation.Transactional",
                "org.springframework.web.server.ResponseStatusException"));
        if (m.lombok()) {
            imports.add("lombok.RequiredArgsConstructor");
        }

        Src s = header(m.pkg() + ".service", imports);
        s.line("@Service");
        if (m.lombok()) {
            s.line("@RequiredArgsConstructor");
        }
        s.line("@Transactional(readOnly = true)");
        s.line("public class " + c + "Service {");
        s.line();
        s.line("    private final " + c + "Repository " + repo + ";");
        if (!m.lombok()) {
            s.line();
            s.line("    public " + c + "Service(" + c + "Repository " + repo + ") {");
            s.line("        this." + repo + " = " + repo + ";");
            s.line("    }");
        }
        s.line();
        s.line("    @Transactional");
        s.line("    public " + c + "Response create(" + c + "CreateRequest request) {");
        s.line("        " + c + " saved = " + repo + ".save(request.toEntity());");
        s.line("        return " + c + "Response.from(saved);");
        s.line("    }");
        s.line();
        s.line("    public List<" + c + "Response> findAll() {");
        s.line("        return " + repo + ".findAll().stream()");
        s.line("                .map(" + c + "Response::from)");
        s.line("                .toList();");
        s.line("    }");
        s.line();
        s.line("    public " + c + "Response findById(" + idParam + ") {");
        s.line("        return " + c + "Response.from(get" + c + "(" + id + "));");
        s.line("    }");
        s.line();
        s.line("    @Transactional");
        s.line("    public " + c + "Response update(" + idParam + ", " + c + "UpdateRequest request) {");
        s.line("        " + c + " " + m.varName() + " = get" + c + "(" + id + ");");
        s.line("        " + m.varName() + ".update(" + join(m.updateFields(), f -> "request." + f.name() + "()") + ");");
        s.line("        return " + c + "Response.from(" + m.varName() + ");");
        s.line("    }");
        s.line();
        s.line("    @Transactional");
        s.line("    public void delete(" + idParam + ") {");
        s.line("        " + repo + ".delete(get" + c + "(" + id + "));");
        s.line("    }");
        s.line();
        s.line("    private " + c + " get" + c + "(" + idParam + ") {");
        s.line("        return " + repo + ".findById(" + id + ")");
        s.line("                .orElseThrow(() -> new ResponseStatusException(");
        s.line("                        HttpStatus.NOT_FOUND, \"" + c + "을(를) 찾을 수 없습니다. " + id + "=\" + " + id + "));");
        s.line("    }");
        s.line("}");
        return s.toString();
    }

    private static String controller(Model m) {
        String c = m.className();
        String service = m.varName() + "Service";
        String idType = m.pk().type();

        Set<String> imports = new TreeSet<>(typeImports(List.of(m.pk())));
        imports.addAll(List.of(
                m.pkg() + ".dto." + c + "CreateRequest",
                m.pkg() + ".dto." + c + "Response",
                m.pkg() + ".dto." + c + "UpdateRequest",
                m.pkg() + ".service." + c + "Service",
                "jakarta.validation.Valid",
                "java.net.URI",
                "java.util.List",
                "org.springframework.http.ResponseEntity",
                "org.springframework.web.bind.annotation.DeleteMapping",
                "org.springframework.web.bind.annotation.GetMapping",
                "org.springframework.web.bind.annotation.PathVariable",
                "org.springframework.web.bind.annotation.PostMapping",
                "org.springframework.web.bind.annotation.PutMapping",
                "org.springframework.web.bind.annotation.RequestBody",
                "org.springframework.web.bind.annotation.RequestMapping",
                "org.springframework.web.bind.annotation.RestController"));
        if (m.lombok()) {
            imports.add("lombok.RequiredArgsConstructor");
        }

        Src s = header(m.pkg() + ".controller", imports);
        s.line("@RestController");
        s.line("@RequestMapping(\"" + m.path() + "\")");
        if (m.lombok()) {
            s.line("@RequiredArgsConstructor");
        }
        s.line("public class " + c + "Controller {");
        s.line();
        s.line("    private final " + c + "Service " + service + ";");
        if (!m.lombok()) {
            s.line();
            s.line("    public " + c + "Controller(" + c + "Service " + service + ") {");
            s.line("        this." + service + " = " + service + ";");
            s.line("    }");
        }
        s.line();
        s.line("    @PostMapping");
        s.line("    public ResponseEntity<" + c + "Response> create(@Valid @RequestBody " + c + "CreateRequest request) {");
        s.line("        " + c + "Response response = " + service + ".create(request);");
        s.line("        return ResponseEntity.created(URI.create(\"" + m.path() + "/\" + response."
                + m.pk().name() + "())).body(response);");
        s.line("    }");
        s.line();
        s.line("    @GetMapping");
        s.line("    public ResponseEntity<List<" + c + "Response>> findAll() {");
        s.line("        return ResponseEntity.ok(" + service + ".findAll());");
        s.line("    }");
        s.line();
        s.line("    @GetMapping(\"/{id}\")");
        s.line("    public ResponseEntity<" + c + "Response> findById(@PathVariable(\"id\") " + idType + " id) {");
        s.line("        return ResponseEntity.ok(" + service + ".findById(id));");
        s.line("    }");
        s.line();
        s.line("    @PutMapping(\"/{id}\")");
        s.line("    public ResponseEntity<" + c + "Response> update(");
        s.line("            @PathVariable(\"id\") " + idType + " id, @Valid @RequestBody " + c + "UpdateRequest request) {");
        s.line("        return ResponseEntity.ok(" + service + ".update(id, request));");
        s.line("    }");
        s.line();
        s.line("    @DeleteMapping(\"/{id}\")");
        s.line("    public ResponseEntity<Void> delete(@PathVariable(\"id\") " + idType + " id) {");
        s.line("        " + service + ".delete(id);");
        s.line("        return ResponseEntity.noContent().build();");
        s.line("    }");
        s.line("}");
        return s.toString();
    }

    // ================================================================ helpers

    /** package 선언 + import 블록 */
    private static Src header(String pkg, Set<String> imports) {
        Src s = new Src();
        s.line("package " + pkg + ";");
        s.line();
        if (!imports.isEmpty()) {
            imports.forEach(i -> s.line("import " + i + ";"));
            s.line();
        }
        return s;
    }

    private static String entityImport(Model m) {
        return m.pkg() + ".entity." + m.className();
    }

    private static Set<String> typeImports(List<Field> fields) {
        Set<String> imports = new TreeSet<>();
        for (Field f : fields) {
            if (f.importName() != null) {
                imports.add(f.importName());
            }
        }
        return imports;
    }

    private static Set<String> validationImports(List<Field> fields) {
        Set<String> imports = new TreeSet<>();
        for (Field f : fields) {
            for (String v : f.validations()) {
                imports.add("jakarta.validation.constraints." + v.replaceAll("^@(\\w+).*$", "$1"));
            }
        }
        return imports;
    }

    /** String name, Integer price */
    private static String params(List<Field> fields) {
        return join(fields, f -> f.type() + " " + f.name());
    }

    private static String join(List<Field> fields, Function<Field, String> each) {
        return String.join(", ", fields.stream().map(each).toList());
    }

    /** 줄 단위로 소스를 쌓는다. 줄바꿈은 \n 로 통일 */
    private static final class Src {
        private final StringBuilder sb = new StringBuilder();

        void line() {
            sb.append('\n');
        }

        void line(String text) {
            sb.append(text).append('\n');
        }

        /** 마지막 줄을 뺀 나머지 줄 끝에 separator 를 붙인다. */
        void lines(List<Field> fields, Function<Field, String> each, String separator) {
            for (int i = 0; i < fields.size(); i++) {
                line(each.apply(fields.get(i)) + (i < fields.size() - 1 ? separator : ""));
            }
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }
}
