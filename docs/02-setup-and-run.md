# 02. 설치와 실행

## 준비물
| 항목 | 버전 |
|---|---|
| JDK | 25 |
| Spring Boot | 4.1.x |
| Spring AI | 2.0.2 |
| Ollama | 로컬 실행 중 (`http://localhost:11434`) |

## 1. Ollama 확인
```bash
# Ollama 서버가 떠 있는지 확인
curl http://localhost:11434/api/tags

# 설치된 모델 목록
ollama list

# 이 프로젝트 기본 모델 받기 (약 5GB)
ollama pull qwen3:8b

# 모델이 도구 호출(tools)을 지원하는지 확인 → Capabilities 에 "tools" 가 있어야 함
ollama show qwen3:8b
```

## 2. 모델 설정
`src/main/resources/application.yml`
```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        model: qwen3:8b
        think: false
        temperature: 0.2
```
- `think: false`: qwen3 의 생각(thinking) 모드를 끕니다. 켜면 응답이 약 6배 느려지지만(8.9초 → 1.4초),
  이 프로젝트에서는 도구 선택 정확도에 차이가 없었습니다.
> Spring AI 2.x 부터 `spring.ai.ollama.chat.options.model` 은 deprecated 이고
> `spring.ai.ollama.chat.model` 을 사용합니다.

설정 파일을 고치지 않고 실행 시점에 모델을 바꿀 수도 있습니다.
```bash
./gradlew bootRun --args='--spring.ai.ollama.chat.model=llama3.1:8b'
```

## 3-A. REST API 모드로 실행 (기본)
```bash
./gradlew bootRun
```

모든 요청에 사용자 ID 헤더(`X-User-Id`)가 필요합니다. 없으면 400 으로 응답합니다.
할 일 목록과 대화 기록이 사용자별로 나뉩니다. (학습용 단순화. 실제 서비스는 인증 정보에서 꺼냄 → [09장](09-user-context.md))

```bash
# 새 대화 시작 (conversationId 생략 → 서버가 발급)
curl -s -X POST localhost:8080/api/agent/chat \
  -H 'Content-Type: application/json' -H 'X-User-Id: alice' \
  -d '{"message":"지금 서울은 몇 시야?"}'
# → {"conversationId":"7f3c...","answer":"지금 서울은 20:55입니다."}

# 같은 대화 이어가기 (conversationId 재사용 → 이전 대화를 기억)
curl -s -X POST localhost:8080/api/agent/chat \
  -H 'Content-Type: application/json' -H 'X-User-Id: alice' \
  -d '{"conversationId":"demo-1","message":"우유 사기를 할 일에 추가해줘"}'

curl -s -X POST localhost:8080/api/agent/chat \
  -H 'Content-Type: application/json' -H 'X-User-Id: alice' \
  -d '{"conversationId":"demo-1","message":"할 일 목록 보여줘"}'

# 대화 기억 삭제
curl -s -X DELETE localhost:8080/api/agent/conversations/demo-1 -H 'X-User-Id: alice'

# 다른 사용자(bob)은 alice 의 할 일이 보이지 않습니다
curl -s -X POST localhost:8080/api/agent/chat \
  -H 'Content-Type: application/json' -H 'X-User-Id: bob' \
  -d '{"message":"할 일 목록 보여줘"}'
```

### 시도해볼 질문
| 질문 | 사용되는 도구 |
|---|---|
| 지금 뉴욕은 몇 시야? | `getCurrentDateTime` |
| 서울 2026-11-04T09:00 은 런던 기준 몇 시야? | `convertTimeZone` |
| 123.45 곱하기 678 은? | `multiply` |
| 우유 사기를 할 일에 추가해줘 / 목록 보여줘 / 1번 완료해줘 | `addTodo` / `listTodos` / `completeTodo` |
| 작업 폴더에 어떤 파일 있어? | `listFiles` |
| notes/shopping.txt 읽어줘 | `readFile` |
| ../build.gradle.kts 읽어줘 | `readFile` → **샌드박스에 의해 거부** |
| git status 실행해줘 / hello.txt 의 줄 수를 wc 명령으로 세줘 | `runCommand` (바로 실행) |
| python3 --version 실행해줘 | `runCommand` → 콘솔: 승인 요청, REST: 자동 거부 |
| 스프링이 뭐야? | (도구 없이 답변) |

## 3-B. 콘솔(REPL) 모드로 실행
```bash
./gradlew bootRun --args='--spring.profiles.active=cli'

# 다른 사용자로 실행 (기본값: cli-user)
./gradlew bootRun --args='--spring.profiles.active=cli --agent.cli-user-id=bob'
```
사용자 ID는 REST의 `X-User-Id` 헤더와 같은 역할입니다. 할 일 목록과 대화 기록이 사용자별로 나뉘고, 이 값은 `ToolContext`로 도구에만 전달되며 LLM에는 보내지 않습니다. 형식(영문·숫자·`-`·`_` 1~64자)이 틀리면 안내를 보여 주고 바로 종료합니다. → [09장](09-user-context.md)

```
==============================================
 Spring AI 에이전트 콘솔 (Ollama)
  - 사용자: cli-user
  - 종료: exit   - 대화 초기화: /reset
==============================================

나> 지금 몇 시야?
에이전트> 지금은 2026년 11월 4일 수요일 20시 55분입니다.
```
`cli` 프로파일이 켜지면 `application-cli.yml` 이 추가로 적용되어 웹 서버가 뜨지 않고,
`@Profile("cli")` 가 붙은 `ConsoleChatRunner` 가 실행됩니다.

## 4. 로그로 흐름 관찰하기
도구가 실제로 실행되었는지는 **두 종류의 로그**로 확인합니다. REST·콘솔 모드 모두 출력됩니다.
```
DEBUG ... o.s.a.model.tool.DefaultToolCallingManager : Executing tool call: multiply   ← Spring AI 가 도구 실행
INFO  ... c.e.springai.tools.CalculatorTools        : [Tool] multiply(123.45, 678) -> 83699.1   ← 우리 도구 메서드 실행
```
| 로그 | 출처 | 설정 |
|---|---|---|
| `Executing tool call: <도구명>` | Spring AI `DefaultToolCallingManager` | `logging.level.org.springframework.ai.model.tool: DEBUG` |
| `[Tool] <도구명>(인자) -> 결과` | 우리 도구 클래스 (`log.info`) | `logging.level.com.example.springai` (INFO 이상) |
| LLM 요청/응답 전문 | `SimpleLoggerAdvisor` | `logging.level.org.springframework.ai.chat.client.advisor: DEBUG` (REST 모드만 기본 ON) |
| `[Agent] ...` 사용자 메시지/최종 답변 | `AgentService` | REST 모드만 기본 ON |
| `[Metrics] LLM 1회 / 요청 전체: …ms, 토큰 …` | `MetricsAdvisor` ([08장](08-custom-advisors.md)) | `logging.level.com.example.springai` (INFO 이상) |
| `[Guard] … 거절했습니다` | `SecretGuardAdvisor` ([08장](08-custom-advisors.md)) | 위와 같음 (WARN) |

> **팁:** 답변이 그럴듯해도 위 두 도구 로그가 **없다면** 모델이 도구를 쓰지 않고 지어낸 답입니다.
> (원인은 아래 "문제 해결" 참고: 모델이 tool_calls 를 텍스트로 출력했거나, 대화 기억만 보고 답한 경우)

콘솔 모드에서 LLM 요청/응답 전문까지 보고 싶다면:
```bash
./gradlew bootRun --args='--spring.profiles.active=cli --logging.level.org.springframework.ai.chat.client.advisor=DEBUG'
```

## 5. 테스트
```bash
./gradlew test
```
도구 단위 테스트는 LLM 없이 동작합니다. (`src/test/kotlin/.../tools/`)

## 문제 해결 (Troubleshooting)

### 답변에 `{"name": "multiply", "arguments": {...}}` 같은 JSON/XML 이 그대로 나와요
모델이 도구 호출을 Ollama 의 구조화된 `tool_calls` 필드가 아니라 **일반 텍스트(content)로 출력**한 것입니다.
Spring AI 는 `tool_calls` 필드가 있을 때만 도구를 실행하므로, 이 경우 텍스트가 그대로 답변이 됩니다.

직접 확인하는 방법:
```bash
curl -s localhost:11434/api/chat -d '{
  "model": "qwen2.5-coder:7b", "stream": false,
  "messages": [{"role":"user","content":"123.45 곱하기 678 은?"}],
  "tools": [{"type":"function","function":{"name":"multiply","description":"두 수를 곱합니다",
    "parameters":{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"string"}},"required":["a","b"]}}}]
}'
```
- 정상: `"message": {"content": "", "tool_calls": [{"function": {"name": "multiply", ...}}]}`
- 비정상: `"message": {"content": "{\"name\": \"multiply\", ...}"}` ← tool_calls 없음

이 프로젝트에서 실제 측정한 결과 (도구 12개 + 한국어 시스템 프롬프트, 질문 5개 × 3회, 셸 도구 추가 전):
| 모델 | 성공 | 평균 응답 | 결과 |
|---|---|---|---|
| **`qwen3:8b` (think=false)** | **15/15** | 1.4초 | **채택.** 모든 질문에서 정확한 도구 선택 |
| `qwen3:8b` (think=true) | 15/15 | 8.9초 | 정확하지만 느림 |
| `qwen3-coder:30b` | 6/15 | 1.0초 | 아래 분석 참고 |
| `qwen2.5-coder:7b` | 0/15 | - | 항상 content 에 JSON 텍스트로 출력 → **도구 실행 안 됨** |

| 모델 (이전 측정) | 결과 |
|---|---|
| `qwen3-coder:30b` | 질문에 따라 성공/실패가 갈림. 실패 시 `<function=readFile>…</function></tool_call>` 이 텍스트로 나옴 |

`qwen3-coder:30b` 실패 원인 분석 (Ollama 0.24.0, Spring AI 가 보낸 실제 요청을 캡처해 재전송):
- 모델 출력에 **여는 태그 `<tool_call>` 이 빠지고** 닫는 태그만 있음 → Ollama 파서가 도구 호출로 인식하지 못해 content 로 넘김
- 도구 정의(JSON 스키마)는 정상. 도구를 1개만 보내면 항상 성공
- 시스템 프롬프트 유무·언어, 질문 문구에 따라 결과가 달라지며, 어떤 조합도 모든 질문에서 안정적이지 않음

| 질문 (도구 12개 전송, 각 3회) | 한국어 system | 영어 system | system 없음 |
|---|---|---|---|
| hello.txt 읽어줘 | 0/3 | 3/3 | 3/3 |
| 할 일 목록 보여줘 | 0/3 | 0/3 | 0/3 |
| 작업 폴더에 어떤 파일 있어? | 3/3 | 0/3 | 3/3 |
| 123.45 곱하기 678 은? | 3/3 | 3/3 | 3/3 |
| 우유 사기를 할 일에 추가해줘 | 0/3 | 0/3 | 3/3 |

→ 코드(도구 구현)의 문제가 아니라 **모델 + Ollama 파서 조합의 도구 호출 형식 불안정** 문제입니다.

해결: 도구 호출이 안정적인 모델(`qwen3:8b`)로 교체하세요. (`ollama show <모델>` 에서 `tools` capability 확인 후,
위 curl 로 `tool_calls` 가 나오는지 직접 확인하는 것이 가장 확실합니다.)

### 대화가 몇 턴 지나면 도구를 안 부르고 답을 지어내요 (해결됨)
**증상:** 첫 두 질문은 도구를 잘 쓰다가, 세 번째부터 `<file>안녕하세요! 이는 hello.txt 파일의 내용입니다.</file>` 처럼
도구 로그 없이 그럴듯한 가짜 답을 냄.

**원인:** 기본 구성에서는 도구 호출 루프가 ChatModel *안에서* 돌기 때문에, `MessageChatMemoryAdvisor` 는
최종 질문/답변만 저장하고 중간의 tool_calls·도구 결과는 저장하지 못합니다. 그러면 대화 기록이
"질문 → 바로 답변" 패턴만으로 채워지고, 모델이 이를 흉내 내어 도구 없이 답합니다.
같은 질문을 실측하면: 대화 기록 2턴 포함 **0/4**, 기록 제거 **4/4**.

**해결:** `ToolCallingAdvisor` 로 도구 루프를 어드바이저 체인으로 꺼내고, 메모리 어드바이저를 그 *안쪽*에 배치
(`AgentConfig.kt`). 이제 기억에 도구 메시지까지 저장되어 9턴 대화 내내 도구를 정상 호출합니다.
자세한 구조는 [03. 요청 처리 흐름](03-request-flow.md) 참고.

### 첫 응답이 매우 느려요
Ollama 가 모델을 메모리에 올리는 시간입니다. 두 번째 요청부터 빨라집니다.
타임아웃은 `spring.http.clients.read-timeout: 5m` 으로 넉넉히 잡아 두었습니다.
