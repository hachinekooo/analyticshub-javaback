package com.github.analyticshub;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/analytics_flyway_test",
        "spring.datasource.username=root",
        "spring.datasource.password=root",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true"
})
class AnalyticshubJavabackApplicationTests {

    @Test
    void contextLoads() {
    }

}
