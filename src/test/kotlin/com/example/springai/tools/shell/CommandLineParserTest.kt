package com.example.springai.tools.shell

import com.example.springai.tools.shell.CommandLineParser.Parsed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CommandLineParserTest {

    private fun ok(line: String): List<String> {
        val parsed = assertIs<Parsed.Ok>(CommandLineParser.parse(line))
        return listOf(parsed.command) + parsed.args
    }

    @Test
    fun `공백으로 인자를 나눈다`() {
        assertEquals(listOf("git", "log", "-1", "--oneline"), ok("git log -1   --oneline"))
    }

    @Test
    fun `따옴표로 묶은 인자는 하나로 취급한다`() {
        assertEquals(listOf("grep", "hello world", "a.txt"), ok("""grep "hello world" a.txt"""))
        assertEquals(listOf("echo", "a; b | c"), ok("echo 'a; b | c'"))
        assertEquals(listOf("echo", ""), ok("echo \"\""))
    }

    @Test
    fun `따옴표 밖의 셸 문법은 거부한다`() {
        listOf("ls | wc -l", "echo hi; rm x", "ls && pwd", "cat a > b", "echo \$HOME", "echo `id`", "ls *.txt")
            .forEach { assertIs<Parsed.Error>(CommandLineParser.parse(it), it) }
    }

    @Test
    fun `닫히지 않은 따옴표와 빈 명령은 거부한다`() {
        assertIs<Parsed.Error>(CommandLineParser.parse("echo 'abc"))
        assertIs<Parsed.Error>(CommandLineParser.parse("   "))
    }
}
