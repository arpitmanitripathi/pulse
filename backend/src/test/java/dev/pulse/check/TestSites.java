package dev.pulse.check;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.UUID;

import dev.pulse.monitor.CheckMethod;
import dev.pulse.security.HostResolver;
import dev.pulse.security.SsrfGuard;

/**
 * Fake websites run on 127.0.0.1 (WireMock), which the real guard blocks.
 * Here "site.test" points to 127.0.0.1 and loopback is allowed; every other
 * rule is the production one, so redirects to 10.x or 169.254.x are still blocked.
 */
final class TestSites {

	private TestSites() {
	}

	static final HostResolver DNS = host -> switch (host) {
		case "site.test" -> new InetAddress[] { InetAddress.getByName("127.0.0.1") };
		case "internal.test", "rebind.test" -> new InetAddress[] { InetAddress.getByName("10.0.0.5") };
		default -> {
			if (host.matches("[0-9.]+") || host.contains(":")) {
				yield InetAddress.getAllByName(host); // IP literal, no lookup
			}
			throw new UnknownHostException(host);
		}
	};

	static SsrfGuard guard() {
		return new SsrfGuard(DNS, address -> SsrfGuard.isPublic(address) || address.isLoopbackAddress());
	}

	static HttpChecker checker() {
		return new HttpChecker(guard(), Clock.systemUTC());
	}

	static ClaimedMonitor monitor(String url, int timeoutMs, Integer expectedStatus) {
		return new ClaimedMonitor(UUID.randomUUID(), url, CheckMethod.GET, timeoutMs, expectedStatus, null);
	}

	static ClaimedMonitor monitor(String url) {
		return monitor(url, 2000, null);
	}

}
