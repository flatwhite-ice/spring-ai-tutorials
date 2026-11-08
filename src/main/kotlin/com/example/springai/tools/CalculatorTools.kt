package com.example.springai.tools

import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * 계산기 도구 — LLM 의 약점을 도구로 보완하는 예제입니다.
 *
 * 언어 모델은 "다음에 올 그럴듯한 글자"를 예측하는 방식이라 큰 수의 곱셈/나눗셈을 자주 틀립니다.
 * 계산을 도구에 맡기면 모델은 "무엇을 계산할지"만 결정하고, 정확한 계산은 코드가 합니다.
 *
 * 부동소수점 오차(0.1 + 0.2 = 0.30000000000000004)를 피하기 위해 BigDecimal 을 사용합니다.
 * 인자 타입을 String 으로 받는 이유: 모델이 "1,234" 처럼 쉼표를 넣어도 우리가 정리해서 처리하기 위함입니다.
 */
@Component
class CalculatorTools {
    private val log = LoggerFactory.getLogger(javaClass)

    @Tool(description = "두 수를 더합니다 (a + b).")
    fun add(@ToolParam(description = "첫 번째 수") a: String, @ToolParam(description = "두 번째 수") b: String): String =
        compute("add", a, b) { x, y -> x + y }

    @Tool(description = "첫 번째 수에서 두 번째 수를 뺍니다 (a - b).")
    fun subtract(@ToolParam(description = "첫 번째 수") a: String, @ToolParam(description = "두 번째 수") b: String): String =
        compute("subtract", a, b) { x, y -> x - y }

    @Tool(description = "두 수를 곱합니다 (a * b).")
    fun multiply(@ToolParam(description = "첫 번째 수") a: String, @ToolParam(description = "두 번째 수") b: String): String =
        compute("multiply", a, b) { x, y -> x * y }

    @Tool(description = "첫 번째 수를 두 번째 수로 나눕니다 (a / b). 결과는 소수점 10자리까지 반올림됩니다.")
    fun divide(@ToolParam(description = "나뉘는 수") a: String, @ToolParam(description = "나누는 수 (0 불가)") b: String): String =
        compute("divide", a, b) { x, y ->
            require(y.signum() != 0) { "0으로 나눌 수 없습니다" }
            x.divide(y, 10, RoundingMode.HALF_UP)
        }

    @Tool(description = "거듭제곱을 계산합니다 (base 의 exponent 제곱). exponent 는 0 이상 999 이하의 정수여야 합니다.")
    fun power(@ToolParam(description = "밑") base: String, @ToolParam(description = "지수 (0~999 정수)") exponent: String): String =
        compute("power", base, exponent) { x, y ->
            val n = y.intValueExact()
            require(n in 0..999) { "지수는 0~999 사이 정수여야 합니다" }
            x.pow(n, MathContext.DECIMAL128)
        }

    /**
     * 공통 계산 처리: 문자열 → BigDecimal 변환, 연산 실행, 결과/오류를 문자열로 반환.
     * 오류도 문자열로 돌려주어 모델이 상황을 이해하고 사용자에게 설명할 수 있게 합니다.
     */
    private fun compute(op: String, a: String, b: String, operation: (BigDecimal, BigDecimal) -> BigDecimal): String {
        val result = runCatching {
            operation(parse(a), parse(b)).stripTrailingZeros().toPlainString()
        }.getOrElse { "오류: ${it.message}" }
        log.info("[Tool] {}({}, {}) -> {}", op, a, b, result)
        return result
    }

    private fun parse(value: String): BigDecimal =
        value.replace(",", "").trim().toBigDecimalOrNull()
            ?: throw IllegalArgumentException("'$value' 는 숫자가 아닙니다")
}
