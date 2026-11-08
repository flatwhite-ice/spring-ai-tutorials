package com.example.springai.advisor

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.api.CallAdvisor
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain

/**
 * [확장 예제 A] 소요 시간과 토큰 사용량을 측정하는 어드바이저. — `CallAdvisor` 로 "감싸기"
 *
 * CallAdvisor 는 `adviseCall(request, chain)` 하나만 구현합니다.
 * `chain.nextCall(request)` 를 부르면 안쪽 어드바이저들과 모델이 실행되고 응답이 돌아옵니다.
 * 그 앞뒤에 원하는 코드를 넣으면 됩니다. (서블릿 Filter, Spring AOP 의 @Around 와 같은 모양)
 *
 *   adviseCall(request, chain) {
 *       // ① 요청 전 처리
 *       val response = chain.nextCall(request)   // ② 안쪽 실행
 *       // ③ 응답 후 처리
 *       return response
 *   }
 *
 * ── 같은 클래스를 두 위치에 등록합니다 (AdvisorOrders 참고) ─────────────────
 *  - 도구 루프 바깥(REQUEST_METRICS): 질문 1개 전체를 측정 → 질문당 1줄
 *    ToolCallingAdvisor 가 회차별 토큰을 합산(UsageAccumulator)해서 돌려주므로 토큰도 전체 합계입니다.
 *  - 도구 루프 안쪽(ROUND_METRICS): LLM 호출 1번을 측정 → 도구를 2번 부르면 3줄
 * ──────────────────────────────────────────────────────────────────────────
 *
 * @param label 로그에 찍을 이름 (예: "요청 전체", "LLM 1회")
 * @param order 체인 안의 위치
 */
class MetricsAdvisor(
    private val label: String,
    private val order: Int,
) : CallAdvisor {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun adviseCall(request: ChatClientRequest, chain: CallAdvisorChain): ChatClientResponse {
        val start = System.nanoTime()

        // 안쪽 어드바이저 → … → 모델 호출. 응답이 올 때까지 여기서 기다립니다.
        val response = chain.nextCall(request)

        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val chatResponse = response.chatResponse()
        val usage = chatResponse?.metadata?.usage
        val toolCalls = chatResponse?.result?.output?.hasToolCalls() == true

        log.info(
            "[Metrics] {}: {}ms, 토큰 입력 {} / 출력 {}{}",
            label, elapsedMs, usage?.promptTokens, usage?.completionTokens,
            if (toolCalls) ", 모델이 도구 호출을 요청함" else "",
        )
        return response
    }

    // 같은 클래스를 두 번 등록하므로 이름에 label 을 넣어 구분합니다.
    override fun getName(): String = "MetricsAdvisor($label)"

    override fun getOrder(): Int = order
}
