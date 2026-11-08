package com.example.springai.web

/**
 * REST API 요청 본문.
 *
 * @property conversationId 대화 ID. 생략하면 서버가 새로 발급합니다.
 *                          이어서 대화하려면 응답으로 받은 conversationId 를 다음 요청에 넣어 보내세요.
 * @property message 사용자 메시지
 */
data class ChatRequest(
    val conversationId: String? = null,
    val message: String,
)

/** REST API 응답 본문 */
data class ChatResponse(
    val conversationId: String,
    val answer: String,
)
