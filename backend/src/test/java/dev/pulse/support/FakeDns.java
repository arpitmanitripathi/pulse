package dev.pulse.support;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import dev.pulse.security.HostResolver;

/**
 * Fixed DNS answers so tests never depend on the real internet.
 * IP literals ("10.0.0.1", "::1") resolve to themselves; known names use the
 * table; anything else "does not exist".
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeDns {

	private static final Map<String, String[]> RECORDS = Map.of(
			"example.com", new String[] { "93.184.216.34" },
			"status.example.org", new String[] { "93.184.216.35" },
			"localhost", new String[] { "127.0.0.1" },
			"internal.test", new String[] { "10.0.0.5" },
			"metadata.test", new String[] { "169.254.169.254" },
			"mixed.test", new String[] { "93.184.216.34", "10.0.0.5" });

	@Bean
	@Primary
	HostResolver fakeHostResolver() {
		return resolver();
	}

	public static HostResolver resolver() {
		return host -> {
			String[] ips = RECORDS.get(host.toLowerCase());
			if (ips != null) {
				InetAddress[] result = new InetAddress[ips.length];
				for (int i = 0; i < ips.length; i++) {
					result[i] = InetAddress.getByName(ips[i]); // literal: no DNS lookup
				}
				return result;
			}
			if (host.matches("[0-9.]+") || host.contains(":")) {
				return InetAddress.getAllByName(host); // literal: no DNS lookup
			}
			throw new UnknownHostException(host);
		};
	}

}
