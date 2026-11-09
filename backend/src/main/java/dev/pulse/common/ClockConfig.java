package dev.pulse.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Code never calls Instant.now() directly; it asks this Clock. Tests can then
 * replace it with a fixed or moving clock and control time exactly.
 */
@Configuration
public class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
