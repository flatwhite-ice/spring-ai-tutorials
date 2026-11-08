package com.example.springai.advisor

import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor
import org.springframework.core.Ordered

/**
 * 어드바이저 실행 순서를 한곳에 모아 둔 상수.
 *
 * order 값이 작을수록 "바깥쪽"입니다. 요청은 바깥 → 안쪽으로, 응답은 안쪽 → 바깥으로 지나갑니다.
 *
 * ```
 * 요청 → SecretGuard → RequestMetrics → ToolCalling ┐→ Memory → CurrentTime → RoundMetrics → Logger → Ollama
 *                                                    └─ 여기부터 오른쪽은 도구 호출 루프의 "매 회차"마다 실행
 * ```
 *
 * ToolCallingAdvisor 는 모델이 tool_calls 를 주면 도구를 실행한 뒤 "자기보다 안쪽 체인"을 다시 호출합니다.
 * 그래서 ToolCallingAdvisor 보다 바깥에 둔 어드바이저는 질문 1개당 1번,
 * 안쪽에 둔 어드바이저는 LLM 호출 1번마다 실행됩니다. 어디에 둘지가 곧 동작을 결정합니다.
 */
object AdvisorOrders {
    /** 가장 바깥: 차단하면 메모리·도구·모델 모두 실행되지 않음 */
    const val SECRET_GUARD = Ordered.HIGHEST_PRECEDENCE

    /** 도구 루프 바깥: 질문 1개 전체의 시간·토큰 */
    val REQUEST_METRICS = ToolCallingAdvisor.DEFAULT_ORDER - 1

    /** 도구 호출 루프 (HIGHEST_PRECEDENCE + 300) */
    val TOOL_CALLING = ToolCallingAdvisor.DEFAULT_ORDER

    /** 대화 기억: 루프 안쪽이라 tool_calls·도구 결과까지 저장 */
    val MEMORY = ToolCallingAdvisor.DEFAULT_ORDER + 1

    /** 현재 시각 주입: 메모리보다 안쪽이라 주입한 내용이 대화 기록에 남지 않음 */
    val CURRENT_TIME = ToolCallingAdvisor.DEFAULT_ORDER + 2

    /** LLM 호출 1회의 시간·토큰 */
    val ROUND_METRICS = ToolCallingAdvisor.DEFAULT_ORDER + 3

    /** 가장 안쪽: 실제로 Ollama 에 보내는 최종 프롬프트를 로그로 출력 */
    val LOGGER = ToolCallingAdvisor.DEFAULT_ORDER + 4
}
