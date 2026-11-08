package com.example.springai.tools

import com.example.springai.config.AgentProperties
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileToolsTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var workspace: Path
    private lateinit var tools: FileTools

    @BeforeTest
    fun setUp() {
        workspace = tempDir.resolve("workspace").createDirectories()
        workspace.resolve("hello.txt").writeText("hi")
        tempDir.resolve("secret.txt").writeText("TOP SECRET") // 샌드박스 밖 파일
        tools = FileTools(AgentProperties(workspaceDir = workspace.toString()))
    }

    @Test
    fun `작업 폴더 안의 파일은 읽을 수 있다`() = assertEquals("hi", tools.readFile("hello.txt"))

    @Test
    fun `목록 조회`() = assertContains(tools.listFiles(null), "hello.txt")

    @Test
    fun `상위 경로로 탈출하면 거부된다`() {
        val result = tools.readFile("../secret.txt")
        assertTrue(result.startsWith("오류"), result)
    }

    @Test
    fun `절대 경로로 탈출해도 거부된다`() {
        val result = tools.readFile(tempDir.resolve("secret.txt").toString())
        assertTrue(result.startsWith("오류"), result)
    }
}
