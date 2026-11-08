package com.example.springai.agent

import com.example.springai.advisor.AdvisorOrders
import com.example.springai.tools.TodoTools
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor
import org.springframework.ai.chat.memory.MessageWindowChatMemory
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.support.ToolCallbacks
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 사용자 식별자가 LLM 에게는 보이지 않고 도구에는 전달되는지 검증합니다.
 *
 * 가짜 모델이 실제 LLM 처럼 tool_call 을 돌려주도록 만들어, ToolCallingAdvisor 의 루프
 * (모델 → 도구 실행 → 모델)를 그대로 거치게 합니다. Ollama 는 필요 없습니다.
 */
class UserContextTest {

    /**
     * "추가: X" → addTodo(title=X) 도구 호출, "목록" → listTodos 도구 호출을 요청하는 가짜 모델.
     * 도구 결과(role=tool)를 받으면 그 결과를 그대로 최종 답변으로 돌려줍니다.
     */
    private class ScriptedModel : ChatModel {
        val prompts = mutableListOf<Prompt>()

        // ChatClient 가 이 옵션을 바탕으로 도구 목록·ToolContext 를 담습니다. 도구 호출을 지원하는 옵션이어야 합니다.
        override fun getOptions(): ChatOptions = ToolCallingChatOptions.builder().build()

        override fun call(prompt: Prompt): ChatResponse {
            prompts += prompt
            val last = prompt.instructions.last()
            val userText = prompt.userMessage.text.orEmpty()
            val message = when {
                last is ToolResponseMessage -> AssistantMessage(last.responses.joinToString { it.responseData() })
                userText.startsWith("추가: ") ->
                    toolCall("addTodo", """{"title":"${userText.removePrefix("추가: ")}"}""")
                userText == "목록" -> toolCall("listTodos", "{}")
                else -> AssistantMessage("일반 답변")
            }
            return ChatResponse(listOf(Generation(message)))
        }

        private fun toolCall(name: String, args: String) = AssistantMessage.builder()
            .content("")
            .toolCalls(listOf(AssistantMessage.ToolCall("call-1", "function", name, args)))
            .build()

        /** 이 모델에 "보내진" 모든 것: 메시지 본문 + 도구 정의(이름·설명·JSON 스키마) */
        fun everythingSentToLlm(): String = prompts.joinToString("\n") { p ->
            val messages = p.instructions.joinToString("\n") { it.text.orEmpty() }
            val tools = (p.options as ToolCallingChatOptions).toolCallbacks.orEmpty()
                .joinToString("\n") { it.toolDefinition.let { d -> d.name() + d.description() + d.inputSchema() } }
            messages + "\n" + tools
        }
    }

    private val model = ScriptedModel()
    private val memory = MessageWindowChatMemory.builder().build()
    private val todoTools = TodoTools()

    private val service = AgentService(
        ChatClient.builder(model)
            .defaultAdvisors(
                ToolCallingAdvisor.builder().toolCallingManager(ToolCallingManager.builder().build())
                    .conversationHistoryEnabled(false).build(),
                MessageChatMemoryAdvisor.builder(memory).order(AdvisorOrders.MEMORY).build(),
            )
            .defaultTools(todoTools)
            .build(),
        memory,
    )

    @Test
    fun `도구에는 사용자 ID 가 전달되고 사용자별로 나뉜다`() {
        assertTrue(service.chat("alice", "c1", "추가: 우유 사기").contains("#1 우유 사기"))
        service.chat("alice", "c1", "추가: 운동하기")

        // bob 의 목록 조회: alice 의 할 일이 보이지 않음 (도구 결과 "[]" 가 그대로 답변으로 옴)
        assertEquals("[]", service.chat("bob", "c1", "목록"))
        assertEquals(2, todoTools.listTodos(null, ToolContext(UserContext.of("alice"))).size)
    }

    @Test
    fun `LLM 에 보내는 메시지와 도구 스키마에는 사용자 ID 가 없다`() {
        service.chat("alice-7f3a", "c1", "추가: 우유 사기")

        assertEquals(2, model.prompts.size, "도구 요청 1회 + 최종 답변 1회")
        val sent = model.everythingSentToLlm()
        assertFalse(sent.contains("alice-7f3a"), "사용자 ID 가 LLM 요청에 들어가면 안 됨")
        assertFalse(sent.contains("ToolContext") || sent.contains("ctx"), "ToolContext 매개변수가 스키마에 보이면 안 됨")
    }

    @Test
    fun `도구 JSON 스키마에는 LLM 이 채울 매개변수만 있다`() {
        val schemas = ToolCallbacks.from(todoTools).associate { it.toolDefinition.name() to it.toolDefinition.inputSchema() }

        assertTrue(schemas.getValue("addTodo").contains("\"title\""))
        schemas.values.forEach { assertFalse(it.contains("ctx") || it.contains("userId"), it) }
    }

    @Test
    fun `같은 conversationId 라도 사용자가 다르면 대화 기록이 섞이지 않는다`() {
        service.chat("alice", "shared-id", "내 비밀번호 힌트는 고양이 이름이야")
        model.prompts.clear()

        service.chat("bob", "shared-id", "아까 내가 뭐라고 했지?")

        assertFalse(model.everythingSentToLlm().contains("고양이"), "bob 의 요청에 alice 의 대화가 붙으면 안 됨")
    }

    @Test
    fun `사용자 ID 형식이 이상하면 거부한다`() {
        // "a:b" + "c" 와 "a" + "b:c" 가 같은 기록 키가 되는 것을 막기 위해 ':' 를 허용하지 않음
        listOf("", "a:b", "a b", "x".repeat(65)).forEach { assertFalse(UserContext.isValid(it), it) }
        assertTrue(UserContext.isValid("cli-user"))
    }
}
