package dev.pulse.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param mailFrom the From address of alert emails */
@ConfigurationProperties("pulse.alerts")
public record AlertProperties(String mailFrom) {
}
