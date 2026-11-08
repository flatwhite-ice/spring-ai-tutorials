package com.example.springai.tools.shell

import com.example.springai.config.AgentProperties
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * 실제로 프로세스를 실행하는 클래스. JDK 기본 ProcessBuilder 만 사용합니다.
 *
 * 안전장치:
 *  1. 셸을 거치지 않음  : ProcessBuilder(listOf("ls", "-l")) 처럼 프로그램과 인자를 "목록"으로 전달.
 *                        `bash -c "문자열"` 이 아니므로 ; && | $() 가 해석되지 않습니다.
 *  2. 실행 파일 고정    : searchPaths 안에서 직접 찾은 절대 경로로 실행 (PATH 조작으로 가짜 프로그램 실행 방지)
 *  3. 작업 폴더 고정    : 항상 workspace 폴더에서 실행
 *  4. 환경 변수 비우기  : JVM 의 환경 변수(API 키 등 비밀값)를 자식 프로세스에 넘기지 않음
 *  5. 타임아웃          : 시간을 넘기면 자식 프로세스까지 모두 강제 종료
 *  6. 출력 길이 제한    : 너무 긴 출력은 잘라서 모델 컨텍스트를 보호
 *  7. OS 샌드박스       : 커널이 작업 폴더 밖 쓰기, 민감 경로 읽기, 네트워크를 차단 (ProcessSandbox)
 */
@Component
class ShellExecutor(
    properties: AgentProperties,
    private val sandbox: ProcessSandbox,
) {

    private val shell = properties.shell
    private val workspace: Path = Path.of(properties.workspaceDir).toAbsolutePath().normalize()

    /** OS 샌드박스 안에서 실행하는지 여부 (실패 원인 안내에 사용) */
    val sandboxed: Boolean get() = sandbox !is NoSandbox

    /** 실행 결과 */
    data class Result(val exitCode: Int?, val output: String, val timedOut: Boolean)

    fun execute(command: String, args: List<String>): Result {
        val executable = resolveExecutable(command)
            ?: return Result(exitCode = null, output = "실행 파일을 찾을 수 없습니다: $command", timedOut = false)

        // 출력은 임시 파일로 보냅니다.
        // 주의: process.inputStream 을 readText() 로 먼저 읽으면, 프로세스가 끝날 때까지 그 줄에서 멈춰서
        //       아래 waitFor(타임아웃)이 의미가 없어집니다. 파일로 보내면 이 문제가 생기지 않습니다.
        val outputFile = Files.createTempFile("agent-shell-", ".out")
        try {
            // 샌드박스가 있으면 [sandbox-exec, -p, 정책, 실행파일, 인자...] 로 감싸집니다
            val builder = ProcessBuilder(sandbox.wrap(listOf(executable.toString()) + args))
                .directory(workspace.toFile())
                .redirectErrorStream(true)                 // stderr 를 stdout 에 합침
                .redirectOutput(outputFile.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(NULL_FILE)) // 입력을 기다리는 명령이 멈추지 않게

            builder.environment().apply {
                clear()                                    // 비밀값이 든 환경 변수 차단
                put("PATH", shell.searchPaths.joinToString(":"))
                put("LANG", "C.UTF-8")
            }

            val process = builder.start()
            val finished = process.waitFor(shell.timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!finished) {
                // 자식 프로세스(손자 포함)를 먼저 죽이고 본 프로세스를 종료
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }

            val raw = String(Files.readAllBytes(outputFile), Charsets.UTF_8)
            val output = if (raw.length > shell.maxOutputChars) {
                raw.take(shell.maxOutputChars) + "\n...(출력 생략: 총 ${raw.length}자)"
            } else raw

            return Result(exitCode = if (finished) process.exitValue() else null, output = output, timedOut = !finished)
        } finally {
            Files.deleteIfExists(outputFile)
        }
    }

    /** searchPaths 에서 실행 파일을 찾아 절대 경로로 돌려줍니다. 없으면 null. */
    private fun resolveExecutable(command: String): Path? =
        shell.searchPaths.asSequence()
            .map { Path.of(it, command) }
            .firstOrNull { it.isRegularFile() && it.isExecutable() }

    private companion object {
        val NULL_FILE = java.io.File("/dev/null")
    }
}
