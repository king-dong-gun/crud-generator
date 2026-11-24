package com.example.demo.util.crudgen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 생성된 코드를 실행 중인 내 프로젝트 폴더에 쓴다.
 *
 * <ul>
 *   <li>프로젝트 폴더, basePackage, lombok 사용 여부를 프로젝트에서 알아낸다.</li>
 *   <li>이미 있는 파일은 overwrite=true 가 아니면 하나도 쓰지 않는다. (먼저 전부 검사해서 일부만 써지는 일이 없게)</li>
 *   <li>빌드 파일에 필요한 의존성이 없으면 경고를 돌려준다.</li>
 * </ul>
 */
final class ProjectWriter {

    private static final List<String> BUILD_FILES = List.of("build.gradle", "build.gradle.kts", "pom.xml");
    /** 줄 맨 앞의 애너테이션만 본다. (주석·문자열 안의 글자는 무시) */
    private static final Pattern SPRING_BOOT_APP = Pattern.compile("(?m)^\\s*@SpringBootApplication\\b");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    private ProjectWriter() {
    }

    // ------------------------------------------------------------ 프로젝트 찾기

    /**
     * 실행 중인 애플리케이션의 프로젝트 폴더.
     * 컴파일된 클래스 위치(build/classes/java/main, target/classes, out/production ...)와
     * 작업 폴더에서 위로 올라가며 빌드 파일과 src/main/java 가 있는 폴더를 찾는다.
     */
    static Path findProjectRoot(Class<?> anchor) {
        List<Path> starts = new ArrayList<>();
        try {
            CodeSource source = anchor.getProtectionDomain().getCodeSource();
            if (source != null) {
                starts.add(Path.of(source.getLocation().toURI()));
            }
        } catch (Exception ignored) {
            // jar 로 실행하는 등 파일 경로가 아니면 작업 폴더로 찾는다
        }
        starts.add(Path.of(System.getProperty("user.dir")));

        for (Path start : starts) {
            for (Path dir = start.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
                if (isProject(dir)) {
                    return dir;
                }
            }
        }
        throw new IllegalArgumentException(
                "프로젝트 폴더를 찾지 못했습니다. projectPath 쿼리 파라미터에 build.gradle(또는 pom.xml)이 있는 "
                        + "폴더의 절대 경로를 / 로 구분해서 주세요. (예: C:/Users/me/IdeaProjects/cafe)");
    }

    /** projectPath 를 직접 줬을 때 검증: 절대 경로 + 존재하는 폴더 + 빌드 파일 */
    static Path resolveProject(String projectPath) {
        Path project = Path.of(projectPath.trim());
        if (!project.isAbsolute()) {
            throw new IllegalArgumentException("projectPath 는 절대 경로여야 합니다: " + projectPath);
        }
        project = project.normalize();
        if (!Files.isDirectory(project)) {
            throw new IllegalArgumentException("projectPath 폴더가 없습니다: " + project);
        }
        if (buildFile(project).isEmpty()) {
            throw new IllegalArgumentException(
                    "Spring 프로젝트 폴더가 아닌 것 같습니다. (build.gradle / pom.xml 이 없음): " + project
                            + " — build.gradle 이 있는 폴더를 지정하세요.");
        }
        return project;
    }

    private static boolean isProject(Path dir) {
        return buildFile(dir).isPresent() && Files.isDirectory(dir.resolve("src/main/java"));
    }

    // ------------------------------------------------------------ 설정 알아내기

    /** src/main/java 아래에서 @SpringBootApplication 클래스의 패키지를 찾는다. */
    static String detectBasePackage(Path project) {
        Path javaRoot = project.resolve("src/main/java");
        if (!Files.isDirectory(javaRoot)) {
            throw new IllegalArgumentException("src/main/java 폴더가 없습니다: " + javaRoot
                    + " — basePackage 를 직접 지정하세요.");
        }
        TreeSet<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(javaRoot)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String src = read(p);
                if (SPRING_BOOT_APP.matcher(src).find()) {
                    Matcher m = PACKAGE.matcher(src);
                    found.add(m.find() ? m.group(1) : "");
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (found.isEmpty()) {
            throw new IllegalArgumentException(
                    "@SpringBootApplication 클래스를 찾지 못했습니다. basePackage 를 직접 지정하세요.");
        }
        if (found.size() > 1) {
            throw new IllegalArgumentException(
                    "@SpringBootApplication 클래스가 여러 패키지에 있습니다 " + found + ". basePackage 를 직접 지정하세요.");
        }
        String pkg = found.first();
        if (pkg.isEmpty()) {
            throw new IllegalArgumentException(
                    "@SpringBootApplication 클래스가 기본 패키지(package 선언 없음)에 있습니다. basePackage 를 직접 지정하세요.");
        }
        return pkg;
    }

    /** 빌드 파일에 lombok 이 있으면 true */
    static boolean detectLombok(Path project) {
        return buildFileText(project).contains("lombok");
    }

    /** 생성된 코드가 컴파일·실행되려면 필요한 의존성이 빌드 파일에 있는지 */
    static List<String> dependencyWarnings(Path project, boolean lombok) {
        String build = buildFileText(project);
        String file = buildFile(project).map(p -> p.getFileName().toString()).orElse("빌드 파일");
        List<String> warnings = new ArrayList<>();
        // Boot 4 의 spring-boot-starter-webmvc 도 이 문자열을 포함한다
        check(build, "spring-boot-starter-web", file, "Controller", warnings);
        check(build, "spring-boot-starter-data-jpa", file, "Entity, Repository", warnings);
        check(build, "spring-boot-starter-validation", file, "DTO 의 @NotBlank, @Size 등", warnings);
        if (lombok) {
            check(build, "lombok", file, "@Getter, @RequiredArgsConstructor", warnings);
        }
        return warnings;
    }

    // ------------------------------------------------------------ 파일 쓰기

    /** 이미 있는 파일 목록 (프로젝트 기준 상대 경로) */
    static List<String> existingFiles(Path project, List<CodeGenerator.GeneratedFile> files) {
        List<String> existing = new ArrayList<>();
        for (CodeGenerator.GeneratedFile f : files) {
            if (Files.exists(target(project, f))) {
                existing.add(f.path());
            }
        }
        return existing;
    }

    /** 파일 쓰기. 쓴 파일의 상대 경로 목록을 돌려준다. */
    static List<String> write(Path project, List<CodeGenerator.GeneratedFile> files) {
        List<String> written = new ArrayList<>();
        for (CodeGenerator.GeneratedFile f : files) {
            Path target = target(project, f);
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, f.content(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("파일을 쓸 수 없습니다: " + target, e);
            }
            written.add(f.path());
        }
        return written;
    }

    // ------------------------------------------------------------ helpers

    /** 프로젝트 폴더 밖으로 나가는 경로는 거부 (생성 경로는 검증된 이름으로만 만들지만 한 번 더 확인) */
    private static Path target(Path project, CodeGenerator.GeneratedFile f) {
        Path target = project.resolve(f.path()).normalize();
        if (!target.startsWith(project)) {
            throw new IllegalArgumentException("프로젝트 폴더 밖에는 쓸 수 없습니다: " + f.path());
        }
        return target;
    }

    private static void check(String build, String artifact, String file, String usedBy, List<String> warnings) {
        if (!build.contains(artifact)) {
            warnings.add(String.format("%s 에 %s 의존성이 없습니다. (%s 에 필요)", file, artifact, usedBy));
        }
    }

    private static Optional<Path> buildFile(Path project) {
        return BUILD_FILES.stream().map(project::resolve).filter(Files::isRegularFile).findFirst();
    }

    private static String buildFileText(Path project) {
        return buildFile(project).map(ProjectWriter::read).orElse("");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 인코딩이 다른 파일 등은 건너뛴다
            return "";
        }
    }
}
