package com.example.springai.tools

import com.example.springai.config.AgentProperties
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

/**
 * 로컬 파일 읽기 도구 — "안전장치가 필요한" 도구 예제입니다.
 *
 * 에이전트에게 파일 시스템 접근 권한을 주는 것은 위험할 수 있습니다.
 * 모델이 (혹은 악의적인 사용자 입력이 모델을 속여서) "../../etc/passwd" 같은 경로를 요청할 수도 있기 때문입니다.
 * 그래서 이 도구는 다음 원칙을 지킵니다.
 *   1. 읽기 전용: 쓰기/삭제 도구는 제공하지 않음 (셸 도구는 작업 폴더 안 쓰기가 가능 — docs/07 참고)
 *   2. 샌드박스: agent.workspace-dir 폴더 안의 파일만 접근 가능 (경로 탈출 차단)
 *   3. 크기 제한: 너무 큰 파일은 잘라서 반환 (LLM 컨텍스트 보호)
 *
 * "도구의 권한은 코드에서 제한한다. 프롬프트로 부탁하는 것은 보안이 아니다." 를 기억하세요.
 */
@Component
class FileTools(properties: AgentProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 샌드박스 루트. 절대 경로 + 정규화(normalize)해 두어야 비교가 정확합니다. */
    private val root: Path = Path.of(properties.workspaceDir).toAbsolutePath().normalize()

    @Tool(description = "작업 폴더(workspace) 안의 파일과 하위 폴더 목록을 보여줍니다.")
    fun listFiles(
        @ToolParam(description = "작업 폴더 기준 상대 경로. 생략하면 작업 폴더 최상위", required = false)
        directory: String?,
    ): String {
        val result = runCatching {
            val dir = resolveSafely(directory ?: ".")
            require(dir.isDirectory()) { "폴더가 아닙니다: $directory" }
            Files.list(dir).use { stream ->
                stream.sorted().toList().joinToString("\n") { p ->
                    val name = p.relativeTo(root).toString()
                    if (p.isDirectory()) "[DIR]  $name/" else "[FILE] $name (${Files.size(p)} bytes)"
                }
            }.ifEmpty { "(비어 있음)" }
        }.getOrElse { "오류: ${it.message}" }
        log.info("[Tool] listFiles({}) -> \n{}", directory, result)
        return result
    }

    @Tool(description = "작업 폴더(workspace) 안의 텍스트 파일 내용을 읽어옵니다.")
    fun readFile(@ToolParam(description = "작업 폴더 기준 상대 파일 경로 (예: hello.txt)") path: String): String {
        val result = runCatching {
            val file = resolveSafely(path)
            require(file.isRegularFile()) { "파일이 없습니다: $path" }
            val text = file.readText()
            if (text.length > MAX_CHARS) text.take(MAX_CHARS) + "\n...(이하 생략: 총 ${text.length}자)" else text
        }.getOrElse { "오류: ${it.message}" }
        log.info("[Tool] readFile({}) -> {}자", path, result.length)
        return result
    }

    /**
     * 사용자가 준 상대 경로를 샌드박스 안의 절대 경로로 바꿉니다.
     * normalize() 로 ".." 을 해석한 뒤에도 root 로 시작하지 않으면 거부합니다.
     *   예) "../secret.txt" → /…/spring-ai/secret.txt → root 밖이므로 거부
     * (심볼릭 링크까지 막으려면 toRealPath() 비교가 추가로 필요합니다. 학습용이라 생략)
     */
    internal fun resolveSafely(relative: String): Path {
        val resolved = root.resolve(relative.trim()).normalize()
        require(resolved.startsWith(root)) { "작업 폴더 밖의 경로에는 접근할 수 없습니다: $relative" }
        require(Files.exists(resolved)) { "존재하지 않는 경로입니다: $relative" }
        return resolved
    }

    companion object {
        /** 한 번에 모델에게 돌려줄 최대 글자 수 */
        const val MAX_CHARS = 4_000
    }
}
