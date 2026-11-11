package dev.pulse.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.pulse.security.SsrfGuard.UnsafeUrlException;
import dev.pulse.support.FakeDns;

class SsrfGuardTest {

	private final SsrfGuard guard = new SsrfGuard(FakeDns.resolver());

	@ParameterizedTest(name = "allowed: {0}")
	@ValueSource(strings = {
			"https://example.com",
			"http://example.com/health?full=1",
			"https://example.com:443/",
			"http://example.com:8080/status",
			"http://93.184.216.34/",
			"HTTPS://EXAMPLE.COM/",
			"http://172.15.255.255/",   // just below 172.16.0.0/12
			"http://172.32.0.1/",       // just above it
			"http://100.63.255.255/",   // just below 100.64.0.0/10
			"http://100.128.0.1/",      // just above it
			"https://[2606:2800:220:1:248:1893:25c8:1946]/",
			"https://[2001:4860:4860::8888]/",   // public IPv6 that starts with 2001
			"https://[2001:200::1]/" })          // just past 2001::/23
	void publicUrlsAreAllowed(String url) {
		assertThat(guard.check(url).addresses()).isNotEmpty();
	}

	@ParameterizedTest(name = "blocked address: {0}")
	@ValueSource(strings = {
			"http://127.0.0.1/",
			"http://127.8.9.10/",
			"http://localhost:8080/",
			"http://0.0.0.0/",
			"http://10.1.2.3/",
			"http://172.16.0.1/",
			"http://172.31.255.255/",
			"http://192.168.1.1/",
			"http://169.254.169.254/latest/meta-data/",
			"http://100.64.0.1/",
			"http://100.127.255.255/",
			"http://192.0.0.8/",
			"http://198.18.0.1/",
			"http://224.0.0.1/",
			"http://240.0.0.1/",
			"http://255.255.255.255/",
			"http://[::1]/",
			"http://[::]/",
			"http://[fe80::1]/",
			"http://[fc00::1]/",
			"http://[fd12:3456::1]/",
			"http://[ff02::1]/",
			"http://[::ffff:127.0.0.1]/",   // IPv4-mapped IPv6 of loopback
			"http://[::ffff:10.0.0.1]/",
			"http://192.0.2.1/",            // documentation ranges
			"http://198.51.100.7/",
			"http://203.0.113.9/",
			"http://192.88.99.1/",          // old 6to4 relay
			"http://[64:ff9b::a00:5]/",     // NAT64 wrapping 10.0.0.5
			"http://[64:ff9b::808:808]/",   // NAT64 wrapping a public IP: still not plain public IPv6
			"http://[2002:a00:5::1]/",      // 6to4 wrapping 10.0.0.5
			"http://[2001::1]/",            // Teredo tunnel
			"http://[2001:db8::1]/",        // IPv6 documentation
			"http://[100::1]/",             // IPv6 discard range
			"http://[::a00:5]/",            // old IPv4-compatible form of 10.0.0.5
			"http://internal.test/",        // public-looking name, private address
			"http://metadata.test/",
			"http://mixed.test/" })         // one public and one private address
	void privateAndReservedAddressesAreBlocked(String url) {
		assertThatThrownBy(() -> guard.check(url))
			.isInstanceOf(UnsafeUrlException.class)
			.hasMessageContaining("private or reserved");
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource(delimiter = '|', value = {
			"ftp://example.com/                | must start with http:// or https://",
			"file:///etc/passwd                | must start with http:// or https://",
			"javascript:alert(1)               | must start with http:// or https://",
			"example.com                       | must start with http:// or https://",
			"http://                           | Enter a valid URL",
			"http://exa mple.com/              | Enter a valid URL",
			"http://user:pass@example.com/     | Remove the user name and password",
			"http://example.com@127.0.0.1/     | Remove the user name and password",
			"http://example.com:22/            | Port 22 is not allowed",
			"http://example.com:0/             | Port 0 is not allowed",
			"http://does-not-exist.test/       | could not be found" })
	void badUrlsAreRejectedWithAReason(String url, String reason) {
		assertThatThrownBy(() -> guard.check(url))
			.isInstanceOf(UnsafeUrlException.class)
			.hasMessageContaining(reason);
	}

}
