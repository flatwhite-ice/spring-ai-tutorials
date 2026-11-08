package com.example.springai.advisor

import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor
import org.springframework.ai.chat.memory.ChatMemory
import org.springframework.ai.chat.memory.MessageWindowChatMemory
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 직접 만든 어드바이저 테스트.
 *
 * 실제 Ollama 대신 "받은 프롬프트를 기록하고 고정 답변을 돌려주는" 가짜 ChatModel 을 씁니다.
 * ChatClient 는 ChatModel 인터페이스만 알기 때문에, 이렇게 바꿔 끼우면 어드바이저 체인을 LLM 없이 검증할 수 있습니다.
 */
class CustomAdvisorsTest {

    /** 호출될 때마다 프롬프트를 기록하는 가짜 모델 */
    private class FakeChatModel : ChatModel {
        val prompts = mutableListOf<Prompt>()
        override fun call(prompt: Prompt): ChatResponse {
            prompts += prompt
            return ChatResponse(listOf(Generation(AssistantMessage("모델 답변"))))
        }
    }

    private val model = FakeChatModel()
    private val memory = MessageWindowChatMemory.builder().build()

    private fun client(vararg advisors: org.springframework.ai.chat.client.advisor.api.Advisor) =
        ChatClient.builder(model)
            .defaultSystem("기본 규칙")
            .defaultAdvisors(
                MessageChatMemoryAdvisor.builder(memory).order(AdvisorOrders.MEMORY).build(),
                *advisors,
            )
            .build()

    private fun ask(client: ChatClient, text: String): String? =
        client.prompt().user(text)
            .advisors { it.param(ChatMemory.CONVERSATION_ID, "t") }
            .call().content()

    @Test
    fun `비밀키가 보이면 모델을 부르지 않고 거절하며 대화 기록에도 남지 않는다`() {
        val answer = ask(client(SecretGuardAdvisor()), "이 키로 접속해줘 AKIAABCDEFGHIJKLMNOP")

        assertTrue(answer!!.contains("AWS 액세스 키"), answer)
        assertEquals(0, model.prompts.size, "모델이 호출되면 안 됨")
        assertEquals(0, memory.get("t").size, "가드레일이 메모리보다 바깥이라 저장되지 않아야 함")
    }

    @Test
    fun `stream 호출에서도 가드레일이 적용된다`() {
        val chunks = client(SecretGuardAdvisor()).prompt()
            .user("-----BEGIN RSA PRIVATE KEY----- ...")
            .advisors { it.param(ChatMemory.CONVERSATION_ID, "t") }
            .stream().content()
            .collectList().block()!!

        assertTrue(chunks.joinToString("").contains("개인 키"), chunks.toString())
        assertEquals(0, model.prompts.size)
    }

    @Test
    fun `평범한 메시지는 그대로 통과한다`() {
        val answer = ask(client(SecretGuardAdvisor()), "AKIA 가 뭐야?")

        assertEquals("모델 답변", answer)
        assertEquals(1, model.prompts.size)
    }

    @Test
    fun `시각은 user 메시지 앞에, 고정 규칙은 시스템 메시지에 붙고 대화 기록에는 저장되지 않는다`() {
        // 2026-11-06 05:30 UTC = 14:30 KST, 금요일
        val clock = Clock.fixed(Instant.parse("2026-11-06T05:30:00Z"), ZoneId.of("Asia/Seoul"))
        val client = client(CurrentTimeAdvisor(AdvisorOrders.CURRENT_TIME, clock))

        ask(client, "오늘 무슨 요일이야?")

        val prompt = model.prompts.single()
        // 시스템 메시지: 원래 규칙 + 고정 문장(RULE). 시각 같은 "바뀌는 값"은 들어가지 않음 (캐시 유지)
        assertEquals("기본 규칙" + CurrentTimeAdvisor.RULE, prompt.systemMessage.text)
        assertEquals("[현재 시각: 2026-11-06 (금) 14:30 (Asia/Seoul)]\n오늘 무슨 요일이야?", prompt.userMessage.text)
        // 메모리에는 user, assistant 만 저장되고 주입한 시각은 없음
        val saved = memory.get("t")
        assertEquals(listOf(MessageType.USER, MessageType.ASSISTANT), saved.map { it.messageType })
        assertTrue(saved.none { it.text.orEmpty().contains("현재 시각") })
    }

    @Test
    fun `측정 어드바이저는 응답을 바꾸지 않는다`() {
        val answer = ask(client(MetricsAdvisor("테스트", AdvisorOrders.ROUND_METRICS)), "안녕")
        assertEquals("모델 답변", answer)
    }
}
