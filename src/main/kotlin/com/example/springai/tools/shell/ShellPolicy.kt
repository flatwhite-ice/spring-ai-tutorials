package com.example.springai.tools.shell

import com.example.springai.config.AgentProperties
import org.springframework.stereotype.Component

/**
 * 셸 명령 허용 정책 — "이 명령을 실행해도 되는가?"를 판정합니다.
 *
 * 판정은 항상 코드로 합니다. 시스템 프롬프트에 "위험한 명령은 쓰지 마"라고 적는 것은
 * 모델에 대한 "부탁"일 뿐 보안이 아닙니다. 모델은 실수하고, 프롬프트 인젝션에 속을 수도 있습니다.
 *
 * 판정 순서 (위에서 먼저 걸리는 것이 결과, 거부가 항상 우선):
 *  1. DENY  : 빈 명령, 이름에 경로(/) 포함, 거부 목록의 프로그램, 거부 인자(-exec 등) 포함
 *  2. ASK   : 인자가 작업 폴더 밖을 가리킬 수 있음 (/…, ~…, ..) — 허용 목록에 있는 명령이어도 승인 필요
 *  3. ALLOW : 서브커맨드 목록이 있는 프로그램(git)은 허용된 서브커맨드일 때만, 나머지는 허용 목록에 있을 때
 *  4. ASK   : 그 밖의 모든 경우 → 사람에게 승인 요청
 *
 * 셸을 거치지 않고 실행하고(ShellExecutor), 셸 문법은 파싱 단계에서 이미 거부되므로(CommandLineParser)
 * `;`, `&&`, `|`, `$(...)` 로 명령을 이어 붙일 수 없습니다. 그래서 정책은 "프로그램 + 인자"만 보면 됩니다.
 */
@Component
class ShellPolicy(properties: AgentProperties) {

    private val shell = properties.shell

    /** 판정 결과. reason 은 로그와 모델에게 돌려줄 메시지에 사용됩니다. */
    sealed interface Decision {
        val reason: String
        data class Allow(override val reason: String) : Decision
        data class Ask(override val reason: String) : Decision
        data class Deny(override val reason: String) : Decision
    }

    fun evaluate(command: String, args: List<String>): Decision {
        // ── 1. 거부 ─────────────────────────────────────────────
        if (command.isBlank()) return Decision.Deny("명령이 비어 있습니다")
        if ('/' in command || '\\' in command) {
            // "/tmp/evil" 처럼 경로를 주면 searchPaths 밖의 프로그램을 실행할 수 있으므로 금지
            return Decision.Deny("프로그램 이름에 경로를 포함할 수 없습니다: $command")
        }
        if (command in shell.deniedCommands) return Decision.Deny("거부 목록에 있는 명령입니다: $command")
        args.firstOrNull { it in shell.deniedArguments }?.let {
            return Decision.Deny("허용되지 않는 인자입니다: $it")
        }

        // ── 2. 작업 폴더 밖을 가리키는 인자는 승인 필요 ───────────────
        //  예) cat ../secret.txt, ls /etc, cat ~/.ssh/id_rsa
        args.firstOrNull { looksOutsideWorkspace(it) }?.let {
            return Decision.Ask("작업 폴더 밖을 가리킬 수 있는 인자입니다: $it")
        }

        // ── 3. 허용 ─────────────────────────────────────────────
        shell.allowedSubcommands[command]?.let { subcommands ->
            val sub = args.firstOrNull()
            return if (sub != null && sub in subcommands) Decision.Allow("허용된 서브커맨드: $command $sub")
            else Decision.Ask("허용 목록에 없는 서브커맨드입니다: $command ${sub ?: "(없음)"}")
        }
        if (command in shell.allowedCommands) return Decision.Allow("허용 목록에 있는 명령: $command")

        // ── 4. 그 밖은 모두 승인 필요 ────────────────────────────────
        return Decision.Ask("허용 목록에 없는 명령입니다: $command")
    }

    /** 절대 경로, 홈 경로, 상위 폴더(..) 참조를 "밖을 가리킬 수 있음"으로 봅니다. */
    private fun looksOutsideWorkspace(arg: String): Boolean =
        arg.startsWith("/") || arg.startsWith("~") || arg.split('/', '=').any { it == ".." }
}
