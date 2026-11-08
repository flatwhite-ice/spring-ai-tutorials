package com.example.springai.cli

import com.example.springai.agent.AgentService
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 콘솔(터미널) 대화 모드.
 *
 * - [Profile]("cli"): `spring.profiles.active=cli` 일 때만 이 빈이 만들어집니다.
 *   cli 프로파일에서는 application-cli.yml 에 의해 웹 서버가 뜨지 않습니다.
 * - [CommandLineRunner]: 스프링 컨텍스트가 모두 준비된 직후 run() 이 한 번 호출됩니다.
 *
 * 실행: ./gradlew bootRun --args='--spring.profiles.active=cli'
 *   (또는 빌드 후 java -jar build/libs/spring-ai-0.0.1-SNAPSHOT.jar --spring.profiles.active=cli)
 */
@Component
@Profile("cli")
class ConsoleChatRunner(private val agentService: AgentService) : CommandLineRunner {

    override fun run(vararg args: String) {
        // 콘솔 한 세션 = 하나의 대화. /reset 으로 새 대화를 시작할 수 있습니다.
        var conversationId = UUID.randomUUID().toString()

        println(
            """
            |==============================================
            | Spring AI 에이전트 콘솔 (Ollama)
            |  - 종료: exit   - 대화 초기화: /reset
            |==============================================
            """.trimMargin()
        )

        while (true) {
            print("\n나> ")
            // readlnOrNull(): 입력 스트림이 끝나면(Ctrl+D) null
            val input = readlnOrNull()?.trim() ?: break
            when {
                input.isEmpty() -> continue
                input.equals("exit", ignoreCase = true) -> break
                input == "/reset" -> {
                    agentService.reset(conversationId)
                    conversationId = UUID.randomUUID().toString()
                    println("(새 대화를 시작합니다)")
                }
                else -> {
                    val answer = runCatching { agentService.chat(conversationId, input) }
                        .getOrElse { "오류가 발생했습니다: ${it.message}  (Ollama 가 실행 중인지 확인하세요)" }
                    println("에이전트> $answer")
                }
            }
        }
        println("안녕히 가세요!")
    }
}
