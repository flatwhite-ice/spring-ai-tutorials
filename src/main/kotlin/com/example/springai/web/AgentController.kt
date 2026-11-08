package com.example.springai.web

import com.example.springai.agent.AgentService
import com.example.springai.agent.UserContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 에이전트 REST API.
 *
 * 컨트롤러는 HTTP ↔ 서비스 변환만 담당하고, 실제 에이전트 로직은 [AgentService] 에 있습니다.
 *
 * ── 사용자 식별 (학습용 단순화) ─────────────────────────────────────────
 *  여기서는 `X-User-Id` 헤더로 사용자 ID 를 받습니다. 누구나 헤더를 바꿔 보낼 수 있으므로
 *  실제 서비스에서는 이렇게 하면 안 됩니다. Spring Security 를 붙이고 인증 정보에서 꺼내세요.
 *    예) fun chat(@AuthenticationPrincipal jwt: Jwt, ...) { val userId = jwt.subject }
 *  요청 본문(JSON)에 userId 를 받는 것도 같은 이유로 안 됩니다.
 *  어느 쪽이든 사용자 ID 는 AgentService → ToolContext 로만 흐르고, LLM 에는 보내지 않습니다. (docs/09)
 *
 * 사용 예:
 *   curl -X POST localhost:8080/api/agent/chat \
 *        -H 'Content-Type: application/json' -H 'X-User-Id: alice' \
 *        -d '{"message":"지금 몇 시야?"}'
 */
@RestController
@RequestMapping("/api/agent")
class AgentController(private val agentService: AgentService) {

    /** 에이전트와 대화합니다. conversationId 가 없으면 새 대화를 시작합니다. */
    @PostMapping("/chat")
    fun chat(
        // 헤더가 없으면 Spring 이 400 Bad Request 로 응답합니다
        @RequestHeader(USER_HEADER) userId: String,
        @RequestBody request: ChatRequest,
    ): ResponseEntity<ChatResponse> {
        if (!UserContext.isValid(userId) || request.message.isBlank()) return ResponseEntity.badRequest().build()

        val conversationId = request.conversationId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val answer = agentService.chat(userId, conversationId, request.message)
        return ResponseEntity.ok(ChatResponse(conversationId, answer))
    }

    /** 대화 기억을 삭제합니다. 자기 대화만 지울 수 있습니다 (기록 키가 사용자 ID 로 묶여 있음). */
    @DeleteMapping("/conversations/{conversationId}")
    fun reset(
        @RequestHeader(USER_HEADER) userId: String,
        @PathVariable conversationId: String,
    ): ResponseEntity<Void> {
        if (!UserContext.isValid(userId)) return ResponseEntity.badRequest().build()
        agentService.reset(userId, conversationId)
        return ResponseEntity.noContent().build()
    }

    companion object {
        const val USER_HEADER = "X-User-Id"
    }
}
