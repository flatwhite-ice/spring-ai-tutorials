package com.example.springai

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

/**
 * 스프링 컨텍스트(모든 빈)가 정상적으로 만들어지는지 확인합니다.
 *
 * Ollama 가 꺼져 있어도 통과합니다. 빈을 만들 때는 네트워크 호출을 하지 않고
 * (pull-model-strategy: never), 실제 /api/chat 호출은 질문을 보낼 때 일어나기 때문입니다.
 */
@SpringBootTest
class SpringAiApplicationTests {

    @Test
    fun contextLoads() {
    }

}
