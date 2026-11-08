package com.example.springai.tools

import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 날짜/시간 도구 — 가장 단순한 @Tool 예제입니다.
 *
 * LLM 은 학습 시점의 데이터만 알기 때문에 "지금 몇 시야?" 같은 질문에 정확히 답할 수 없습니다.
 * 이런 "모델이 모르는 정보"를 도구로 제공하는 것이 에이전트의 기본 아이디어입니다.
 *
 * 참고: 현재 시각은 CurrentTimeAdvisor 가 매 요청에 붙여 주기도 합니다(agent.inject-current-time).
 *       그때는 서울 기준 날짜·시각 질문에 이 도구를 부르지 않습니다. 이 규칙은 어드바이저가 붙이는
 *       문장(CurrentTimeAdvisor.RULE)에 있고, 도구 description 은 주입 여부와 관계없이 그대로 둡니다.
 *       (description 에 "[현재 시각]이 있으면 그 값을 쓰라"는 단서를 넣어 봤더니, 주입을 끈 상태에서
 *        모델이 시각이 주어진 것처럼 도구 없이 요일을 지어냈습니다. docs/08 참고)
 *
 * ── @Tool 이 동작하는 방식 ──────────────────────────────────────────
 * Spring AI 는 @Tool 메서드를 읽어 아래와 같은 도구 정의를 만들고 Ollama 에 전달합니다.
 *   {
 *     "type": "function",
 *     "function": {
 *       "name": "getCurrentDateTime",
 *       "description": "지정한 타임존의 현재 날짜와 시간을 알려줍니다...",
 *       "parameters": { "type": "object",
 *                       "properties": { "timeZone": { "type": "string", "description": "..." } } }
 *     }
 *   }
 * 모델은 이 description 만 보고 도구를 언제/어떻게 쓸지 판단하므로, 설명을 구체적으로 쓰는 것이 중요합니다.
 * ────────────────────────────────────────────────────────────────────
 */
@Component
class DateTimeTools {
    private val log = LoggerFactory.getLogger(javaClass)
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (EEEE) z")

    @Tool(description = "지정한 타임존의 현재 날짜와 시간을 알려줍니다. 사용자가 오늘 날짜, 현재 시각, 요일 등을 물을 때 사용하세요.")
    fun getCurrentDateTime(
        // required = false: 모델이 이 인자를 생략할 수 있습니다. 생략되면 null 이 들어옵니다.
        @ToolParam(description = "IANA 타임존 ID. 예: Asia/Seoul, America/New_York, Europe/London. 생략하면 Asia/Seoul", required = false)
        timeZone: String?,
    ): String {
        val zone = parseZone(timeZone ?: DEFAULT_ZONE)
        val result = ZonedDateTime.now(zone).format(formatter)
        log.info("[Tool] getCurrentDateTime(timeZone={}) -> {}", timeZone, result)
        return result
    }

    @Tool(description = "특정 날짜/시간을 한 타임존에서 다른 타임존 기준으로 변환합니다.")
    fun convertTimeZone(
        // 주의: 도구 정의 속 예시 값은 모델이 "사실"처럼 가져다 쓸 수 있습니다.
        //       예시에 2026-11-04T09:30 을 적어 두었을 때, 모델이 "오늘 며칠이야?"에 도구 없이
        //       "2026년 11월 4일"이라고 그럴듯하게 틀린 답을 했습니다. 그래서 형식만 적습니다. (docs/08)
        @ToolParam(description = "변환할 날짜/시간. 형식: yyyy-MM-ddTHH:mm (연-월-일T시:분, 24시간제)") dateTime: String,
        @ToolParam(description = "원래 타임존 IANA ID (예: Asia/Seoul)") fromTimeZone: String,
        @ToolParam(description = "변환할 대상 타임존 IANA ID (예: America/New_York)") toTimeZone: String,
    ): String {
        // 도구에서 예외를 던져도 되지만, 학습용으로 "모델이 읽을 수 있는 오류 메시지"를 돌려주는 방식을 보여줍니다.
        // 모델은 이 메시지를 보고 인자를 고쳐 다시 시도하거나 사용자에게 설명할 수 있습니다.
        val result = runCatching {
            LocalDateTime.parse(dateTime)
                .atZone(parseZone(fromTimeZone))
                .withZoneSameInstant(parseZone(toTimeZone))
                .format(formatter)
        }.getOrElse { "오류: 변환할 수 없습니다 (${it.message})" }
        log.info("[Tool] convertTimeZone({}, {} -> {}) -> {}", dateTime, fromTimeZone, toTimeZone, result)
        return result
    }

    private fun parseZone(id: String): ZoneId =
        runCatching { ZoneId.of(id.trim()) }.getOrElse { ZoneId.of(DEFAULT_ZONE) }

    companion object {
        const val DEFAULT_ZONE = "Asia/Seoul"
    }
}
