package com.example.springai.web

import com.example.springai.agent.AgentService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 에이전트 REST API.
 *
 * 컨트롤러는 HTTP ↔ 서비스 변환만 담당하고, 실제 에이전트 로직은 [AgentService] 에 있습니다.
 *
 * 사용 예:
 *   curl -X POST localhost:8080/api/agent/chat \
 *        -H 'Content-Type: application/json' \
 *        -d '{"message":"지금 몇 시야?"}'
 */
@RestController
@RequestMapping("/api/agent")
class AgentController(private val agentService: AgentService) {

    /** 에이전트와 대화합니다. conversationId 가 없으면 새 대화를 시작합니다. */
    @PostMapping("/chat")
    fun chat(@RequestBody request: ChatRequest): ResponseEntity<ChatResponse> {
        if (request.message.isBlank()) return ResponseEntity.badRequest().build()

        val conversationId = request.conversationId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val answer = agentService.chat(conversationId, request.message)
        return ResponseEntity.ok(ChatResponse(conversationId, answer))
    }

    /** 대화 기억을 삭제합니다. */
    @DeleteMapping("/conversations/{conversationId}")
    fun reset(@PathVariable conversationId: String): ResponseEntity<Void> {
        agentService.reset(conversationId)
        return ResponseEntity.noContent().build()
    }
}
