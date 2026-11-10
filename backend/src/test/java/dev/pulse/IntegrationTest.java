package dev.pulse;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import dev.pulse.support.FakeDns;
import dev.pulse.support.TestUsers;

/**
 * The full app on a random port, against real Postgres and Redis in Docker,
 * with MockMvc for calling the API and fake DNS. All integration tests share
 * one context (and one pair of containers), so the suite stays fast.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeDns.class, TestUsers.class })
@ActiveProfiles("test")
public @interface IntegrationTest {
}
