# 04. Spring AI 핵심 개념

이 프로젝트에 등장하는 Spring AI 개념을 "아래층 → 위층" 순서로 정리합니다.

```
 ┌───────────────────────────────┐
 │ ChatClient  (우리가 쓰는 API)   │  ← 시스템 프롬프트, Advisor, Tool 을 조립
 ├───────────────────────────────┤
 │ Advisor 체인                   │  ← 요청/응답 가로채기 (기억, 로깅 ...)
 ├───────────────────────────────┤
 │ ChatModel (OllamaChatModel)   │  ← 실제 LLM 호출 + 도구 호출 루프
 ├───────────────────────────────┤
 │ OllamaApi (HTTP)              │  ← POST /api/chat
 └───────────────────────────────┘
```

## 1. 스타터와 자동 구성 (Auto-configuration)
`build.gradle.kts`
```kotlin
dependencyManagement {
    imports { mavenBom("org.springframework.ai:spring-ai-bom:2.0.2") }
}
dependencies {
    implementation("org.springframework.ai:spring-ai-starter-model-ollama")
}
```
이 스타터 하나만 추가하면 `application.yml` 의 `spring.ai.ollama.*` 값을 읽어
다음 빈들이 **자동으로** 만들어집니다. 우리가 직접 `new` 할 필요가 없습니다.
- `OllamaApi` : Ollama HTTP 클라이언트
- `OllamaChatModel` : `ChatModel` 인터페이스의 Ollama 구현체
- `ChatClient.Builder` : 위 ChatModel 이 연결된 빌더
- `ToolCallingManager` : 도구 실행 담당

> 다른 LLM(OpenAI, Anthropic 등)으로 바꾸려면 스타터와 설정만 바꾸면 됩니다.
> 우리 코드는 `ChatClient` 만 사용하므로 거의 수정할 필요가 없습니다. 이것이 Spring AI 의 "이식성"입니다.

## 2. ChatModel
LLM 과 실제로 통신하는 저수준 인터페이스입니다. `Prompt`(메시지 목록 + 옵션)를 받아 `ChatResponse` 를 돌려줍니다.
도구 호출 루프(tool_calls → 실행 → 재요청)도 여기서 처리됩니다.
보통은 직접 쓰지 않고 `ChatClient` 를 통해 사용합니다.

## 3. ChatClient
LLM 호출을 위한 **유창한(fluent) API** 입니다. `AgentConfig.kt` 에서 기본값을 설정해 두고,
`AgentService.kt` 에서 요청마다 사용자 메시지만 넣어 호출합니다.

```kotlin
// 설정 시 (한 번)
builder
    .defaultSystem("당신은 ... 에이전트입니다")   // 시스템 프롬프트
    .defaultAdvisors(memoryAdvisor, loggerAdvisor) // Advisor
    .defaultTools(dateTimeTools, calculatorTools)  // 도구
    .build()

// 호출 시 (매 요청)
chatClient.prompt()
    .user("123 곱하기 456?")
    .advisors { it.param(ChatMemory.CONVERSATION_ID, "demo-1") }
    .call()       // 동기 호출. 스트리밍은 .stream()
    .content()    // 텍스트만. 객체로 받고 싶으면 .entity(MyClass::class.java)
```

## 4. 메시지 역할 (Role)
| Role | 의미 | 이 프로젝트에서 |
|---|---|---|
| `system` | 모델의 역할·규칙 지시 | `AgentConfig.SYSTEM_PROMPT` |
| `user` | 사용자 입력 | `.user(message)` |
| `assistant` | 모델의 응답 (텍스트 또는 tool_calls) | 모델이 생성 |
| `tool` | 도구 실행 결과 | Spring AI 가 자동 추가 |

## 5. Advisor
`ChatClient` 호출의 앞뒤에 끼어드는 **인터셉터**입니다. (Servlet Filter, Spring AOP 와 비슷한 개념)
```
요청 → [ToolCallingAdvisor] → [MemoryAdvisor] → [LoggerAdvisor] → ChatModel
응답 ← [ToolCallingAdvisor] ← [MemoryAdvisor] ← [LoggerAdvisor] ← ChatModel
          └─ tool_calls 가 있으면 도구 실행 후 안쪽 체인을 다시 호출 (반복)
```
- 실행 순서는 `order` 값으로 정해집니다. 작을수록 바깥쪽(요청 시 먼저, 응답 시 나중).
- `ToolCallingAdvisor` : 도구 호출 루프를 ChatModel 내부 대신 어드바이저 체인에서 실행 (Spring AI 2.0+)
- `MessageChatMemoryAdvisor` : 요청 전 → 이전 대화를 메시지로 추가 / 응답 후 → 새 메시지 저장.
  ToolCallingAdvisor *안쪽*에 두면 루프 매 회차마다 실행되어 도구 메시지까지 저장합니다.
- `SimpleLoggerAdvisor` : 요청·응답을 DEBUG 로그로 출력
- 그 외: `QuestionAnswerAdvisor`(RAG), `SafeGuardAdvisor`(금칙어) 등.
- 직접 만들 수도 있습니다: `CallAdvisor`(감싸기) 또는 `BaseAdvisor`(before/after). 이 프로젝트의 예제 3개(측정, 시각 주입, 가드레일)는 [08장](08-custom-advisors.md)에 있습니다.

## 6. ChatMemory
LLM 은 **상태가 없습니다**(stateless). 이전 대화를 기억하게 하려면 매번 대화 기록을 같이 보내야 합니다.
- `ChatMemory` : 대화 기록 관리 인터페이스 (`add`, `get`, `clear`)
- `MessageWindowChatMemory` : 최근 N개 메시지만 유지 (프롬프트가 무한히 커지는 것 방지)
- `ChatMemoryRepository` : 실제 저장소. 여기서는 `InMemoryChatMemoryRepository`(재시작 시 사라짐)
- `ChatMemory.CONVERSATION_ID` : 어떤 대화의 기억을 쓸지 지정하는 키

## 7. Tool Calling (도구 호출)
에이전트의 핵심입니다.
```kotlin
@Component
class CalculatorTools {
    @Tool(description = "두 수를 곱합니다 (a * b).")
    fun multiply(
        @ToolParam(description = "첫 번째 수") a: String,
        @ToolParam(description = "두 번째 수") b: String,
    ): String = ...
}
```
1. `@Tool` 메서드 → 도구 정의(이름, 설명, JSON 스키마)로 변환되어 LLM 에 전달
2. LLM 이 필요하다고 판단하면 `tool_calls` 로 "이 도구를 이 인자로 실행해줘" 응답
3. `ToolCallingManager` 가 메서드를 찾아 실행 (JSON 인자 → Kotlin 파라미터 변환).
   이 프로젝트에서는 `ToolCallingAdvisor` 가 ToolCallingManager 를 호출합니다.
4. 반환값을 문자열(객체는 JSON)로 바꿔 `tool` 메시지로 LLM 에 다시 전달
5. LLM 이 최종 답변을 만들 때까지 반복

**중요한 점**
- 모델은 코드가 아니라 `description` 만 보고 판단합니다 → 설명을 구체적으로 쓰세요.
- 도구는 **우리 JVM 안에서** 실행됩니다. LLM 은 실행 요청만 할 뿐입니다.
- 모델이 도구 호출을 지원해야 합니다. (`ollama show <모델>` → Capabilities 에 `tools`)
- 도구 메서드에 `ToolContext` 매개변수를 두면 서버가 `.toolContext(map)` 으로 넣은 값(예: 사용자 ID)을 받습니다.
  이 매개변수는 JSON 스키마에서 빠지므로 LLM 은 그 값을 보지도, 바꾸지도 못합니다. → [09장](09-user-context.md)

> 도구가 등록·선택·실행되는 내부 과정과, 흐름을 누가 제어하는지는
> [06. 에이전트 내부 동작 원리](06-agent-internals.md) 에서 자세히 다룹니다.
