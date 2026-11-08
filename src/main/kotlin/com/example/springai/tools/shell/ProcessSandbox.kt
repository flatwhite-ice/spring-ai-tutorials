package com.example.springai.tools.shell

import com.example.springai.config.AgentProperties
import com.example.springai.config.AgentProperties.SandboxType
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isExecutable

/**
 * OS 샌드박스 — 실행할 명령을 "샌드박스 안에서 실행하는 명령"으로 감쌉니다.
 *
 *   wrap([/bin/ls, -la])  →  [/usr/bin/sandbox-exec, -p, "(version 1)...", /bin/ls, -la]
 *
 * ■ 정책(ShellPolicy)과 무엇이 다른가?
 *   - 정책  : 실행 "전"에 명령 문자열을 보고 판단합니다. 문자열 검사라 우회 가능성이 남습니다.
 *             (예: 허용된 git 의 위험한 옵션, 작업 폴더 안의 심볼릭 링크)
 *   - 샌드박스: 실행 "중"에 커널이 시스템 콜(파일 쓰기, 네트워크 연결 등)을 직접 거부합니다.
 *             프로세스가 무엇을 하든, 자식 프로세스를 띄우든 이 제한을 벗어날 수 없습니다.
 *
 * ■ 가상화가 아닙니다
 *   명령은 같은 커널 위에서 네이티브로 실행되고, 커널이 정책만 검사합니다.
 *   그래서 명령당 수 ms 정도만 추가되고 실행 중 성능 차이는 거의 없습니다. (docs/07 실측 참고)
 */
fun interface ProcessSandbox {
    /** @param command 실행 파일 절대 경로 + 인자 */
    fun wrap(command: List<String>): List<String>
}

/** 샌드박스 없이 그대로 실행 */
object NoSandbox : ProcessSandbox {
    override fun wrap(command: List<String>) = command
}

/**
 * macOS Seatbelt 샌드박스 (`/usr/bin/sandbox-exec`).
 *
 * macOS 커널의 샌드박스 기능을 씁니다. Chrome, App Store 앱의 샌드박스와 같은 기능입니다.
 * 정책은 SBPL(Sandbox Profile Language, Scheme 과 비슷한 문법)로 작성합니다.
 *
 * 주의: sandbox-exec 는 man 페이지에 deprecated 로 표시되어 있지만 현재 macOS 에서 동작하며,
 *       여러 에이전트 도구가 사용합니다. Linux 에서는 bubblewrap 으로 같은 역할을 구현합니다.
 */
class SeatbeltSandbox(
    workspace: Path,
    allowNetwork: Boolean,
    deniedReadPaths: List<Path>,
    readOnlyPaths: List<Path> = emptyList(),
) : ProcessSandbox {

    /** 실행할 때마다 쓰는 정책 문자열. 생성 시 한 번만 만들어 둡니다. */
    val profile: String = buildProfile(workspace, allowNetwork, deniedReadPaths, readOnlyPaths)

    override fun wrap(command: List<String>): List<String> =
        listOf(SANDBOX_EXEC, "-p", profile) + command

    companion object {
        const val SANDBOX_EXEC = "/usr/bin/sandbox-exec"

        fun isAvailable(): Boolean =
            System.getProperty("os.name").startsWith("Mac") && Path.of(SANDBOX_EXEC).isExecutable()

        /**
         * SBPL 정책 생성.
         *
         * ★ 규칙이 겹치면 "나중에 쓴 규칙이 이깁니다". 그래서 넓은 규칙을 먼저, 예외를 나중에 씁니다.
         *   (예: 모든 쓰기 거부 → 그 다음에 workspace 쓰기 허용)
         *
         * ★ 경로는 실제 경로(toRealPath)로 써야 합니다. macOS 에서 /tmp 는 /private/tmp 의 링크라서,
         *   링크 경로로 규칙을 쓰면 커널이 보는 실제 경로와 맞지 않아 규칙이 적용되지 않습니다.
         */
        fun buildProfile(
            workspace: Path,
            allowNetwork: Boolean,
            deniedReadPaths: List<Path>,
            readOnlyPaths: List<Path> = emptyList(),
        ): String = buildString {
            appendLine("(version 1)")
            // 1) 기본은 허용: 프로그램 실행, 시스템 라이브러리 읽기 등 일반 동작은 그대로 둡니다.
            appendLine("(allow default)")

            // 2) 파일 쓰기: 전부 막고 작업 폴더만 허용
            appendLine("(deny file-write*)")
            appendLine("(allow file-write* (subpath ${quote(realPath(workspace))}) (literal \"/dev/null\"))")

            // 2-1) 작업 폴더 안이라도 읽기 전용으로 둘 파일 (예: AGENTS.md 안내 파일)
            //      위의 allow 보다 "나중에" 써야 이 deny 가 이깁니다.
            readOnlyPaths.forEach { appendLine("(deny file-write* (literal ${quote(realPath(it))}))") }

            // 3) 민감한 경로는 읽기도 차단 (SSH 키, 클라우드 자격 증명 등)
            deniedReadPaths.forEach { appendLine("(deny file-read* (subpath ${quote(realPath(it))}))") }

            // 4) 네트워크: 기본 전부 차단 (데이터 유출, 악성 스크립트 다운로드 방지)
            if (!allowNetwork) appendLine("(deny network*)")
        }

        /** 실제 경로로 변환. 아직 없는 경로는 정규화만 합니다. */
        private fun realPath(path: Path): String =
            (if (Files.exists(path)) path.toRealPath() else path.toAbsolutePath().normalize()).toString()

        /** SBPL 문자열 리터럴로 감싸기 (역슬래시, 큰따옴표 이스케이프) */
        private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

/** 설정(agent.shell.sandbox.type)에 따라 샌드박스 구현을 고릅니다. */
@Configuration
class ProcessSandboxConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun processSandbox(properties: AgentProperties): ProcessSandbox {
        val sandbox = properties.shell.sandbox
        val useSeatbelt = when (sandbox.type) {
            SandboxType.NONE -> false
            SandboxType.SEATBELT -> {
                // 명시적으로 요구했는데 쓸 수 없으면 조용히 넘어가지 않고 시작을 실패시킵니다 (fail-closed)
                check(SeatbeltSandbox.isAvailable()) { "agent.shell.sandbox.type=seatbelt 이지만 sandbox-exec 를 사용할 수 없습니다 (macOS 전용)" }
                true
            }
            SandboxType.AUTO -> SeatbeltSandbox.isAvailable()
        }

        if (!useSeatbelt) {
            log.warn("[Shell] OS 샌드박스 없이 명령을 실행합니다 (type={}). 정책·승인 계층만 적용됩니다.", sandbox.type)
            return NoSandbox
        }

        val home = System.getProperty("user.home")
        val seatbelt = SeatbeltSandbox(
            workspace = Path.of(properties.workspaceDir),
            allowNetwork = sandbox.allowNetwork,
            deniedReadPaths = sandbox.deniedReadPaths.map { Path.of(it.replaceFirst(Regex("^~"), home)) },
            // 시스템 프롬프트에 들어가는 안내 파일은 명령으로 고칠 수 없게 합니다
            readOnlyPaths = listOf(properties.guidePath),
        )
        log.info("[Shell] OS 샌드박스: macOS Seatbelt (쓰기: workspace 만, 네트워크: {})", if (sandbox.allowNetwork) "허용" else "차단")
        log.debug("[Shell] Seatbelt 정책:\n{}", seatbelt.profile)
        return seatbelt
    }
}
