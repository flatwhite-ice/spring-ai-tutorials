package com.example.springai.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 도구는 평범한 Kotlin 클래스이므로 LLM 없이 단위 테스트할 수 있습니다.
 * 도구 로직을 먼저 테스트로 검증해 두면, 에이전트가 이상하게 동작할 때
 * "도구 문제인지 / 모델 판단 문제인지"를 빠르게 구분할 수 있습니다.
 */
class CalculatorToolsTest {
    private val calc = CalculatorTools()

    @Test
    fun `소수 덧셈이 부동소수점 오차 없이 계산된다`() = assertEquals("0.3", calc.add("0.1", "0.2"))

    @Test
    fun `쉼표가 포함된 숫자도 처리한다`() = assertEquals("3000", calc.multiply("1,000", "3"))

    @Test
    fun `소수 곱셈`() = assertEquals("83699.1", calc.multiply("123.45", "678"))

    @Test
    fun `나눗셈은 소수점 10자리까지 반올림한다`() = assertEquals("3.3333333333", calc.divide("10", "3"))

    @Test
    fun `0으로 나누면 오류 메시지를 반환한다`() = assertTrue(calc.divide("1", "0").startsWith("오류"))

    @Test
    fun `숫자가 아니면 오류 메시지를 반환한다`() = assertTrue(calc.add("abc", "1").startsWith("오류"))

    @Test
    fun `거듭제곱`() = assertEquals("1024", calc.power("2", "10"))
}
