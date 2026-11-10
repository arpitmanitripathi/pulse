package dev.pulse.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pulse.rate-limit")
public record RateLimitProperties(int authPerMinute, int publicPerMinute) {
}
