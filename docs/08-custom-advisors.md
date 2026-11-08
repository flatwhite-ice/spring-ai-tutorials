# 08. 어드바이저 직접 만들기

[04장](04-spring-ai-concepts.md)에서 어드바이저는 "ChatClient 호출 앞뒤에 끼어드는 인터셉터"라고 설명했습니다.
이 장에서는 어드바이저를 직접 3개 만들어 체인에 끼우고, **체인 안의 위치에 따라 동작이 어떻게 달라지는지** 실제 로그로 확인합니다.

| 예제 | 파일 | 만드는 방법 | 하는 일 |
|---|---|---|---|
| A | `advisor/MetricsAdvisor.kt` | `CallAdvisor` (감싸기) | 소요 시간·토큰 측정. 같은 클래스를 두 위치에 등록 |
| B | `advisor/CurrentTimeAdvisor.kt` | `BaseAdvisor` (before) | 매 요청에 현재 시각 주입 |
| C | `advisor/SecretGuardAdvisor.kt` | `CallAdvisor` + `StreamAdvisor` (short-circuit) | 비밀키가 보이면 모델을 부르지 않고 거절 |

순서 값은 `advisor/AdvisorOrders.kt`에 모았고, 등록은 `AgentConfig.buildAdvisors()`에서 합니다.

---

## 1. 어드바이저를 만드는 두 가지 방법

| 인터페이스 | 구현할 메서드 | 언제 쓰나 |
|---|---|---|
| `CallAdvisor` | `adviseCall(request, chain)` | 앞뒤를 직접 제어. `chain.nextCall()`을 **부르지 않으면 모델 호출을 건너뜀** |
| `BaseAdvisor` | `before(request, chain)`, `after(response, chain)` | 요청·응답을 살짝 고치기만 할 때. `stream()` 도 자동 지원 |

둘 다 `getName()`과 `getOrder()`가 필요합니다. `BaseAdvisor`는 `getName()` 기본 구현(클래스 이름)이 있습니다.

`CallAdvisor`의 모양은 서블릿 `Filter`나 AOP `@Around`와 같습니다.

```kotlin
override fun adviseCall(request: ChatClientRequest, chain: CallAdvisorChain): ChatClientResponse {
    // ① 요청 전 처리
    val response = chain.nextCall(request)   // ② 안쪽 어드바이저 → … → 모델
    // ③ 응답 후 처리
    return response
}
```

주고받는 객체 두 개:

- `ChatClientRequest(prompt, context)`: `prompt`는 보낼 메시지 목록과 옵션이고, `context`는 어드바이저끼리 값을 넘기는 Map입니다. `ChatMemory.CONVERSATION_ID`도 이 Map으로 전달됩니다.
- `ChatClientResponse(chatResponse, context)`: `chatResponse`에 모델 답변, 토큰 사용량, tool_calls가 들어 있습니다.

`ChatClientRequest`와 `Prompt`는 **불변(immutable)** 입니다. 고칠 때는 `mutate()`나 `augmentSystemMessage()` 같은 메서드로 복사본을 만들어 돌려줍니다.

---

## 2. 체인 안의 위치가 동작을 결정한다

```
order 작음 ───────────────────────────────────────────────────────────────────────▶ 큼

요청 → SecretGuard → Metrics(요청 전체) → ToolCalling ┐→ Memory → CurrentTime → Metrics(LLM 1회) → Logger → Ollama
       HIGHEST       DEFAULT-1           DEFAULT      │  +1        +2              +3                +4
                                                      └─ 여기부터 오른쪽은 도구 호출 루프의 "매 회차"마다 실행
```

`ToolCallingAdvisor`는 모델이 tool_calls를 주면 도구를 실행하고, **자기보다 안쪽 체인을 다시 호출**합니다([06장](06-agent-internals.md)). 그래서 어디에 두느냐에 따라 실행 횟수가 다릅니다.

| 위치 | 실행 횟수 | 이 프로젝트에서 그 위치에 둔 이유 |
|---|---|---|
| ToolCalling 바깥 | 질문당 1번 | 가드레일은 메모리·도구 전에 막아야 하고, 전체 측정은 한 번만 찍으면 됨 |
| ToolCalling 안쪽 | LLM 호출마다 | 메모리는 tool_calls까지 저장해야 하고, 회차별 측정이 필요 |
| Memory보다 안쪽 | LLM 호출마다, 기록에는 안 남음 | 매번 새로 붙여야 하는 정보(현재 시각)는 대화 기록에 저장하면 안 됨 |
| 가장 안쪽 | LLM 호출마다 | 로거가 "주입이 끝난 최종 프롬프트"를 보게 하려고 |

리스트에 넣은 순서는 상관없습니다. 각 어드바이저의 `getOrder()` 값으로 정렬됩니다.

---

## 3. 예제 A: 측정 (`MetricsAdvisor`)

`chain.nextCall()` 앞뒤로 시간을 재고, 응답의 `metadata.usage`에서 토큰 수를 꺼냅니다. **같은 클래스를 두 위치에 등록**했습니다.

```kotlin
add(MetricsAdvisor("요청 전체", AdvisorOrders.REQUEST_METRICS))  // ToolCalling 바깥
add(MetricsAdvisor("LLM 1회",  AdvisorOrders.ROUND_METRICS))    // ToolCalling 안쪽
```

"뉴욕은 지금 몇 시야?"를 보냈을 때의 실제 로그입니다.

```
[Metrics] LLM 1회: 3188ms, 토큰 입력 2272 / 출력 55, 모델이 도구 호출을 요청함
[Tool] convertTimeZone(2026-11-06T23:25, Asia/Seoul -> America/New_York) -> 2026-11-06 09:25:00 (금요일) EST
[Metrics] LLM 1회: 2601ms, 토큰 입력 2361 / 출력 37
[Metrics] 요청 전체: 5802ms, 토큰 입력 4633 / 출력 92
```

- 안쪽 측정기는 LLM 호출마다 1줄씩, 모두 2줄을 찍었습니다.
- 바깥 측정기의 토큰은 두 회차의 **합계**입니다(2272+2361=4633). `ToolCallingAdvisor`가 회차별 사용량을 더해서(`UsageAccumulator`) 돌려주기 때문입니다.
- 매 회차 입력이 2,200토큰을 넘습니다. 대부분 시스템 프롬프트와 도구 정의 13개입니다. 짧은 "안녕"도 이만큼 보냅니다.

이름이 겹치지 않도록 `getName()`에 label을 넣었습니다. `stream()`에는 적용되지 않습니다. `CallAdvisor`만 구현했기 때문입니다(5장 참고).

---

## 4. 예제 B: 현재 시각 주입 (`CurrentTimeAdvisor`)

`BaseAdvisor.before()`에서 프롬프트를 고칩니다.

```kotlin
override fun before(request: ChatClientRequest, chain: AdvisorChain): ChatClientRequest {
    val stamp = "[현재 시각: 2026-11-06 (금) 23:25 (Asia/Seoul)]"
    val prompt = request.prompt()
        .augmentSystemMessage { it.mutate().text(it.text + RULE).build() }       // 고정 규칙
        .augmentUserMessage { it.mutate().text(stamp + "\n" + it.text).build() } // 바뀌는 값
    return request.mutate().prompt(prompt).build()
}
```

`application.yml`의 `agent.inject-current-time`으로 켜고 끌 수 있습니다. 끄면 이 어드바이저가 등록되지 않습니다.

### 4-1. 효과: 질문 6개 × 2회, 켜기/끄기 비교

| 질문 | 켬: LLM 호출 / 도구 / 시간 | 끔: LLM 호출 / 도구 / 시간 |
|---|---|---|
| 오늘 무슨 요일이야? | 1회 / 없음 / 0.6초 | 2회 / getCurrentDateTime / 3.3초 |
| 지금 몇 시야? | 1회 / 없음 / 1.0~1.1초 | 2회 / getCurrentDateTime / 4.0초 |
| 뉴욕은 지금 몇 시야? | 2회 / convertTimeZone / 5.8초 | 2회 / getCurrentDateTime / 4.4초 |
| 123.45 곱하기 678 은? | 2회 / multiply / 3.6초 | 2회 / multiply / 3.6초 |
| '우유'가 들어간 파일 찾아줘 | 2회 / runCommand / 3.2초 | 2회 / runCommand / 3.1초 |
| 안녕 | 1회 / 없음 / 1.4초 | 1회 / 없음 / 1.4초 |

(앱 시작 직후 첫 요청은 모델 로딩 때문에 8~10초 걸려서 표에서 뺐습니다.)

- 두 경우 모두 12번 전부 맞게 답했습니다.
- 서울 시각 질문은 도구 호출이 사라지고 LLM 호출이 절반으로 줄었습니다.
- 뉴욕 질문은 주입된 서울 시각을 `convertTimeZone`에 넣는 방식으로 바뀌었습니다.
- 대신 정밀도가 **분 단위**입니다. 끄면 도구가 "23시 27분 13초"처럼 초까지 알려 줍니다.

### 4-2. 시행착오: 바뀌는 값은 프롬프트 뒤쪽에

처음에는 시각을 **시스템 메시지 끝**에 붙였습니다. 그러자 분이 바뀐 뒤 첫 요청이 가끔 9초씩 걸렸습니다. Ollama에 같은 요청을 직접 보내 프롬프트 처리 시간(`prompt_eval_duration`)을 쟀습니다. 응답은 1토큰만 받았습니다.

| 시각을 붙인 위치 | 요청 | 프롬프트 처리 시간 |
|---|---|---|
| 시스템 메시지 끝 | 시각 A | 70ms |
| | 같은 시각 A 반복 | 52ms |
| | **시각 B (1분 뒤)** | **7,316ms** |
| | 같은 시각 B, 질문만 다름 | 273ms |
| user 메시지 앞 | 시각 B (1분 뒤) | 594ms |
| | 시각 C (2분 뒤) | 577ms |

이유는 Ollama의 **프롬프트 캐시(KV 캐시)** 에 있습니다.

- Ollama는 직전 요청과 **앞부분이 같으면** 그 부분의 계산 결과를 재사용하고, 달라진 지점부터 다시 계산합니다.
- qwen3의 채팅 템플릿은 실제로 다음 순서로 문자열을 만듭니다.

  ```
  [시스템 메시지] [도구 정의 13개 ≈ 1,500토큰] [대화 기록] [user 메시지]
  ```
- 시스템 메시지 끝이 바뀌면 그 뒤의 **도구 정의부터 전부 다시** 계산해야 합니다.
- user 메시지 앞에 붙이면 바뀐 지점 앞의 내용(시스템 메시지, 도구 정의)은 그대로 재사용됩니다.

그래서 다음처럼 나눴습니다.

- **시스템 메시지**: 항상 같은 규칙 문장(`RULE`)만 붙입니다. "user 메시지 앞의 `[현재 시각]`을 쓰고 도구는 부르지 마라"는 내용입니다.
- **마지막 user 메시지 앞**: `[현재 시각: …]`. 매분 바뀌는 값입니다.

> 원칙: **자주 바뀌는 값일수록 프롬프트 뒤쪽에** 둡니다. 클라우드 API의 프롬프트 캐싱도 "앞부분 일치" 방식이라 똑같이 적용됩니다.

### 4-3. 대화 기록과의 관계

이 어드바이저는 메모리 어드바이저보다 **안쪽**(order가 더 큼)에 있습니다.

- 메모리 어드바이저는 주입 **전**의 user 메시지를 저장합니다. 그래서 대화 기록에는 `[현재 시각]`이 남지 않습니다. 단위 테스트로 확인했습니다.
- 만약 바깥에 두면 기록에 옛날 시각이 계속 쌓여서, 모델이 어느 시각이 맞는지 헷갈릴 수 있습니다.
- 도구 루프 2회차에도 실행되지만, 시각이 두 번 붙지는 않습니다. `ToolCallingAdvisor`가 다음 회차 요청을 자기가 받은 **주입 전** 요청으로 만들기 때문입니다. 실제 요청을 캡처해서 확인했습니다.

### 4-4. 정확도까지 고친다: "오늘 며칠이야?"

나중에 질문을 늘려 다시 비교하다가, 시각 주입을 **끈** 상태에서 "오늘 며칠이야?"가 틀린다는 것을 발견했습니다. 모델이 도구를 부르지 않고 날짜를 지어냈습니다.

| 질문 (각 2회) | 켬 | 끔 |
|---|---|---|
| 오늘 무슨 요일이야? | 2/2, 도구 없음 | 2/2, getCurrentDateTime |
| 지금 몇 시야? | 2/2, 도구 없음 | 2/2, getCurrentDateTime |
| **오늘 며칠이야?** | **2/2, 도구 없음** | **0/2, 도구 없이 "2023년 10월 5일"** |
| 뉴욕은 지금 몇 시야? | 2/2, convertTimeZone | 2/2, getCurrentDateTime |
| 런던 시간 알려줘 | 2/2, getCurrentDateTime 또는 convertTimeZone | 2/2, getCurrentDateTime |

qwen3:8b는 "요일"과 "몇 시"는 날짜 도구와 연결하지만, "며칠"은 연결하지 못했습니다. 끈 상태에서 고쳐 보려고 한 번에 하나씩 바꿔 시험했습니다. 모두 0/2였습니다.

| 시도 | "며칠" 결과 (끔) |
|---|---|
| 원래 상태 | "2026년 11월 4일" ← `convertTimeZone` 인자 설명의 예시 값 `2026-11-04T09:30` |
| 인자 설명에서 예시 날짜를 빼고 형식만 남김 | "2023-10-05" (학습 시점 근처의 날짜) |
| 시스템 규칙을 "반드시 날짜/시간 도구를 사용하세요"로 강하게 | "2023-10-05" |
| 도구 설명에 "오늘 날짜(오늘 며칠인지)"를 추가 | "2023년 10월 5일" |
| 도구 설명에 "[현재 시각]이 주어졌으면 그 값을 쓰라"는 단서 추가 | 0/2. 게다가 "요일"까지 "금요일"로 틀림 (주어지지 않은 시각이 있는 것처럼 행동) |

배운 점 세 가지:

- **문구 조정으로 안 되는 것은 구조로 해결합니다.** 시각을 매 요청에 넣어 주면 모델이 "도구를 부를지" 판단할 필요가 없어서, 같은 질문이 2/2로 맞습니다. `CurrentTimeAdvisor`는 속도만이 아니라 정확도도 고칩니다.
- **도구 정의 속 예시 값은 사실처럼 새어 나옵니다.** 예시 날짜가 있을 때의 오답("11월 4일")은 그럴듯해서 더 위험했습니다. 인자 설명에는 형식만 적도록 바꿨습니다(`DateTimeTools.convertTimeZone`).
- **도구 설명은 어드바이저를 몰라야 합니다.** 주입 여부에 따라 달라지는 규칙은 어드바이저가 붙이는 문장(`RULE`)에만 둡니다. 도구 설명에 조건을 섞으면, 그 조건이 거짓인 상황에서 모델이 혼란스러워했습니다.

---

## 5. 예제 C: 가드레일 (`SecretGuardAdvisor`)

사용자 메시지에 AWS 액세스 키, PEM 개인 키, GitHub 토큰, `sk-…` 형식 API 키가 보이면 **`chain.nextCall()`을 부르지 않고** 직접 만든 응답을 돌려줍니다.

```kotlin
override fun adviseCall(request: ChatClientRequest, chain: CallAdvisorChain): ChatClientResponse {
    val found = detect(request) ?: return chain.nextCall(request) // 통과
    return blockedResponse(request, found)                         // 여기서 끝: 모델 호출 없음
}
```

실제 결과:

```
요청: 이 키로 S3 접속해줘 AKIAIOSFODNN7EXAMPLE
응답: 메시지에 AWS 액세스 키 로 보이는 값이 있어 처리하지 않았습니다. 비밀키는 대화에 붙여 넣지 말고, ...
로그: [Guard] 사용자 메시지에서 AWS 액세스 키 형식을 발견해 모델 호출 없이 거절했습니다.
      ([Metrics] 로그 없음: 측정기보다 바깥에서 끝났기 때문)
```

### 5-1. 왜 가장 바깥인가

- **메모리보다 바깥**: 비밀키가 대화 기록에 저장되지 않습니다. 다음 질문 때 다시 모델로 보내지는 일도 없습니다.
- **로거보다 바깥**: `SimpleLoggerAdvisor`의 DEBUG 로그에도 남지 않습니다. 가드 자신의 로그에도 종류만 남기고 원문은 남기지 않습니다.

같은 대화에서 "방금 내가 뭐라고 했지?"라고 물었더니, 모델은 기억에 아무것도 없어서 엉뚱하게 **시스템 프롬프트 내용을 요약해** 답했습니다. 기록이 정말 비어 있다는 증거이긴 합니다. 하지만 시스템 프롬프트는 이렇게 쉽게 새어 나간다는 점도 함께 보여 줍니다. 시스템 프롬프트에 비밀을 두면 안 되는 이유입니다.

### 5-2. 왜 `StreamAdvisor`도 구현했나

`ChatClient`는 `call()`에는 `CallAdvisor`만, `stream()`에는 `StreamAdvisor`만 적용합니다.

- `CallAdvisor`만 구현하면, 누군가 나중에 스트리밍 API를 추가할 때 가드레일이 **조용히 빠집니다**.
- 측정기는 빠져도 로그만 없어지지만, 보안 장치는 두 경로를 모두 막아야 합니다.

단위 테스트 `stream 호출에서도 가드레일이 적용된다`로 확인합니다.

`BaseAdvisor`는 둘 다 자동으로 지원합니다. 다만 `before()`는 요청을 고칠 수만 있고 체인을 끊을 수는 없어서, 가드레일에는 쓰지 않았습니다.

### 5-3. 한계

- 정규식으로 잡을 수 있는 **형식이 정해진 키만** 막습니다. 비밀번호나 사내 토큰은 못 잡습니다.
- **사용자 입력만** 검사합니다. 도구 결과(예: `cat`으로 읽은 파일 내용)에 들어 있는 비밀키는 이 어드바이저를 지나가지 않습니다. 그쪽은 셸 샌드박스의 `denied-read-paths`가 맡습니다([07장](07-shell-tool-security.md)).
- 금칙어 목록만으로 충분하면 내장된 `SafeGuardAdvisor`를 써도 됩니다.

---

## 6. 요청별 값 넘기기: `context`

`AgentService`는 이미 이 방식으로 대화 ID를 넘깁니다. 같은 통로로 다른 값도 넘길 수 있습니다.

```kotlin
// 호출하는 쪽
chatClient.prompt().user(msg)
    .advisors { it.param(ChatMemory.CONVERSATION_ID, id).param("userRole", "guest") }
    .call().content()

// 어드바이저 안
val role = request.context()["userRole"] as? String
```

사용자별 권한 처리, A/B 테스트용 프롬프트 선택 같은 데 씁니다. 이 프로젝트에는 구현하지 않았습니다.

이 값은 **어드바이저**만 봅니다. 사용자 ID처럼 **도구**가 알아야 하는 값은 `.toolContext(...)`로 넘깁니다. 두 통로 모두 LLM에는 전달되지 않습니다. → [09장](09-user-context.md)

---

## 7. 테스트: LLM 없이 체인 검증

`CustomAdvisorsTest`는 실제 Ollama 대신 **가짜 `ChatModel`** 을 씁니다. 이 가짜 모델은 받은 프롬프트를 기록하고 고정 답변을 돌려줍니다. `ChatClient`는 `ChatModel` 인터페이스만 알기 때문에 이렇게 바꿔 끼울 수 있습니다.

```kotlin
private class FakeChatModel : ChatModel {
    val prompts = mutableListOf<Prompt>()
    override fun call(prompt: Prompt): ChatResponse {
        prompts += prompt
        return ChatResponse(listOf(Generation(AssistantMessage("모델 답변"))))
    }
}
```

| 테스트 | 확인하는 것 |
|---|---|
| 비밀키 → 거절 | 답변에 종류가 나오고, 모델 호출이 0회이며, 메모리가 비어 있음 |
| stream 경로 | `stream()`으로 불러도 차단됨 |
| 평범한 메시지 | "AKIA 가 뭐야?"는 통과 (오탐 없음) |
| 시각 주입 | 시스템 메시지 = 원래 규칙 + 고정 `RULE`, user 메시지 = `[현재 시각…]` + 원문, 메모리에는 원문만 |
| 측정 | 응답을 바꾸지 않음 |

```bash
./gradlew test --tests "com.example.springai.advisor.*"
```

---

## 8. 정리

- 어드바이저는 **무엇을 하느냐보다 어디에 두느냐**가 더 중요합니다. 같은 `MetricsAdvisor`가 위치에 따라 질문 단위가 되기도 하고 LLM 호출 단위가 되기도 했습니다.
- 막는 어드바이저는 **바깥**에, 매번 새로 붙이는 어드바이저는 **메모리보다 안쪽**에, 관찰하는 어드바이저는 **보고 싶은 지점**에 둡니다.
- 프롬프트를 고칠 때는 **캐시**를 생각합니다. 바뀌는 값은 뒤쪽에 둡니다.
- 보안용 어드바이저는 `call`과 `stream` **두 경로를 모두** 구현합니다.
