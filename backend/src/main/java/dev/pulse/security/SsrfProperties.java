package dev.pulse.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param trustedHosts host names whose private addresses are allowed anyway. Empty in every
 *                     real deployment; only the load-test stack sets it, to its WireMock target.
 */
@ConfigurationProperties("pulse.ssrf")
public record SsrfProperties(List<String> trustedHosts) {
}
