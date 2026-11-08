package com.example.springai

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * 애플리케이션 진입점.
 *
 * - [SpringBootApplication]: 이 패키지(com.example.springai) 하위의 @Component, @Service, @Configuration 등을
 *   자동으로 스캔하고, 클래스패스에 있는 스타터(예: spring-ai-starter-model-ollama)의 자동 구성을 활성화합니다.
 * - [ConfigurationPropertiesScan]: @ConfigurationProperties 가 붙은 클래스(AgentProperties)를 찾아
 *   application.yml 의 값을 바인딩합니다.
 *
 * 실행 모드
 * - 기본: 웹 서버(8080) + REST API  →  `./gradlew bootRun`
 * - 콘솔: 터미널 대화(REPL)          →  `./gradlew bootRun --args='--spring.profiles.active=cli'`
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class SpringAiApplication

fun main(args: Array<String>) {
    runApplication<SpringAiApplication>(*args)
}
