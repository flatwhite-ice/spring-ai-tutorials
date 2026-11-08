package com.example.springai.tools.shell

/**
 * 명령 한 줄("git log -1 --oneline")을 프로그램과 인자 목록으로 나누는 파서.
 *
 * ■ 왜 직접 나누나? (실측 결과)
 *   처음에는 도구 인자를 command="git", args=["status"] 처럼 배열로 받았습니다.
 *   그런데 qwen3:8b 는 대화가 이어지면 args 를 빈 배열로 보내는 실수를 반복했습니다. (같은 상황 0/5)
 *   명령 한 줄을 문자열로 받게 바꾸자 5/5 로 정확해졌습니다. 작은 모델은 배열보다 문자열을 훨씬 잘 채웁니다.
 *
 * ■ 셸이 아닙니다
 *   따옴표로 인자를 묶는 것만 지원합니다. 파이프(|), 리다이렉트(>), 명령 연결(; &&), 변수($),
 *   명령 치환(`), 와일드카드(*)처럼 "셸만 이해하는 문법"이 따옴표 밖에 있으면 거부합니다.
 *   (그대로 글자로 넘겨도 안전하지만, 모델이 파이프가 동작한다고 착각하고 엉뚱한 결과를 해석하게 되므로
 *    명확한 오류 메시지로 알려주는 편이 낫습니다.)
 *
 *   예) git log -1 --oneline        → [git, log, -1, --oneline]
 *       grep "hello world" a.txt    → [grep, hello world, a.txt]
 *       echo 'a; b'                 → [echo, a; b]          (따옴표 안의 ; 는 글자)
 *       ls | wc -l                  → 오류 (셸 문법)
 */
object CommandLineParser {

    /** 따옴표 밖에 오면 거부하는 셸 문법 문자 */
    private const val SHELL_SYNTAX = "|;&<>`$*?(){}"

    sealed interface Parsed {
        data class Ok(val command: String, val args: List<String>) : Parsed
        data class Error(val message: String) : Parsed
    }

    fun parse(commandLine: String): Parsed {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inToken = false
        var quote: Char? = null   // 현재 열려 있는 따옴표 (' 또는 "), 없으면 null

        for (c in commandLine) {
            when {
                quote != null && c == quote -> quote = null                       // 따옴표 닫기
                quote != null -> current.append(c)                               // 따옴표 안: 모두 글자
                c == '\'' || c == '"' -> { quote = c; inToken = true }           // 따옴표 열기
                c.isWhitespace() -> if (inToken) { tokens += current.toString(); current.clear(); inToken = false }
                c in SHELL_SYNTAX -> return Parsed.Error(
                    "셸 문법('$c')은 지원하지 않습니다. 파이프·리다이렉트·명령 연결 없이 명령 하나만 실행하세요."
                )
                else -> { current.append(c); inToken = true }
            }
        }
        if (quote != null) return Parsed.Error("따옴표($quote)가 닫히지 않았습니다.")
        if (inToken) tokens += current.toString()
        if (tokens.isEmpty()) return Parsed.Error("명령이 비어 있습니다.")
        return Parsed.Ok(tokens.first(), tokens.drop(1))
    }
}
