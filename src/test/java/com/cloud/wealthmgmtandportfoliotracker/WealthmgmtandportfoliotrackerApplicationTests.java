package com.cloud.wealthmgmtandportfoliotracker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.boot.test.context.SpringBootTest;

@Disabled("Legacy scaffold context test; excluded from active CI build.")
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.orm.jpa.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration"
})
class WealthmgmtandportfoliotrackerApplicationTests {

    @Test
    void contextLoads() {
    }

}
