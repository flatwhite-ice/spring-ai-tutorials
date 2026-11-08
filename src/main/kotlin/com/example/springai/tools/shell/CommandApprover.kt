package com.example.springai.tools.shell

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 사람 승인(Human-in-the-loop) 담당.
 *
 * ShellPolicy 가 ASK 로 판정한 명령은 실행 전에 이 인터페이스를 통해 사람에게 묻습니다.
 * 실행 환경에 따라 "누구에게 어떻게 물을지"가 다르므로 인터페이스로 분리했습니다.
 *   - 콘솔 모드: 터미널에서 y/N 입력
 *   - REST 모드: 요청을 보낸 사람에게 되물을 방법이 없으므로 자동 거부
 *   - (실무 예) 슬랙/메신저로 승인 요청, 관리자 화면의 승인 대기열 등
 */
fun interface CommandApprover {
    /** @return true 면 실행 승인 */
    fun approve(commandLine: String, reason: String): Boolean
}

/**
 * 콘솔(cli 프로파일) 승인자: 터미널에서 직접 묻습니다.
 *
 * 도구는 ConsoleChatRunner 와 같은 스레드에서 동기적으로 실행되므로,
 * 여기서 표준 입력을 읽어도 대화 입력과 섞이지 않습니다.
 */
@Component
@Profile("cli")
class ConsoleCommandApprover : CommandApprover {
    override fun approve(commandLine: String, reason: String): Boolean {
        println()
        println("⚠️  [승인 요청] 에이전트가 다음 명령을 실행하려고 합니다.")
        println("    명령: $commandLine")
        println("    이유: $reason")
        print("    실행할까요? (y/N): ")
        // 기본값은 거부(N). 엔터만 치거나 입력이 끝나도 실행하지 않습니다.
        return readlnOrNull()?.trim()?.lowercase() in setOf("y", "yes")
    }
}

/**
 * REST(기본) 모드 승인자: 물어볼 사람이 없으므로 항상 거부합니다.
 * "확실하지 않으면 실행하지 않는다(fail-closed)" 원칙입니다.
 */
@Component
@Profile("!cli")
class RejectingCommandApprover : CommandApprover {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun approve(commandLine: String, reason: String): Boolean {
        log.warn("[Shell] 승인이 필요한 명령을 자동 거부했습니다 (REST 모드): {} / {}", commandLine, reason)
        return false
    }
}
