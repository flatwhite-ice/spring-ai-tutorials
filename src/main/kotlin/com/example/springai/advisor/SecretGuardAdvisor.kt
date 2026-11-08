package com.example.springai.advisor

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.api.CallAdvisor
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import reactor.core.publisher.Flux

/**
 * [확장 예제 C] 사용자 메시지에 비밀키가 보이면 모델을 부르지 않고 바로 거절하는 가드레일. — short-circuit
 *
 * 어드바이저는 `chain.nextCall()` 을 "부르지 않을" 수도 있습니다.
 * 그러면 안쪽의 도구 루프·메모리·모델이 전혀 실행되지 않고, 여기서 만든 응답이 그대로 사용자에게 갑니다.
 *
 * ── 왜 가장 바깥(AdvisorOrders.SECRET_GUARD)인가 ───────────────────────
 *  메모리 어드바이저보다 바깥이어야 비밀키가 담긴 메시지가 대화 기록에 저장되지 않습니다.
 *  또 로깅 어드바이저까지 가지 않으므로 로그 파일에도 남지 않습니다.
 *
 * ── 왜 StreamAdvisor 도 구현하나 ───────────────────────────────────────
 *  ChatClient 는 call() 에는 CallAdvisor 만, stream() 에는 StreamAdvisor 만 적용합니다.
 *  CallAdvisor 만 구현하면 누군가 stream() 으로 호출할 때 가드레일이 "조용히 빠집니다".
 *  보안용 어드바이저는 두 경로를 모두 막아야 합니다. (MetricsAdvisor 는 측정용이라 call 만 구현)
 *
 * 한계: 정규식으로 잡을 수 있는 형식만 막습니다. 도구 결과(예: cat 으로 읽은 파일)에 들어 있는 비밀키는
 *       이 어드바이저가 보지 못합니다. 그쪽은 셸 샌드박스의 denied-read-paths 가 담당합니다. (docs/07)
 */
class SecretGuardAdvisor(
    private val order: Int = AdvisorOrders.SECRET_GUARD,
) : CallAdvisor, StreamAdvisor {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun adviseCall(request: ChatClientRequest, chain: CallAdvisorChain): ChatClientResponse {
        val found = detect(request) ?: return chain.nextCall(request) // 문제 없으면 안쪽으로 통과
        return blockedResponse(request, found)                         // nextCall 을 부르지 않음 → 모델 호출 없음
    }

    override fun adviseStream(request: ChatClientRequest, chain: StreamAdvisorChain): Flux<ChatClientResponse> {
        val found = detect(request) ?: return chain.nextStream(request)
        return Flux.just(blockedResponse(request, found))
    }

    /** 이번 사용자 메시지에서 비밀키 종류를 찾습니다. 없으면 null */
    private fun detect(request: ChatClientRequest): String? {
        // 가장 바깥이라 아직 대화 기록이 붙기 전입니다. 마지막 user 메시지가 곧 이번 입력입니다.
        val text = request.prompt().userMessage.text.orEmpty()
        val found = PATTERNS.entries.firstOrNull { (_, regex) -> regex.containsMatchIn(text) }?.key
        if (found != null) {
            // 비밀키 원문은 로그에 남기지 않고 종류만 기록합니다.
            log.warn("[Guard] 사용자 메시지에서 {} 형식을 발견해 모델 호출 없이 거절했습니다.", found)
        }
        return found
    }

    /** 모델 응답처럼 보이는 ChatClientResponse 를 직접 만듭니다. `.content()` 로 그대로 꺼낼 수 있습니다. */
    private fun blockedResponse(request: ChatClientRequest, found: String): ChatClientResponse {
        val message = AssistantMessage(
            "메시지에 $found 로 보이는 값이 있어 처리하지 않았습니다. " +
                "비밀키는 대화에 붙여 넣지 말고, 이미 노출되었다면 해당 키를 폐기(rotate)하세요.",
        )
        return ChatClientResponse.builder()
            .chatResponse(ChatResponse.builder().generations(listOf(Generation(message))).build())
            .context(request.context())
            .build()
    }

    override fun getName(): String = "SecretGuardAdvisor"

    override fun getOrder(): Int = order

    companion object {
        /** 종류 이름 → 정규식. 형식이 정해진 키만 넣었습니다 (오탐을 줄이기 위해) */
        val PATTERNS: Map<String, Regex> = linkedMapOf(
            "AWS 액세스 키" to Regex("""\b(AKIA|ASIA)[0-9A-Z]{16}\b"""),
            "개인 키(PEM)" to Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
            "GitHub 토큰" to Regex("""\bgh[pousr]_[A-Za-z0-9]{36,}\b"""),
            "API 키(sk-…)" to Regex("""\bsk-[A-Za-z0-9_-]{20,}"""),
        )
    }
}
