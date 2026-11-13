package dev.pulse.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.pulse.security.SsrfGuard.BlockedAddressException;
import dev.pulse.security.SsrfGuard.UnsafeUrlException;

/** The load-test stack trusts exactly one host; nothing else may get through. */
class SsrfTrustedHostsTest {

	static final Map<String, String> DNS = Map.of(
			"wiremock", "172.18.0.5",           // the load-test target, on a private Docker network
			"internal.test", "10.0.0.1",
			"metadata.test", "169.254.169.254");

	static final HostResolver RESOLVER = host -> {
		if (host.matches("[0-9.]+") || host.contains(":")) {
			return new InetAddress[] { InetAddress.getByName(host) }; // an IP literal resolves to itself, like real DNS
		}
		String ip = DNS.get(host.toLowerCase(java.util.Locale.ROOT));
		if (ip == null) {
			throw new UnknownHostException(host);
		}
		return new InetAddress[] { InetAddress.getByName(ip) };
	};

	static SsrfGuard guard(List<String> trusted) {
		return new SsrfGuard(RESOLVER, new SsrfProperties(trusted));
	}

	@Test
	void byDefaultNothingIsTrustedSoAPrivateTargetIsRefused() {
		for (SsrfGuard guard : List.of(guard(List.of()), guard(null))) {
			assertThatThrownBy(() -> guard.check("http://wiremock:8080/health")).isInstanceOf(UnsafeUrlException.class)
				.hasMessage(SsrfGuard.PRIVATE_ADDRESS);
		}
	}

	@Test
	void aTrustedHostIsAllowedWhateverItsCase() throws Exception {
		SsrfGuard guard = guard(List.of("wiremock"));

		assertThat(guard.check("http://wiremock:8080/health").addresses()).hasSize(1);
		assertThat(guard.check("http://WireMock:8080/health").addresses()).hasSize(1);
		assertThat(guard.resolvePublic("wiremock")).hasSize(1); // the checker's DNS path too
	}

	@Test
	void everyOtherPrivateHostIsStillRefused() {
		SsrfGuard guard = guard(List.of("wiremock"));

		assertThatThrownBy(() -> guard.check("http://internal.test/")).hasMessage(SsrfGuard.PRIVATE_ADDRESS);
		assertThatThrownBy(() -> guard.check("http://metadata.test/")).hasMessage(SsrfGuard.PRIVATE_ADDRESS);
		assertThatThrownBy(() -> guard.check("http://10.0.0.1/")).hasMessage(SsrfGuard.PRIVATE_ADDRESS);
		assertThatThrownBy(() -> guard.resolvePublic("internal.test")).isInstanceOf(BlockedAddressException.class);
	}

	@Test
	void theUrlRulesStillApplyToTheTrustedHost() {
		SsrfGuard guard = guard(List.of("wiremock"));

		assertThatThrownBy(() -> guard.check("http://wiremock:22/")).hasMessageContaining("Port 22 is not allowed");
		assertThatThrownBy(() -> guard.check("ftp://wiremock/")).hasMessageContaining("http:// or https://");
		assertThatThrownBy(() -> guard.check("http://user:pw@wiremock:8080/")).hasMessageContaining("user name");
	}

	@ParameterizedTest
	@ValueSource(strings = { "169.254.169.254", "10.0.0.1", "127.0.0.1", "::1", "[::1]", "fd00::1" })
	void anIpAddressCanNeverBeTrusted(String entry) {
		assertThatThrownBy(() -> guard(List.of(entry))).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("host names");
	}

}
