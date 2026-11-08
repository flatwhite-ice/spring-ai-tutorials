package com.example.springai.tools

import com.example.springai.agent.UserContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 할 일(TODO) 관리 도구 — "상태를 변경하는" 도구이자 "사용자별 데이터"를 다루는 도구 예제입니다.
 *
 * 앞의 도구들은 값을 계산해서 돌려주기만 했지만, 이 도구는 애플리케이션의 데이터를 바꿉니다.
 * 즉 에이전트가 "말"만 하는 것이 아니라 실제로 "행동"할 수 있게 됩니다.
 *
 * ── 사용자별 분리 (ToolContext) ─────────────────────────────────────────
 *  할 일 목록은 사용자마다 따로 저장합니다. 사용자 ID 는 도구 매개변수가 아니라 `ToolContext` 로 받습니다.
 *  - `ctx: ToolContext` 매개변수는 LLM 에 보내는 JSON 스키마에서 빠집니다 → LLM 은 사용자 ID 를 모름
 *  - 값은 서버(AgentService)가 넣으므로 LLM 이 다른 사용자 ID 로 바꿀 수 없음
 *  - ID(#1, #2 …)도 사용자마다 1부터 시작합니다. 다른 사용자의 #1 은 애초에 보이지 않습니다.
 *  자세한 설명: docs/09-user-context.md
 *
 * 저장소는 학습용으로 JVM 메모리(ConcurrentHashMap)를 사용하므로 재시작하면 사라집니다.
 * 웹 요청은 여러 스레드에서 동시에 들어올 수 있으므로 스레드 안전한 컬렉션을 사용했습니다.
 */
@Component
class TodoTools {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 할 일 한 건을 나타내는 데이터. 사용자 ID 는 넣지 않습니다 (결과가 LLM 에 그대로 전달되므로) */
    data class Todo(val id: Long, val title: String, val done: Boolean = false, val createdAt: LocalDateTime = LocalDateTime.now())

    /** 사용자 한 명의 할 일 저장소 */
    private class UserTodos {
        val todos = ConcurrentHashMap<Long, Todo>()
        val idGenerator = AtomicLong(0)
    }

    /** 사용자 ID → 그 사용자의 저장소 */
    private val stores = ConcurrentHashMap<String, UserTodos>()

    /** ToolContext 에서 사용자 ID 를 꺼내 그 사용자의 저장소를 돌려줍니다 (없으면 새로 만듦) */
    private fun storeOf(ctx: ToolContext): Pair<String, UserTodos> {
        val userId = UserContext.from(ctx)
        return userId to stores.computeIfAbsent(userId) { UserTodos() }
    }

    @Tool(description = "새 할 일을 목록에 추가하고, 추가된 할 일의 ID 를 알려줍니다.")
    fun addTodo(
        @ToolParam(description = "할 일 내용 (예: 우유 사기)") title: String,
        ctx: ToolContext, // ← LLM 이 채우는 값이 아님. 서버가 넣은 사용자 ID 가 들어 있음
    ): String {
        val (userId, store) = storeOf(ctx)
        val todo = Todo(id = store.idGenerator.incrementAndGet(), title = title.trim())
        store.todos[todo.id] = todo
        // 로그는 서버에만 남으므로 사용자 ID 를 적어도 됩니다. 반환값(LLM 에 전달)에는 넣지 않습니다.
        log.info("[Tool] addTodo(user={}, {}) -> id={}", userId, title, todo.id)
        return "추가됨: #${todo.id} ${todo.title}"
    }

    // 반환 타입이 List<Todo> 처럼 객체여도 됩니다. Spring AI 가 JSON 으로 직렬화해서 모델에게 전달합니다.
    @Tool(description = "할 일 목록을 조회합니다. 각 항목의 ID, 내용, 완료 여부를 반환합니다.")
    fun listTodos(
        @ToolParam(description = "true 이면 완료된 항목도 포함, false 이면 미완료 항목만. 생략하면 전체", required = false)
        includeDone: Boolean?,
        ctx: ToolContext,
    ): List<Todo> {
        val (userId, store) = storeOf(ctx)
        val result = store.todos.values
            .filter { includeDone != false || !it.done }
            .sortedBy { it.id }
        log.info("[Tool] listTodos(user={}, includeDone={}) -> {}건", userId, includeDone, result.size)
        return result
    }

    @Tool(description = "ID 로 지정한 할 일을 완료 처리합니다. ID 를 모르면 먼저 listTodos 로 조회하세요.")
    fun completeTodo(
        @ToolParam(description = "완료할 할 일의 ID (숫자)") id: Long,
        ctx: ToolContext,
    ): String {
        val (userId, store) = storeOf(ctx)
        // computeIfPresent: 키가 있을 때만 원자적으로 값을 교체. 이 사용자의 저장소에서만 찾습니다.
        val updated = store.todos.computeIfPresent(id) { _, todo -> todo.copy(done = true) }
        val result = updated?.let { "완료 처리됨: #${it.id} ${it.title}" } ?: "오류: ID $id 인 할 일이 없습니다"
        log.info("[Tool] completeTodo(user={}, {}) -> {}", userId, id, result)
        return result
    }
}
