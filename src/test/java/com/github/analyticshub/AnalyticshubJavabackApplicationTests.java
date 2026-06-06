package com.github.analyticshub;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:analytics}",
        "spring.datasource.username=${DB_USER:analytic}",
        "spring.datasource.password=${DB_PASSWORD:replace-with-local-analytic-password}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true"
})
class AnalyticshubJavabackApplicationTests {

    @Test
    void contextLoads() {
    }

}
