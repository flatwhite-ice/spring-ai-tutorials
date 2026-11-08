package com.example.springai.tools.shell

import com.example.springai.config.AgentProperties
import com.example.springai.tools.shell.ShellPolicy.Decision
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * 정책 판정은 순수 로직이므로 프로세스 실행 없이 테스트합니다.
 *
 * 주의(Kotlin + JUnit5): `fun test() = assertIs<...>(...)` 처럼 식 본문으로 쓰면 반환 타입이 Unit 이 아니게 되어
 * JUnit 이 테스트로 인식하지 않고 조용히 건너뜁니다. assertIs 처럼 값을 반환하는 단언은 블록 본문 { } 으로 쓰세요.
 */
class ShellPolicyTest {
    private val policy = ShellPolicy(AgentProperties())

    @Test
    fun `허용 목록의 명령은 바로 실행`() {
        assertIs<Decision.Allow>(policy.evaluate("ls", listOf("-la")))
        assertIs<Decision.Allow>(policy.evaluate("wc", listOf("-l", "hello.txt")))
    }

    @Test
    fun `허용된 서브커맨드만 바로 실행, 나머지는 승인 필요`() {
        assertIs<Decision.Allow>(policy.evaluate("git", listOf("status")))
        assertIs<Decision.Ask>(policy.evaluate("git", listOf("push")))
        assertIs<Decision.Ask>(policy.evaluate("git", emptyList()))
    }

    @Test
    fun `허용 목록에 없는 명령은 승인 필요`() {
        assertIs<Decision.Ask>(policy.evaluate("python3", listOf("--version")))
    }

    @Test
    fun `작업 폴더 밖을 가리키는 인자는 허용 명령이어도 승인 필요`() {
        assertIs<Decision.Ask>(policy.evaluate("cat", listOf("../build.gradle.kts")))
        assertIs<Decision.Ask>(policy.evaluate("cat", listOf("/etc/hosts")))
        assertIs<Decision.Ask>(policy.evaluate("ls", listOf("~/.ssh")))
        assertIs<Decision.Ask>(policy.evaluate("git", listOf("diff", "--output=../x")))
    }

    @Test
    fun `거부 목록의 명령은 항상 거부`() {
        assertIs<Decision.Deny>(policy.evaluate("rm", listOf("-rf", "notes")))
        assertIs<Decision.Deny>(policy.evaluate("bash", listOf("-c", "ls")))   // 셸 우회 차단
        assertIs<Decision.Deny>(policy.evaluate("curl", listOf("http://example.com")))
    }

    @Test
    fun `거부 인자가 있으면 거부 - 거부가 허용보다 우선`() {
        assertIs<Decision.Deny>(policy.evaluate("find", listOf(".", "-exec", "rm", "{}", ";")))
    }

    @Test
    fun `프로그램 이름에 경로가 있으면 거부`() {
        assertIs<Decision.Deny>(policy.evaluate("/tmp/evil", emptyList()))
        assertIs<Decision.Deny>(policy.evaluate("./ls", emptyList()))
    }
}
