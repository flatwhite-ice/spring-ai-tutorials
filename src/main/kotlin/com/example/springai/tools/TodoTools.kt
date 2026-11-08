package com.example.springai.tools

import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 할 일(TODO) 관리 도구 — "상태를 변경하는" 도구 예제입니다.
 *
 * 앞의 도구들은 값을 계산해서 돌려주기만 했지만, 이 도구는 애플리케이션의 데이터를 바꿉니다.
 * 즉 에이전트가 "말"만 하는 것이 아니라 실제로 "행동"할 수 있게 됩니다.
 *
 * 저장소는 학습용으로 JVM 메모리(ConcurrentHashMap)를 사용하므로 재시작하면 사라집니다.
 * 웹 요청은 여러 스레드에서 동시에 들어올 수 있으므로 스레드 안전한 컬렉션을 사용했습니다.
 *
 * 참고: 단순화를 위해 TODO 목록은 모든 대화(conversationId)가 공유합니다.
 */
@Component
class TodoTools {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 할 일 한 건을 나타내는 데이터 */
    data class Todo(val id: Long, val title: String, val done: Boolean = false, val createdAt: LocalDateTime = LocalDateTime.now())

    private val todos = ConcurrentHashMap<Long, Todo>()
    private val idGenerator = AtomicLong(0)

    @Tool(description = "새 할 일을 목록에 추가하고, 추가된 할 일의 ID 를 알려줍니다.")
    fun addTodo(@ToolParam(description = "할 일 내용 (예: 우유 사기)") title: String): String {
        val todo = Todo(id = idGenerator.incrementAndGet(), title = title.trim())
        todos[todo.id] = todo
        log.info("[Tool] addTodo({}) -> id={}", title, todo.id)
        return "추가됨: #${todo.id} ${todo.title}"
    }

    // 반환 타입이 List<Todo> 처럼 객체여도 됩니다. Spring AI 가 JSON 으로 직렬화해서 모델에게 전달합니다.
    @Tool(description = "할 일 목록을 조회합니다. 각 항목의 ID, 내용, 완료 여부를 반환합니다.")
    fun listTodos(
        @ToolParam(description = "true 이면 완료된 항목도 포함, false 이면 미완료 항목만. 생략하면 전체", required = false)
        includeDone: Boolean?,
    ): List<Todo> {
        val result = todos.values
            .filter { includeDone != false || !it.done }
            .sortedBy { it.id }
        log.info("[Tool] listTodos(includeDone={}) -> {}건", includeDone, result.size)
        return result
    }

    @Tool(description = "ID 로 지정한 할 일을 완료 처리합니다. ID 를 모르면 먼저 listTodos 로 조회하세요.")
    fun completeTodo(@ToolParam(description = "완료할 할 일의 ID (숫자)") id: Long): String {
        // computeIfPresent: 키가 있을 때만 원자적으로 값을 교체
        val updated = todos.computeIfPresent(id) { _, todo -> todo.copy(done = true) }
        val result = updated?.let { "완료 처리됨: #${it.id} ${it.title}" } ?: "오류: ID $id 인 할 일이 없습니다"
        log.info("[Tool] completeTodo({}) -> {}", id, result)
        return result
    }
}
