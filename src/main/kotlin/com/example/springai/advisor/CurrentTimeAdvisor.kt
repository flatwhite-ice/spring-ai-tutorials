package com.example.springai.advisor

import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.api.AdvisorChain
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * [확장 예제 B] 매 요청에 현재 시각을 알려 주는 어드바이저. — `BaseAdvisor` 의 before
 *
 * BaseAdvisor 는 `before`(요청 수정)와 `after`(응답 수정)만 구현하면 됩니다.
 * 체인을 넘기는 일(`chain.nextCall`)은 BaseAdvisor 가 대신 해 주고, 스트리밍(stream())도 자동 지원됩니다.
 * "요청에 무언가를 덧붙이기"처럼 단순한 일에는 CallAdvisor 보다 이쪽이 간단합니다.
 *
 * 같은 패턴으로 만들 수 있는 것: RAG(관련 문서 검색 후 주입), 사용자 프로필 주입, 개인정보 마스킹 등.
 *
 * ── 효과 ──────────────────────────────────────────────────────────────
 *  "오늘 무슨 요일이야?" 같은 질문에 모델이 날짜/시간 도구를 부르지 않고 바로 답합니다.
 *  LLM 호출이 2번(도구 요청 → 최종 답변)에서 1번으로 줄어듭니다.
 *  다른 타임존 시각이나 시간대 변환은 여전히 도구를 씁니다.
 *
 * ── 무엇을 어디에 붙이나: "바뀌는 값은 뒤쪽에" ──────────────────────────
 *  - 시스템 메시지 끝: 사용법 규칙(RULE). 항상 같은 문장이라 프롬프트 캐시를 깨지 않습니다.
 *  - 마지막 user 메시지 앞: `[현재 시각: …]`. 매분 바뀌는 값입니다.
 *  처음에는 시각까지 시스템 메시지에 붙였는데, 1분마다 첫 요청이 약 7초씩 느려졌습니다.
 *  Ollama 는 "앞부분이 같은 프롬프트"의 계산 결과(KV 캐시)를 재사용하는데, qwen3 템플릿은
 *  시스템 메시지 "뒤에" 도구 정의(약 1,500 토큰)를 붙이므로, 시스템 메시지 끝이 바뀌면
 *  도구 정의부터 전부 다시 계산해야 했기 때문입니다. (측정: docs/08)
 *
 * ── 체인 안의 위치 (AdvisorOrders.CURRENT_TIME) ───────────────────────
 *  메모리 어드바이저보다 "안쪽"에 둡니다. 그래야 주입한 내용이 대화 기록에 저장되지 않고,
 *  매 요청마다 그때의 시각으로 새로 붙습니다. 도구 루프 안쪽이므로 회차마다 실행되지만,
 *  ToolCallingAdvisor 는 다음 회차를 "주입 전" 원래 요청으로 만들기 때문에 중복으로 붙지 않습니다.
 *
 * @param clock 테스트에서 시각을 고정할 수 있도록 주입받습니다.
 */
class CurrentTimeAdvisor(
    private val order: Int,
    private val clock: Clock = Clock.systemDefaultZone(),
) : BaseAdvisor {

    override fun before(request: ChatClientRequest, chain: AdvisorChain): ChatClientRequest {
        val now = ZonedDateTime.now(clock)
        val stamp = "[현재 시각: ${now.format(FORMAT)} (${now.zone.id})]"

        // Prompt 는 불변 객체입니다. augment* 메서드는 해당 메시지만 바꾼 "새 Prompt"를 돌려줍니다.
        val prompt = request.prompt()
            // 시스템 메시지 끝에 고정 규칙 추가
            .augmentSystemMessage { system -> system.mutate().text(system.text + RULE).build() }
            // 마지막 user 메시지 앞에 시각 추가 (도구 루프 2회차 이후에도 "마지막 user 메시지"는 이번 질문)
            .augmentUserMessage { user -> user.mutate().text(stamp + "\n" + user.text).build() }

        // ChatClientRequest 도 불변(record)이라 mutate() 로 복사본을 만들어 돌려줍니다.
        return request.mutate().prompt(prompt).build()
    }

    /** 응답은 손대지 않습니다 */
    override fun after(response: ChatClientResponse, chain: AdvisorChain): ChatClientResponse = response

    override fun getOrder(): Int = order

    companion object {
        private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd (E) HH:mm", Locale.KOREAN)

        /** 시스템 메시지에 붙이는 고정 문장. 매번 같아야 캐시가 유지됩니다. */
        val RULE = "\n\n" + """
            # 현재 시각
            사용자 메시지 맨 앞의 [현재 시각: …] 은 시스템이 붙인 값입니다.
            그 타임존의 오늘 날짜, 요일, 현재 시각을 묻는 질문에는 이 값을 그대로 쓰고 도구를 부르지 마세요.
            다른 타임존의 시각이나 시간대 변환이 필요할 때만 날짜/시간 도구를 사용하세요.
        """.trimIndent()
    }
}
