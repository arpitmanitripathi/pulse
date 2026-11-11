package dev.pulse.check;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.config.Lookup;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import dev.pulse.monitor.CheckMethod;
import dev.pulse.security.SsrfGuard;
import dev.pulse.security.SsrfGuard.BlockedAddressException;
import dev.pulse.security.SsrfGuard.UnsafeUrlException;

/**
 * Checks one URL over HTTP.
 *
 * <ul>
 * <li>DNS goes through {@link SsrfGuard#resolvePublic}: the client can only
 * connect to an address the guard approved. There is no second, unchecked lookup.</li>
 * <li>Redirects are followed by hand (max 5) so every hop passes the guard.</li>
 * <li>Only the status line is read; the body is never downloaded.</li>
 * <li>A fresh connection per check, so latency includes DNS, connect and TLS,
 * like a real visitor's first request.</li>
 * <li>The monitor's timeout is a budget for the whole check, redirects included.</li>
 * </ul>
 */
@Component
public class HttpChecker implements Checker {

	static final String USER_AGENT = "PulseMonitor/1.0";
	static final int MAX_REDIRECTS = 5;
	private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);

	private final SsrfGuard guard;
	private final Clock clock;
	private final DnsResolver dnsResolver;
	/** Built once: loading the JVM's trusted certificates is slow. Certificates and hostnames are verified. */
	private final Lookup<TlsSocketStrategy> tls = RegistryBuilder.<TlsSocketStrategy>create()
			.register(URIScheme.HTTPS.id, DefaultClientTlsStrategy.createDefault())
			.build();

	public HttpChecker(SsrfGuard guard, Clock clock) {
		this.guard = guard;
		this.clock = clock;
		this.dnsResolver = new DnsResolver() {
			@Override
			public InetAddress[] resolve(String host) throws UnknownHostException {
				return guard.resolvePublic(host);
			}

			@Override
			public String resolveCanonicalHostname(String host) {
				return host;
			}
		};
	}

	@Override
	public CheckOutcome check(ClaimedMonitor monitor) {
		Instant checkedAt = clock.instant();
		long start = System.nanoTime();
		String url = monitor.url();
		Integer lastStatus = null;
		boolean followRedirects = monitor.expectedStatus() == null || monitor.expectedStatus() < 300
				|| monitor.expectedStatus() > 399;
		try {
			for (int hop = 0; ; hop++) {
				int remainingMs = monitor.timeoutMs() - elapsedMs(start);
				if (remainingMs <= 0) {
					return CheckOutcome.failure(checkedAt, lastStatus, elapsedMs(start), ErrorType.TIMEOUT,
							"No answer within " + monitor.timeoutMs() + " ms.");
				}
				URI uri = guard.validateUrl(url);      // scheme, port, no user info  -> BLOCKED
				guard.resolvePublic(uri.getHost());     // catches IP literals too     -> BLOCKED or DNS
				Response response = send(monitor.method(), uri, remainingMs);
				lastStatus = response.status();
				int latency = elapsedMs(start);

				if (followRedirects && REDIRECTS.contains(response.status()) && response.location() != null) {
					if (hop >= MAX_REDIRECTS) {
						return CheckOutcome.failure(checkedAt, response.status(), latency, ErrorType.STATUS,
								"More than " + MAX_REDIRECTS + " redirects.");
					}
					url = uri.resolve(response.location()).toString();
					continue;
				}
				if (isExpected(response.status(), monitor.expectedStatus())) {
					return CheckOutcome.success(checkedAt, response.status(), latency);
				}
				String expected = monitor.expectedStatus() == null ? "a 2xx status"
						: "status " + monitor.expectedStatus();
				return CheckOutcome.failure(checkedAt, response.status(), latency, ErrorType.STATUS,
						"Expected " + expected + ", got " + response.status() + ".");
			}
		}
		catch (UnsafeUrlException ex) {
			return CheckOutcome.failure(checkedAt, lastStatus, elapsedMs(start), ErrorType.BLOCKED,
					"Blocked: " + ex.getMessage());
		}
		catch (BlockedAddressException ex) {
			return CheckOutcome.failure(checkedAt, lastStatus, elapsedMs(start), ErrorType.BLOCKED,
					"Blocked: " + ex.getMessage() + ".");
		}
		catch (UnknownHostException ex) {
			return CheckOutcome.failure(checkedAt, lastStatus, elapsedMs(start), ErrorType.DNS,
					"The hostname could not be found.");
		}
		catch (SSLException ex) {
			return CheckOutcome.failure(checkedAt, lastStatus, elapsedMs(start), ErrorType.TLS,
					"HTTPS failed: " + ex.getMessage());
		}
		catch (InterruptedIOException ex) {
			// Connect and read timeouts are both InterruptedIOExceptions.
			return CheckOutcome.failure(checkedAt, lastStatus, elapsedMs(start), ErrorType.TIMEOUT,
					"No answer within " + monitor.timeoutMs() + " ms.");
		}
		catch (IOException ex) {
			return CheckOutcome.failure(checkedAt, lastStatus, elapsedMs(start), ErrorType.CONNECTION,
					"Could not connect: " + ex.getMessage());
		}
		catch (RuntimeException ex) {
			return CheckOutcome.failure(checkedAt, lastStatus, elapsedMs(start), ErrorType.CONNECTION,
					"Check failed: " + ex.getClass().getSimpleName());
		}
	}

	private Response send(CheckMethod method, URI uri, int timeoutMs) throws IOException {
		Timeout timeout = Timeout.ofMilliseconds(timeoutMs);
		BasicHttpClientConnectionManager connections = BasicHttpClientConnectionManager.create(
				DefaultSchemePortResolver.INSTANCE, dnsResolver, tls, ManagedHttpClientConnectionFactory.INSTANCE);
		connections.setConnectionConfig(ConnectionConfig.custom()
				.setConnectTimeout(timeout)
				.setSocketTimeout(timeout)
				.build());
		HttpUriRequestBase request = method == CheckMethod.HEAD ? new HttpHead(uri) : new HttpGet(uri);
		request.setConfig(RequestConfig.custom()
				.setResponseTimeout(timeout)
				.setRedirectsEnabled(false)
				.build());
		CloseableHttpClient client = HttpClients.custom()
				.setConnectionManager(connections)
				.disableRedirectHandling()
				.disableAutomaticRetries()
				.disableCookieManagement()
				.disableContentCompression()
				.setUserAgent(USER_AGENT)
				.build();
		ClassicHttpResponse response = null;
		try {
			response = client.executeOpen(null, request, null);
			Header location = response.getFirstHeader("Location");
			return new Response(response.getCode(), location == null ? null : location.getValue());
		}
		finally {
			// Drop the connection without reading the body (a plain close() would read it first).
			// Errors while closing are expected after a cancel and say nothing about the site.
			request.cancel();
			closeQuietly(response);
			closeQuietly(client);
		}
	}

	private static void closeQuietly(AutoCloseable closeable) {
		if (closeable == null) {
			return;
		}
		try {
			closeable.close();
		}
		catch (Exception ignored) {
			// see above
		}
	}

	private static boolean isExpected(int status, Integer expectedStatus) {
		return expectedStatus == null ? status >= 200 && status <= 299 : status == expectedStatus;
	}

	private static int elapsedMs(long startNanos) {
		return (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
	}

	private record Response(int status, String location) {
	}

}
