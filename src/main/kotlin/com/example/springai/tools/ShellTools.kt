package com.example.springai.tools

import com.example.springai.tools.shell.CommandApprover
import com.example.springai.tools.shell.CommandLineParser
import com.example.springai.tools.shell.CommandLineParser.Parsed
import com.example.springai.tools.shell.ShellExecutor
import com.example.springai.tools.shell.ShellPolicy
import com.example.springai.tools.shell.ShellPolicy.Decision
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * 셸 명령 실행 도구 — 에이전틱 코딩 도구의 "bash 도구"를 학습용으로 단순화한 버전입니다.
 *
 * 이 도구는 지금까지의 도구 중 가장 위험합니다. 모델이 작성한 명령이 실제 컴퓨터에서 실행되기 때문입니다.
 * 그래서 역할을 나눠 여러 겹으로 방어합니다. (자세한 설명: docs/07-shell-tool-security.md)
 *
 *   ShellTools (이 클래스)  : 모델과의 접점. 파싱 → 정책 판정 → (필요시) 승인 → 실행 → 결과 정리
 *   CommandLineParser      : 명령 한 줄 → 프로그램 + 인자 (셸 없이, 셸 문법은 거부)
 *   ShellPolicy            : 허용/승인/거부 판정 (허용 목록, 거부 목록)
 *   CommandApprover        : 사람 승인 (콘솔 y/N, REST 는 자동 거부)
 *   ShellExecutor          : 안전한 실행 (셸 미사용, 작업 폴더 고정, 환경 변수 차단, 타임아웃, 출력 제한)
 *   ProcessSandbox         : OS 샌드박스 (macOS Seatbelt: 작업 폴더 밖 쓰기·민감 경로 읽기·네트워크를 커널이 차단)
 *
 * 모든 결과(거부 포함)는 예외 대신 문자열로 돌려줍니다. 모델이 거부 사유를 읽고 사용자에게 설명하거나
 * 다른 방법(예: 파일 도구)을 시도할 수 있게 하기 위함입니다.
 */
@Component
class ShellTools(
    private val policy: ShellPolicy,
    private val approver: CommandApprover,
    private val executor: ShellExecutor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Tool(
        description = """작업 폴더(workspace)에서 명령 한 줄을 실행하고 출력을 돌려줍니다. 예: "git status", "wc -l hello.txt"
셸이 아니므로 파이프(|), 리다이렉트(>), 명령 연결(; &&), 와일드카드(*)는 사용할 수 없습니다. 명령 하나만 실행하세요.
공백이 들어간 인자는 따옴표로 묶으세요. 예: grep "hello world" notes/shopping.txt
파일 목록 조회나 파일 읽기는 이 도구보다 파일 도구를 우선 사용하세요."""
    )
    fun runCommand(
        @ToolParam(description = "실행할 명령 한 줄 (예: git log -1 --oneline)") commandLine: String,
    ): String {
        // 0) 명령 한 줄 → 프로그램 + 인자 목록 (셸 없이 직접 분리)
        val (command, argList) = when (val parsed = CommandLineParser.parse(commandLine)) {
            is Parsed.Ok -> parsed
            is Parsed.Error -> {
                log.info("[Tool] runCommand({}) 파싱 거부: {}", commandLine, parsed.message)
                return "실행 거부: ${parsed.message}"
            }
        }

        // 1) 정책 판정
        val decision = policy.evaluate(command, argList)
        log.info("[Tool] runCommand({}) 판정: {} - {}", commandLine, decision::class.simpleName, decision.reason)

        // 2) 거부 또는 승인 거절이면 실행하지 않음
        when (decision) {
            // 결과 문자열에 "사용자에게 그대로 알려라"를 명시합니다.
            // (실측: 사유 없이 거부만 알리면 모델이 "지원되지 않는 명령"이라고 잘못 설명했습니다)
            is Decision.Deny -> return "실행 거부(보안 정책): ${decision.reason}. 이 사유를 사용자에게 그대로 알려주세요."
            is Decision.Ask -> if (!approver.approve(commandLine, decision.reason)) {
                log.info("[Tool] runCommand({}) 승인 거절", commandLine)
                return "실행 거부(사용자가 승인하지 않음): ${decision.reason}. 이 사유를 사용자에게 그대로 알려주세요."
            }
            is Decision.Allow -> Unit
        }

        // 3) 실행
        val result = executor.execute(command, argList)
        log.info("[Tool] runCommand({}) -> exit={}, timedOut={}, {}자", commandLine, result.exitCode, result.timedOut, result.output.length)

        // 4) 모델이 읽기 쉬운 형태로 정리
        return buildString {
            when {
                result.timedOut -> appendLine("시간 초과로 강제 종료되었습니다.")
                result.exitCode == null -> appendLine("실행하지 못했습니다.")
                else -> appendLine("종료 코드: ${result.exitCode}")
            }
            append(result.output.ifBlank { "(출력 없음)" })
            // 샌드박스가 막은 실패는 메시지 없이 끝나기도 합니다(예: 네트워크 연결 실패).
            // 모델이 "포트가 닫혀 있다" 같은 엉뚱한 원인을 추측하지 않도록 힌트를 붙입니다. (실측 사례)
            // 단, 모든 실패에 붙이면 "커밋이 없음"(git exit 128) 같은 평범한 실패도 샌드박스 탓처럼 보이므로
            // 커널 거부 메시지가 있거나, 아무 출력 없이 실패한 경우에만 붙입니다.
            if (executor.sandboxed && result.exitCode != 0 && looksBlockedBySandbox(result.output)) {
                appendLine()
                append("참고: 이 명령은 OS 샌드박스 안에서 실행되었습니다. 작업 폴더 밖 쓰기, 민감한 경로 읽기, ")
                append("네트워크 연결은 샌드박스가 차단합니다. 실패 원인이 샌드박스 차단일 수 있음을 사용자에게 알려주세요.")
            }
        }
    }

    private fun looksBlockedBySandbox(output: String) =
        output.isBlank() || "Operation not permitted" in output
}
