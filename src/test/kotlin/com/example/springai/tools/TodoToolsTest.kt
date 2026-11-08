package com.example.springai.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TodoToolsTest {
    private val todos = TodoTools()

    @Test
    fun `추가 - 조회 - 완료 흐름`() {
        todos.addTodo("우유 사기")
        todos.addTodo("운동하기")

        assertEquals(2, todos.listTodos(null).size)
        assertTrue(todos.completeTodo(1).startsWith("완료"))

        // 미완료만 조회하면 1건
        assertEquals(listOf("운동하기"), todos.listTodos(false).map { it.title })
        // 전체 조회 시 완료 표시 확인
        assertTrue(todos.listTodos(true).first { it.id == 1L }.done)
    }

    @Test
    fun `없는 ID 완료 시 오류 메시지`() = assertTrue(todos.completeTodo(999).startsWith("오류"))
}
