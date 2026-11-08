package com.example.springai.tools

import com.example.springai.agent.UserContext
import org.springframework.ai.chat.model.ToolContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TodoToolsTest {
    private val todos = TodoTools()

    // 실제 실행에서는 Spring AI 가 ToolContext 를 넣어 주지만, 단위 테스트에서는 직접 만듭니다.
    private val alice = ToolContext(UserContext.of("alice"))
    private val bob = ToolContext(UserContext.of("bob"))

    @Test
    fun `추가 - 조회 - 완료 흐름`() {
        todos.addTodo("우유 사기", alice)
        todos.addTodo("운동하기", alice)

        assertEquals(2, todos.listTodos(null, alice).size)
        assertTrue(todos.completeTodo(1, alice).startsWith("완료"))

        // 미완료만 조회하면 1건
        assertEquals(listOf("운동하기"), todos.listTodos(false, alice).map { it.title })
        // 전체 조회 시 완료 표시 확인
        assertTrue(todos.listTodos(true, alice).first { it.id == 1L }.done)
    }

    @Test
    fun `없는 ID 완료 시 오류 메시지`() = assertTrue(todos.completeTodo(999, alice).startsWith("오류"))

    @Test
    fun `사용자마다 목록과 ID 가 따로다`() {
        todos.addTodo("우유 사기", alice)

        assertEquals(0, todos.listTodos(null, bob).size, "bob 에게 alice 의 할 일이 보이면 안 됨")
        assertTrue(todos.completeTodo(1, bob).startsWith("오류"), "bob 이 alice 의 #1 을 완료할 수 없어야 함")
        assertEquals("추가됨: #1 운동하기", todos.addTodo("운동하기", bob), "ID 는 사용자마다 1부터")
        assertTrue(todos.listTodos(null, alice).none { it.done })
    }

    @Test
    fun `사용자 정보가 없으면 실행하지 않는다`() {
        assertFailsWith<IllegalStateException> { todos.addTodo("우유 사기", ToolContext(emptyMap())) }
    }
}
