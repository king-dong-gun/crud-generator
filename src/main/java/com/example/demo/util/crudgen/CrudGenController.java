package com.example.demo.util.crudgen;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CREATE TABLE 문을 받아 CRUD 코드(Entity, DTO, Repository, Service, Controller)를 이 프로젝트에 바로 쓴다.
 *
 * <pre>
 * POST /api/generate/write
 * Body: raw → Text 에 CREATE TABLE 문
 * </pre>
 *
 * 선택 쿼리 파라미터
 * <ul>
 *   <li>basePackage : 생략하면 @SpringBootApplication 클래스의 패키지</li>
 *   <li>lombok      : 생략하면 빌드 파일에 lombok 이 있는지로 결정</li>
 *   <li>overwrite   : true 면 이미 있는 파일을 덮어씀 (기본 false → 하나라도 있으면 아무것도 쓰지 않고 409)</li>
 *   <li>projectPath : 프로젝트 폴더를 자동으로 못 찾을 때만 절대 경로로 지정</li>
 * </ul>
 *
 * 개발용 도구다. 같은 PC(localhost)에서 온 요청만 받으며, 배포 전에 util/crudgen 폴더를 지운다.
 */
@RestController
@RequestMapping("/api/generate")
public class CrudGenController {

    @PostMapping("/write")
    public ResponseEntity<?> write(
            HttpServletRequest http,
            @RequestBody(required = false) String ddl,
            @RequestParam(name = "basePackage", required = false) String basePackage,
            @RequestParam(name = "lombok", required = false) Boolean lombok,
            @RequestParam(name = "overwrite", defaultValue = "false") boolean overwrite,
            @RequestParam(name = "projectPath", required = false) String projectPath) {

        if (!isLocal(http)) {
            return error(HttpStatus.FORBIDDEN,
                    "코드를 파일로 쓰는 API 라서 같은 PC(localhost)에서 보낸 요청만 받습니다. (요청 주소: "
                            + http.getRemoteAddr() + ")");
        }

        DdlParser.Table table = DdlParser.parse(ddl);

        Path project = isBlank(projectPath)
                ? ProjectWriter.findProjectRoot(CrudGenController.class)
                : ProjectWriter.resolveProject(projectPath);

        List<String> detected = new ArrayList<>();
        String pkg;
        if (isBlank(basePackage)) {
            pkg = ProjectWriter.detectBasePackage(project);
            detected.add("basePackage: @SpringBootApplication 클래스의 패키지");
        } else {
            pkg = basePackage.trim();
        }
        boolean useLombok;
        if (lombok == null) {
            useLombok = ProjectWriter.detectLombok(project);
            detected.add("lombok: 빌드 파일에 lombok " + (useLombok ? "있음" : "없음"));
        } else {
            useLombok = lombok;
        }

        List<CodeGenerator.GeneratedFile> files = CodeGenerator.generate(pkg, table, useLombok);

        List<String> existing = ProjectWriter.existingFiles(project, files);
        if (!existing.isEmpty() && !overwrite) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "이미 있는 파일이 있어 아무것도 생성하지 않았습니다. 덮어쓰려면 overwrite=true 를 붙이세요.",
                    "existing", existing));
        }
        List<String> written = ProjectWriter.write(project, files);

        return ResponseEntity.ok(new WriteResult(project.toString(), pkg, detected, useLombok, existing, written,
                ProjectWriter.dependencyWarnings(project, useLombok)));
    }

    /**
     * /api/generate/write 응답
     *
     * @param projectPath 파일을 쓴 프로젝트 폴더
     * @param basePackage 사용한 기준 패키지
     * @param detected    프로젝트에서 자동으로 알아낸 값
     * @param lombok      Lombok 사용 여부
     * @param overwritten 덮어쓴 파일 (overwrite=true 일 때만)
     * @param files       생성한 파일 (프로젝트 기준 상대 경로)
     * @param warnings    빌드 파일에 빠진 의존성 등
     */
    public record WriteResult(
            String projectPath,
            String basePackage,
            List<String> detected,
            boolean lombok,
            List<String> overwritten,
            List<String> files,
            List<String> warnings) {
    }

    // ------------------------------------------------------------ 에러 응답
    // 이 컨트롤러에서 난 예외만 처리한다. (내 프로젝트의 다른 API 에는 영향 없음)

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badInput(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<Map<String, String>> ioFailure(UncheckedIOException e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", String.valueOf(message)));
    }

    /**
     * 같은 PC 에서 온 요청인지 (127.0.0.1, ::1).
     * 서버를 127.0.0.1 에만 묶으면 Windows 에서 localhost 가 ::1 로 풀릴 때 접속이 안 되므로 여기서 검사한다.
     */
    private static boolean isLocal(HttpServletRequest http) {
        try {
            return InetAddress.getByName(http.getRemoteAddr()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
