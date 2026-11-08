# 05. 도구(Tool) 설명과 추가 방법

## 제공 도구 목록

| 클래스 | 도구 | 설명 | 학습 포인트 |
|---|---|---|---|
| `DateTimeTools` | `getCurrentDateTime(timeZone?)` | 현재 날짜/시간 | 가장 단순한 @Tool, 선택 인자(`required = false`) |
| | `convertTimeZone(dateTime, from, to)` | 타임존 변환 | 오류를 문자열로 돌려주기 |
| `CalculatorTools` | `add`, `subtract`, `multiply`, `divide`, `power` | 사칙연산 | LLM 의 약점(계산)을 도구로 보완, BigDecimal |
| `TodoTools` | `addTodo(title)` | 할 일 추가 | **상태를 바꾸는** 도구 |
| | `listTodos(includeDone?)` | 목록 조회 | 객체(List) 반환 → 자동 JSON 변환 |
| | `completeTodo(id)` | 완료 처리 | 숫자 인자 |
| `FileTools` | `listFiles(directory?)` | 폴더 목록 | |
| | `readFile(path)` | 파일 읽기 | **샌드박스**: workspace 밖 접근 차단, 크기 제한 |
| `ShellTools` | `runCommand(commandLine)` | 셸 명령 실행 | **가장 위험한 도구**: 허용/승인/거부 정책, 사람 승인, 실행 제한 → [07. 셸 명령 도구와 보안](07-shell-tool-security.md) |

> `@Tool` 이 어떻게 인식되어 모델에게 전달되는지는 [06. 에이전트 내부 동작 원리](06-agent-internals.md#2-도구-인식-원리) 참고.

## 도구 설계 원칙
1. **description 을 구체적으로**: 모델은 설명만 보고 언제 쓸지 결정합니다. "언제 사용하라"까지 써 주면 좋습니다.
2. **오류는 문자열로 돌려주기**: 예외 대신 `"오류: 0으로 나눌 수 없습니다"` 를 반환하면 모델이 상황을 이해하고 사용자에게 설명합니다.
3. **권한은 코드로 제한하기**: "작업 폴더 밖은 읽지 마"를 프롬프트로 부탁하는 것은 보안이 아닙니다. `FileTools.resolveSafely()` 처럼 코드에서 막아야 합니다.
4. **로그 남기기**: `[Tool]` 로그가 있어야 모델이 실제로 도구를 썼는지 확인할 수 있습니다.
5. **단위 테스트**: 도구는 평범한 클래스이므로 LLM 없이 테스트할 수 있습니다. (`src/test/.../tools/`)

## 새 도구 추가하기 (예: 랜덤 숫자 도구)

### 1단계: 도구 클래스 작성
`src/main/kotlin/com/example/springai/tools/RandomTools.kt`
```kotlin
package com.example.springai.tools

import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import kotlin.random.Random

@Component   // 스프링 빈으로 등록
class RandomTools {
    @Tool(description = "min 이상 max 이하의 무작위 정수를 하나 뽑습니다. 주사위, 추첨 등에 사용하세요.")
    fun randomInt(
        @ToolParam(description = "최솟값") min: Int,
        @ToolParam(description = "최댓값") max: Int,
    ): Int = Random.nextInt(min, max + 1)
}
```

### 2단계: ChatClient 에 등록
`config/AgentConfig.kt`
```kotlin
@Bean
fun agentChatClient(
    builder: ChatClient.Builder,
    chatMemory: ChatMemory,
    /* ... 기존 도구들 ... */
    randomTools: RandomTools,                 // ← 추가
): ChatClient =
    builder
        // ...
        .defaultTools(dateTimeTools, calculatorTools, todoTools, fileTools, randomTools)  // ← 추가
        .build()
```

### 3단계: (선택) 시스템 프롬프트에 사용 규칙 추가
```
- 무작위 숫자가 필요하면 랜덤 도구를 사용하세요.
```

### 4단계: 확인
```bash
./gradlew bootRun
curl -s -X POST localhost:8080/api/agent/chat -H 'Content-Type: application/json' \
  -d '{"message":"주사위 굴려줘"}'
```
로그에서 도구 실행 여부를 확인하세요.

## 요청마다 다른 도구를 주고 싶다면?
`defaultTools(...)` 는 모든 요청에 적용됩니다. 특정 요청에만 도구를 주려면 호출 시점에 지정합니다.
```kotlin
chatClient.prompt()
    .user(message)
    .tools(randomTools)   // 이 요청에만 추가
    .call()
    .content()
```
