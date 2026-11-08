package com.example.springai.config

import com.example.springai.advisor.AdvisorOrders
import com.example.springai.advisor.CurrentTimeAdvisor
import com.example.springai.advisor.MetricsAdvisor
import com.example.springai.advisor.SecretGuardAdvisor
import com.example.springai.tools.CalculatorTools
import com.example.springai.tools.DateTimeTools
import com.example.springai.tools.FileTools
import com.example.springai.tools.ShellTools
import com.example.springai.tools.TodoTools
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor
import org.springframework.ai.chat.client.advisor.api.Advisor
import org.springframework.ai.chat.memory.ChatMemory
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository
import org.springframework.ai.chat.memory.MessageWindowChatMemory
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.slf4j.LoggerFactory
import java.nio.file.Files

/**
 * 에이전트를 구성하는 Spring AI 부품들을 조립하는 설정 클래스.
 *
 * ┌──────────────────────────── 조립 결과 ────────────────────────────┐
 * │ ChatClient                                                        │
 * │  ├─ 시스템 프롬프트 : 에이전트의 역할/규칙                          │
 * │  ├─ Advisors        : 비밀키 차단(SecretGuardAdvisor, 직접 구현),   │
 * │  │                    시간·토큰 측정(MetricsAdvisor, 직접 구현),    │
 * │  │                    도구 호출 루프(ToolCallingAdvisor),          │
 * │  │                    대화 기억(MessageChatMemoryAdvisor),         │
 * │  │                    현재 시각 주입(CurrentTimeAdvisor, 직접 구현),│
 * │  │                    요청/응답 로깅(SimpleLoggerAdvisor)          │
 * │  ├─ Tools           : 날짜/시간, 계산기, TODO, 파일 읽기, 셸 명령   │
 * │  └─ ChatModel       : OllamaChatModel (스타터가 자동 생성)          │
 * └───────────────────────────────────────────────────────────────────┘
 */
@Configuration
class AgentConfig {

    /**
     * 대화 기억(ChatMemory).
     *
     * LLM 은 기본적으로 "상태가 없습니다(stateless)". 즉, 이전에 무슨 말을 했는지 기억하지 못합니다.
     * 그래서 매 요청마다 이전 대화 내용을 함께 보내줘야 하는데, 이 일을 ChatMemory 가 담당합니다.
     *
     * - [MessageWindowChatMemory]: 최근 N개의 메시지만 유지(슬라이딩 윈도우)하여 프롬프트가 무한히 커지는 것을 방지
     * - [InMemoryChatMemoryRepository]: 메시지를 JVM 메모리(Map)에 저장. 재시작하면 사라집니다.
     *   (실서비스라면 JDBC/Redis 등 영속 저장소 구현체로 교체)
     *
     * 참고: Spring AI 가 기본 ChatMemory 빈을 자동 구성해 주지만, 학습을 위해 명시적으로 정의했습니다.
     *       우리가 빈을 정의하면 자동 구성은 물러납니다(@ConditionalOnMissingBean).
     */
    @Bean
    fun chatMemory(properties: AgentProperties): ChatMemory =
        MessageWindowChatMemory.builder()
            .chatMemoryRepository(InMemoryChatMemoryRepository())
            .maxMessages(properties.memoryMaxMessages)
            .build()

    /**
     * 에이전트의 핵심 객체인 ChatClient.
     *
     * @param builder 스타터가 자동으로 만들어 주는 ChatClient.Builder. 이미 OllamaChatModel 이 연결되어 있습니다.
     * @param chatMemory 위에서 정의한 대화 기억
     * @param toolCallingManager 스타터가 자동 구성한 도구 실행기. @Tool 메서드를 찾아 실행합니다.
     * @param dateTimeTools 등 도구 객체들: @Component 로 등록된 빈이 주입됩니다.
     */
    @Bean
    fun agentChatClient(
        builder: ChatClient.Builder,
        properties: AgentProperties,
        chatMemory: ChatMemory,
        toolCallingManager: ToolCallingManager,
        dateTimeTools: DateTimeTools,
        calculatorTools: CalculatorTools,
        todoTools: TodoTools,
        fileTools: FileTools,
        shellTools: ShellTools,
    ): ChatClient =
        builder
            // (1) 시스템 프롬프트: 모든 요청 맨 앞에 붙는 "역할 지시문".
            //     모델에게 언제 도구를 써야 하는지 알려주면 도구 호출 정확도가 올라갑니다.
            //     기본 규칙 뒤에 작업 환경 안내 파일(workspace/AGENTS.md)을 덧붙입니다.
            .defaultSystem(buildSystemPrompt(properties))

            // (2) Advisor: ChatClient 호출 앞뒤에 끼어드는 "인터셉터(미들웨어)" 체인.
            //     order 값이 작을수록 바깥쪽(요청 시 먼저, 응답 시 나중)에서 실행됩니다.
            //     순서 값은 AdvisorOrders 한곳에 모아 두었습니다. (docs/08 참고)
            //
            //     요청 → SecretGuard → Metrics(요청 전체) → ToolCalling ─┐ (tool_calls 가 없을 때까지 반복)
            //              MessageChatMemoryAdvisor   ← 여기부터 루프의 "매 회차"마다 실행됨
            //                CurrentTimeAdvisor       ← 시스템 메시지에 현재 시각 추가
            //                  Metrics(LLM 1회)       ← 매 회차의 시간·토큰
            //                    SimpleLoggerAdvisor  ← 매 회차의 LLM 요청/응답을 로그로 출력
            //                      OllamaChatModel  → POST /api/chat
            //
            //     ■ 왜 ToolCallingAdvisor 를 쓰나?
            //       기본 동작에서는 도구 호출 루프가 ChatModel "안에서" 돌기 때문에, 메모리 어드바이저는
            //       최종 질문/답변만 보고 중간의 tool_calls·도구 결과는 저장하지 못합니다.
            //       그러면 대화 기록에는 "도구 없이 바로 답한 것처럼 보이는" 기록만 쌓이고,
            //       모델이 그 패턴을 흉내 내어 도구를 부르지 않고 답을 지어내게 됩니다. (실측: 2턴 이후 0/4)
            //       ToolCallingAdvisor 는 이 루프를 어드바이저 체인으로 꺼내 주므로, 그 안쪽에 둔
            //       메모리 어드바이저가 tool_calls 와 도구 결과까지 모두 기억할 수 있습니다.
            .defaultAdvisors(buildAdvisors(properties, chatMemory, toolCallingManager))

            // (3) 도구 등록: 객체 안의 @Tool 메서드들이 자동으로 "도구 정의(JSON 스키마)"로 변환되어
            //     매 요청마다 Ollama 에 함께 전송됩니다. 모델이 도구가 필요하다고 판단하면
            //     tool_calls 를 응답하고, Spring AI 가 해당 메서드를 실행한 뒤 결과를 다시 모델에 보냅니다.
            .defaultTools(dateTimeTools, calculatorTools, todoTools, fileTools, shellTools)
            .build()

    /**
     * 어드바이저 목록. 리스트 순서가 아니라 각 어드바이저의 getOrder() 값으로 정렬되어 실행됩니다.
     */
    private fun buildAdvisors(
        properties: AgentProperties,
        chatMemory: ChatMemory,
        toolCallingManager: ToolCallingManager,
    ): List<Advisor> = buildList {
        // [직접 구현] 가장 바깥: 비밀키가 보이면 모델을 부르지 않고 거절 (메모리·로그에도 남지 않음)
        add(SecretGuardAdvisor(AdvisorOrders.SECRET_GUARD))

        // [직접 구현] 도구 루프 바깥: 질문 1개 전체의 시간·토큰 (질문당 로그 1줄)
        add(MetricsAdvisor("요청 전체", AdvisorOrders.REQUEST_METRICS))

        // 도구 호출 루프 담당 (order = HIGHEST_PRECEDENCE + 300)
        add(
            ToolCallingAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                // false: 루프의 다음 회차에는 [시스템 프롬프트 + 마지막 도구 결과]만 넘기고,
                //        나머지 대화 기록은 안쪽의 메모리 어드바이저가 채워 넣도록 맡깁니다.
                //        (true 로 두면 기록이 중복으로 들어갑니다)
                .conversationHistoryEnabled(false)
                .build(),
        )

        // 대화 기억 담당. 루프 "안쪽"에 두기 위해 ToolCallingAdvisor 보다 큰 order 를 줍니다.
        //  - 요청 전: 이전 대화(도구 메시지 포함)를 프롬프트에 붙이고, 이번 user/tool 메시지를 저장
        //  - 응답 후: assistant 메시지(tool_calls 포함)를 저장
        add(MessageChatMemoryAdvisor.builder(chatMemory).order(AdvisorOrders.MEMORY).build())

        // [직접 구현] 메모리보다 안쪽: 현재 시각을 시스템 메시지에 붙임 (대화 기록에는 저장되지 않음)
        if (properties.injectCurrentTime) add(CurrentTimeAdvisor(AdvisorOrders.CURRENT_TIME))

        // [직접 구현] 도구 루프 안쪽: LLM 호출 1번의 시간·토큰 (도구를 2번 쓰면 로그 3줄)
        add(MetricsAdvisor("LLM 1회", AdvisorOrders.ROUND_METRICS))

        // 로깅 담당. 가장 안쪽에 두어 "실제로 LLM 에 보내는 최종 메시지"(시각 주입 후)를 매 회차 출력합니다.
        add(SimpleLoggerAdvisor(AdvisorOrders.LOGGER))
    }

    companion object {
        private val log = LoggerFactory.getLogger(AgentConfig::class.java)

        /** 안내 파일이 너무 길면 프롬프트가 커지므로 자릅니다 */
        private const val MAX_GUIDE_CHARS = 4_000

        /** 에이전트의 성격과 규칙을 정의하는 시스템 프롬프트 */
        val SYSTEM_PROMPT = """
            당신은 사용자를 돕는 친절한 한국어 비서 에이전트입니다.

            규칙:
            - 항상 한국어로 간결하게 답하세요.
            - 주어지지 않은 날짜나 시간은 추측하지 말고 날짜/시간 도구를 사용하세요.
            - 숫자 계산은 암산하지 말고 반드시 계산기 도구를 사용하세요.
            - 할 일(TODO) 추가/조회/완료 요청은 TODO 도구를 사용하세요.
            - 파일 하나의 내용을 볼 때는 파일 도구를 사용하세요.
            - 저장소 상태(커밋, 브랜치, 변경 파일), 파일 검색, 개수 세기처럼 명령으로 확인할 수 있는 질문은
              사용자가 명령을 말하지 않아도 알맞은 명령을 직접 골라 명령 실행 도구로 실행하세요.
              아래 "작업 환경 안내"의 목적별 명령을 참고하세요.
            - 명령은 작업 폴더 안을 대상으로 실행하세요. 작업 폴더 밖이 필요하면 그 이유를 사용자에게 설명하세요.
            - 명령이 거부되면 사유를 사용자에게 알리세요. 셸 문법 없이 같은 목적을 다시 시도하는 것은 괜찮지만,
              거부된 동작을 다른 방법으로 우회하려 하지 마세요.
            - "확인해 보겠습니다"라고 말만 하고 끝내지 마세요. 필요한 도구를 모두 호출한 뒤 그 결과로 답하세요.
            - 도구가 필요 없는 일반 질문에는 도구 없이 바로 답하세요.
            - 도구 결과를 바탕으로 사용자에게 자연스러운 문장으로 최종 답변하세요.
        """.trimIndent()

        /**
         * 시스템 프롬프트 = 기본 규칙 + 작업 환경 안내 파일(AGENTS.md).
         *
         * 모델이 자연어 요청에서 "이 프로젝트에 맞는 명령"을 추론하려면 환경 정보가 필요합니다.
         * (예: 작업 폴더가 git 저장소의 하위 폴더라는 것, 셸 문법을 쓸 수 없으니 grep -rc 를 쓰라는 것)
         * Claude Code 의 CLAUDE.md 처럼, 이런 정보를 코드가 아닌 문서 파일로 두면 수정이 쉽습니다.
         *
         * 참고: defaultSystem(String) 은 파라미터(systemParams)가 없으면 템플릿 렌더링을 하지 않으므로
         *       안내 파일에 { } 가 있어도 그대로 전달됩니다.
         * 보안: 안내 파일은 작업 폴더 안에 있지만, 시작 시에만 읽고 OS 샌드박스가 이 파일 쓰기를 막습니다.
         *       (에이전트나 악성 파일이 안내 파일을 고쳐 "영구적인 지시"를 심는 것을 방지)
         */
        fun buildSystemPrompt(properties: AgentProperties): String {
            val guide = properties.guidePath
            if (!Files.isRegularFile(guide)) {
                log.info("[Agent] 작업 환경 안내 파일 없음: {}", guide)
                return SYSTEM_PROMPT
            }
            val text = Files.readString(guide).let { if (it.length > MAX_GUIDE_CHARS) it.take(MAX_GUIDE_CHARS) else it }
            log.info("[Agent] 작업 환경 안내 파일을 시스템 프롬프트에 추가: {} ({}자)", guide.fileName, text.length)
            return SYSTEM_PROMPT + "\n\n# 작업 환경 안내 (" + properties.guideFile + ")\n" + text
        }
    }
}
