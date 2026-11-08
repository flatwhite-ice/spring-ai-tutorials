# Spring AI 에이전트 예제 (Ollama)

Spring AI 2.0 + 로컬 Ollama 로 만든 **도구 호출(Tool Calling) 에이전트** 학습용 프로젝트입니다.
에이전트는 날짜/시간, 계산기, 할 일(TODO), 파일 읽기, 셸 명령 도구를 스스로 골라 사용합니다.
셸 명령 도구는 허용 목록·사람 승인·실행 제한과 OS 샌드박스(macOS Seatbelt)로 보호됩니다.

## 빠른 시작
```bash
# 1. Ollama 실행 확인 및 모델 받기
curl http://localhost:11434/api/tags
ollama pull qwen3:8b

# 2-A. REST API 모드
./gradlew bootRun
curl -s -X POST localhost:8080/api/agent/chat -H 'Content-Type: application/json' \
  -d '{"message":"123.45 곱하기 678 은?"}'

# 2-B. 콘솔 대화 모드
./gradlew bootRun --args='--spring.profiles.active=cli'
```

## 문서
1. [프로젝트 개요](docs/01-overview.md): 에이전트란?, 전체 구조
2. [설치와 실행](docs/02-setup-and-run.md): 실행법, 예시 질문, 문제 해결
3. [요청 처리 흐름](docs/03-request-flow.md): 시퀀스 다이어그램, Ollama `/api/chat` 페이로드
4. [Spring AI 핵심 개념](docs/04-spring-ai-concepts.md): ChatModel, ChatClient, Advisor, ChatMemory, Tool
5. [도구 설명과 추가 방법](docs/05-tools.md)
6. [에이전트 내부 동작 원리](docs/06-agent-internals.md): 흐름 제어(LLM vs Advisor), 도구 인식 4단계
7. [셸 명령 도구와 보안](docs/07-shell-tool-security.md): 허용/승인/거부 정책, 자연어 명령 추론(AGENTS.md), OS 샌드박스, 한계
8. [어드바이저 직접 만들기](docs/08-custom-advisors.md): 측정·시각 주입·가드레일 예제, 체인 위치와 프롬프트 캐시

## 기술 스택
Kotlin · Spring Boot 4.1 · Spring AI 2.0.2 · Ollama (`qwen3:8b`) · JDK 25
