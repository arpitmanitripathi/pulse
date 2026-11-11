package dev.pulse.alert;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import dev.pulse.security.SsrfGuard;
import dev.pulse.security.SsrfGuard.BlockedAddressException;
import dev.pulse.security.SsrfGuard.UnsafeUrlException;
import jakarta.annotation.PreDestroy;

/**
 * POSTs JSON to Slack and Discord webhooks. Like the checker, its DNS goes
 * through the SSRF guard, so it can only connect to approved public addresses.
 * No redirects, no retries here (the outbox retries), 10 s timeouts.
 */
@Component
public class WebhookClient {

	static final String USER_AGENT = "PulseMonitor/1.0";
	private static final Timeout TIMEOUT = Timeout.ofSeconds(10);

	private final SsrfGuard guard;
	private final CloseableHttpClient client;

	public WebhookClient(SsrfGuard guard) {
		this.guard = guard;
		DnsResolver dns = new DnsResolver() {
			@Override
			public InetAddress[] resolve(String host) throws UnknownHostException {
				return guard.resolvePublic(host);
			}

			@Override
			public String resolveCanonicalHostname(String host) {
				return host;
			}
		};
		this.client = HttpClients.custom()
				.setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
						.setDnsResolver(dns)
						.setTlsSocketStrategy(DefaultClientTlsStrategy.createDefault())
						.setDefaultConnectionConfig(ConnectionConfig.custom()
								.setConnectTimeout(TIMEOUT)
								.setSocketTimeout(TIMEOUT)
								.build())
						.setMaxConnTotal(50)
						.setMaxConnPerRoute(20)
						.build())
				.setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(TIMEOUT).build())
				.disableRedirectHandling()
				.disableAutomaticRetries()
				.disableCookieManagement()
				.setUserAgent(USER_AGENT)
				.build();
	}

	/** @param service "Slack" or "Discord", used in error messages */
	public void postJson(String url, String json, String service) throws AlertDeliveryException {
		URI uri;
		try {
			uri = guard.validateUrl(url);
		}
		catch (UnsafeUrlException ex) {
			throw new AlertDeliveryException("The webhook URL is not allowed: " + ex.getMessage());
		}
		HttpPost post = new HttpPost(uri);
		post.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
		int status;
		try {
			status = client.execute(post, response -> {
				EntityUtils.consume(response.getEntity());
				return response.getCode();
			});
		}
		catch (BlockedAddressException ex) {
			throw new AlertDeliveryException("The webhook host resolves to a private address.");
		}
		catch (IOException ex) {
			throw new AlertDeliveryException(service + " could not be reached: " + ex.getMessage());
		}
		if (status < 200 || status > 299) {
			throw new AlertDeliveryException(service + " answered HTTP " + status + ".");
		}
	}

	@PreDestroy
	void close() throws IOException {
		client.close();
	}

}
