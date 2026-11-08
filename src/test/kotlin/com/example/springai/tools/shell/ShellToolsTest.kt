package com.example.springai.tools.shell

import com.example.springai.config.AgentProperties
import com.example.springai.tools.ShellTools
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 실제 프로세스를 실행하는 통합 테스트 (LLM 불필요).
 * 승인자(CommandApprover)는 람다로 바꿔 끼워 승인/거절 상황을 흉내 냅니다.
 */
class ShellToolsTest {
    @TempDir
    lateinit var workspace: Path

    /** 승인 요청이 몇 번 왔는지 기록 */
    private val approvalRequests = mutableListOf<String>()

    private fun tools(approve: Boolean, shell: AgentProperties.ShellProperties = AgentProperties.ShellProperties()): ShellTools {
        val props = AgentProperties(workspaceDir = workspace.toString(), shell = shell)
        val approver = CommandApprover { cmd, _ -> approvalRequests += cmd; approve }
        return ShellTools(ShellPolicy(props), approver, ShellExecutor(props, NoSandbox))
    }

    @BeforeTest
    fun setUp() {
        workspace.resolve("hello.txt").writeText("line1\nline2\nline3\n")
    }

    @Test
    fun `허용 명령은 승인 없이 작업 폴더에서 실행된다`() {
        val result = tools(approve = false).runCommand("wc -l hello.txt")
        assertContains(result, "종료 코드: 0")
        assertContains(result, "3")
        assertTrue(approvalRequests.isEmpty())
    }

    @Test
    fun `셸 문법으로 명령을 이어 붙이면 실행 전에 거부된다`() {
        // bash -c 였다면 "touch pwned" 가 실행되어 파일이 생겼을 것
        val result = tools(approve = true).runCommand("echo hi; touch pwned")
        assertContains(result, "셸 문법")
        assertFalse(workspace.resolve("pwned").exists())
        assertTrue(approvalRequests.isEmpty())        // 승인 단계까지 가지도 않음
    }

    @Test
    fun `따옴표 안의 세미콜론은 글자로 전달될 뿐 새 명령이 되지 않는다`() {
        val result = tools(approve = false).runCommand("echo 'hi; touch pwned'")
        assertContains(result, "hi; touch pwned")     // 글자 그대로 출력됨
        assertFalse(workspace.resolve("pwned").exists())
    }

    @Test
    fun `승인이 필요한 명령은 승인자가 거절하면 실행되지 않는다`() {
        val result = tools(approve = false).runCommand("git push")
        assertContains(result, "실행 거부")
        assertEquals(listOf("git push"), approvalRequests)
    }

    @Test
    fun `승인이 필요한 명령도 승인하면 실행된다`() {
        val result = tools(approve = true).runCommand("uname")
        assertContains(result, "종료 코드: 0")
        assertEquals(listOf("uname"), approvalRequests)
    }

    @Test
    fun `거부 명령은 승인자에게 묻지도 않고 거부된다`() {
        val result = tools(approve = true).runCommand("rm hello.txt")
        assertContains(result, "실행 거부")
        assertTrue(approvalRequests.isEmpty())
        assertTrue(workspace.resolve("hello.txt").exists())
    }

    @Test
    fun `환경 변수는 자식 프로세스에 전달되지 않는다`() {
        // env 는 허용 목록에 없으므로 승인(approve=true) 후 실행
        val result = tools(approve = true).runCommand("env")
        assertFalse("HOME=" in result, result)       // JVM 의 HOME 등이 보이면 안 됨
        assertContains(result, "PATH=")              // 우리가 넣어준 것만 보임
    }

    @Test
    fun `시간을 넘기면 강제 종료된다`() {
        val shell = AgentProperties.ShellProperties(allowedCommands = listOf("sleep"), timeout = Duration.ofMillis(500))
        val start = System.nanoTime()
        val result = tools(approve = false, shell = shell).runCommand("sleep 5")
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertContains(result, "시간 초과")
        assertTrue(elapsedMs < 4_000, "5초를 기다리지 않고 종료되어야 함: ${elapsedMs}ms")
    }

    @Test
    fun `긴 출력은 잘린다`() {
        workspace.resolve("big.txt").writeText("x".repeat(10_000))
        val shell = AgentProperties.ShellProperties(maxOutputChars = 100)
        val result = tools(approve = false, shell = shell).runCommand("cat big.txt")
        assertContains(result, "출력 생략: 총 10000자")
    }
}
