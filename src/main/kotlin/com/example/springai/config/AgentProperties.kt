package com.example.springai.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * application.yml 의 `agent.*` 값을 타입 안전하게 읽어오는 설정 클래스.
 *
 * 예) application.yml
 *       agent:
 *         workspace-dir: ./workspace   →  workspaceDir = "./workspace"
 *     (YAML 의 kebab-case 키가 Kotlin 의 camelCase 프로퍼티로 자동 매핑됩니다)
 *
 * @property workspaceDir 파일 도구·셸 도구가 접근할 수 있는 샌드박스 폴더
 * @property memoryMaxMessages 대화 기억에 유지할 최근 메시지 수
 * @property guideFile 작업 환경 안내 파일 (workspace 기준 상대 경로). 시작 시 읽어 시스템 프롬프트에 덧붙임
 * @property injectCurrentTime true 면 CurrentTimeAdvisor 가 매 요청의 시스템 메시지에 현재 시각을 붙임
 * @property shell 셸 도구(ShellTools) 보안 정책
 */
@ConfigurationProperties(prefix = "agent")
data class AgentProperties(
    val workspaceDir: String = "./workspace",
    val memoryMaxMessages: Int = 40,
    val guideFile: String = "AGENTS.md",
    val injectCurrentTime: Boolean = true,
    val shell: ShellProperties = ShellProperties(),
) {
    /** 안내 파일의 경로 (workspace 기준) */
    val guidePath: java.nio.file.Path
        get() = java.nio.file.Path.of(workspaceDir).resolve(guideFile).toAbsolutePath().normalize()

    /**
     * 셸 도구 보안 정책. (agent.shell.*)
     *
     * 판정 순서: 거부(denied) → 허용(allowed) → 그 외는 사용자 승인(ask)
     *
     * @property allowedCommands 승인 없이 바로 실행할 수 있는 프로그램 이름
     * @property allowedSubcommands 프로그램별로 허용할 첫 번째 인자(서브커맨드). 예) git → [status, log]
     *                              여기에 등록된 프로그램은 목록에 있는 서브커맨드만 바로 실행됩니다.
     * @property deniedCommands 승인 여부와 관계없이 절대 실행하지 않는 프로그램
     * @property deniedArguments 어떤 프로그램이든 이 인자가 포함되면 거부 (예: find -exec)
     * @property searchPaths 실행 파일을 찾을 폴더. 이 폴더 안의 프로그램만 실행됩니다.
     * @property timeout 명령 최대 실행 시간. 넘으면 프로세스(와 자식 프로세스)를 강제 종료
     * @property maxOutputChars 모델에게 돌려줄 출력 최대 글자 수
     * @property sandbox OS 샌드박스 설정 (커널 수준 격리)
     */
    data class ShellProperties(
        val allowedCommands: List<String> = listOf("ls", "cat", "head", "tail", "wc", "grep", "date", "echo", "pwd"),
        val allowedSubcommands: Map<String, List<String>> = mapOf("git" to listOf("status", "log", "diff", "branch")),
        val deniedCommands: List<String> = listOf("rm", "sudo", "bash", "sh", "zsh", "curl", "wget"),
        val deniedArguments: List<String> = listOf("-exec", "-execdir", "-delete"),
        val searchPaths: List<String> = listOf("/usr/bin", "/bin", "/usr/local/bin", "/opt/homebrew/bin"),
        val timeout: Duration = Duration.ofSeconds(10),
        val maxOutputChars: Int = 4_000,
        val sandbox: SandboxProperties = SandboxProperties(),
    )

    /**
     * OS 샌드박스 설정. (agent.shell.sandbox.*)
     *
     * 정책(ShellPolicy)이 "무엇을 실행할지"를 문자열로 판단한다면, 샌드박스는 실행된 프로세스가
     * "무엇을 할 수 있는지"를 커널이 강제합니다. 정책을 우회한 명령도 샌드박스는 벗어나지 못합니다.
     *
     * @property type auto: macOS 면 seatbelt, 아니면 샌드박스 없이 실행(경고 로그)
     *                seatbelt: macOS sandbox-exec 사용 (없으면 시작 실패)
     *                none: 사용하지 않음
     * @property allowNetwork false 면 모든 네트워크 연결 차단 (localhost 포함)
     * @property deniedReadPaths 읽기까지 차단할 민감한 경로. `~` 는 사용자 홈으로 바뀝니다.
     */
    data class SandboxProperties(
        val type: SandboxType = SandboxType.AUTO,
        val allowNetwork: Boolean = false,
        val deniedReadPaths: List<String> = listOf(
            "~/.ssh", "~/.aws", "~/.gnupg", "~/.config", "~/.docker", "~/.kube", "~/.netrc", "~/Library/Keychains",
        ),
    )

    enum class SandboxType { AUTO, SEATBELT, NONE }
}
