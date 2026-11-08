package com.example.springai.agent

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.memory.ChatMemory
import org.springframework.stereotype.Service

/**
 * 에이전트의 진입점 서비스.
 *
 * REST 컨트롤러와 콘솔 REPL 이 모두 이 서비스를 사용합니다.
 * 즉 "입력 방식"과 "에이전트 로직"을 분리해 두었습니다.
 *
 * ── 한 번의 chat() 호출에서 일어나는 일 (어드바이저 순서는 AdvisorOrders 참고) ──
 *  1. SecretGuardAdvisor 가 사용자 메시지에 비밀키가 있는지 검사 (있으면 여기서 거절하고 끝)
 *  2. ToolCallingAdvisor 가 "에이전트 루프"를 시작하고, 루프의 매 회차마다 안쪽 어드바이저를 실행:
 *     a. MessageChatMemoryAdvisor : 대화 기록 키로 이전 대화(도구 메시지 포함)를 붙이고, 새 메시지를 저장
 *     b. CurrentTimeAdvisor       : 현재 시각을 붙임 (대화 기록에는 남지 않음)
 *     c. OllamaChatModel          : POST /api/chat 호출 (메시지 + 도구 정의 목록 전송)
 *  3. 응답에 tool_calls 가 있으면 ToolCallingManager 가 해당 @Tool 메서드를 실행하고,
 *     결과(role=tool 메시지)를 넣어 2-a 부터 다시 실행. 이때 도구에는 ToolContext(사용자 ID)가 함께 전달됨
 *  4. 모델이 도구를 더 부르지 않고 최종 텍스트를 주면 루프 종료 → 최종 텍스트 반환
 *  (MetricsAdvisor 가 루프 바깥과 안쪽에서 각각 시간·토큰을 로그로 남깁니다)
 * ────────────────────────────────────────────────────────────────────
 * 이 모든 과정이 아래 `.call().content()` 한 줄 안에서 자동으로 처리됩니다.
 *
 * ── LLM 에 보내지 않는 값 두 가지 ──────────────────────────────────────
 *  - 어드바이저 파라미터(`.advisors { it.param(...) }`) : 어드바이저끼리만 공유 (예: 대화 기록 키)
 *  - 도구 컨텍스트(`.toolContext(...)`)                : 도구 메서드에만 전달 (예: 사용자 ID)
 *  둘 다 프롬프트에 들어가지 않으므로 사용자 식별 정보를 담기에 알맞습니다. (docs/09)
 */
@Service
class AgentService(
    private val chatClient: ChatClient,
    private val chatMemory: ChatMemory,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 사용자 메시지를 에이전트에게 전달하고 최종 답변을 받습니다.
     *
     * @param userId 사용자 식별자. 반드시 인증된 서버 쪽 정보에서 얻은 값이어야 합니다 (LLM 에는 보내지 않음).
     * @param conversationId 대화를 구분하는 ID. 같은 사용자가 같은 ID 로 호출하면 이전 대화를 기억합니다.
     * @param userMessage 사용자 입력
     * @return 에이전트의 최종 답변 텍스트
     */
    fun chat(userId: String, conversationId: String, userMessage: String): String {
        // 대화 기록은 "사용자ID:대화ID" 로 묶습니다. 다른 사용자의 conversationId 를 넣어도 그 기록에 닿지 않습니다.
        val memoryKey = UserContext.memoryKey(userId, conversationId)
        log.debug("[Agent] memoryKey={} 사용자 메시지: {}", memoryKey, userMessage)

        val answer = chatClient.prompt() // 요청 작성 시작 (기본 설정: 시스템 프롬프트, 도구, 어드바이저가 이미 포함됨)
            .user(userMessage) // 사용자 메시지 추가
            .advisors { spec ->
                // 메모리 어드바이저에게 "어느 대화의 기억을 쓸지" 알려주는 파라미터
                spec.param(ChatMemory.CONVERSATION_ID, memoryKey)
            }
            // 도구 실행 시에만 전달되는 값. LLM 에 보내는 요청(메시지·도구 스키마)에는 들어가지 않습니다.
            .toolContext(UserContext.of(userId))
            .call() // 동기 호출 (스트리밍은 .stream() 사용)
            .content() // 응답에서 텍스트만 꺼냄
            ?: "(응답이 비어 있습니다)"

        log.debug("[Agent] memoryKey={} 최종 답변: {}", memoryKey, answer)
        return answer
    }

    /** 특정 사용자의 특정 대화 기억을 삭제합니다. (다른 사용자의 대화는 지울 수 없음) */
    fun reset(userId: String, conversationId: String) {
        val memoryKey = UserContext.memoryKey(userId, conversationId)
        chatMemory.clear(memoryKey)
        log.debug("[Agent] memoryKey={} 대화 기억 삭제", memoryKey)
    }
}
