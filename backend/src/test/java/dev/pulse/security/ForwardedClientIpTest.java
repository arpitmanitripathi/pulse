package dev.pulse.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;

import org.apache.catalina.Valve;
import org.apache.catalina.util.NetMaskSet;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import dev.pulse.IntegrationTest;

/**
 * Behind Caddy every request arrives from the proxy's address. The client IP used for rate limits must come from
 * X-Forwarded-For, but only when the request comes from a private address (the proxy); a visitor connecting
 * directly from the internet can't choose their own IP with that header.
 */
@IntegrationTest
class ForwardedClientIpTest {

	@Autowired
	ServletWebServerApplicationContext context;

	@Autowired
	StringRedisTemplate redis;

	final HttpClient http = HttpClient.newHttpClient();

	@BeforeEach
	void fullAllowance() {
		redis.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushAll();
			return null;
		});
	}

	@Test
	void visitorsBehindTheProxyGetTheirOwnRateLimit() throws Exception {
		// The test connects from 127.0.0.1, a private address, like Caddy on the Docker network.
		int status = 0;
		for (int i = 0; i < 11; i++) {
			status = loginFrom("203.0.113.7");
		}
		assertThat(status).as("11th login from the same visitor").isEqualTo(429);

		assertThat(loginFrom("203.0.113.8")).as("another visitor is not limited").isEqualTo(401);
	}

	@Test
	void theHeaderIsTrustedOnlyFromPrivateAddresses() throws Exception {
		// Tomcat 11 lists the trusted proxies as address ranges (CIDR), e.g. "10.0.0.0/8, 172.16.0.0/12, ..."
		NetMaskSet trusted = new NetMaskSet();
		assertThat(trusted.addAll(remoteIpValve().getInternalProxies())).as("ranges that failed to parse").isEmpty();

		assertThat(trusted.contains("172.18.0.3")).as("Caddy on the Docker network").isTrue();
		assertThat(trusted.contains("10.0.1.4")).as("the VM's private address").isTrue();
		assertThat(trusted.contains("127.0.0.1")).isTrue();
		assertThat(trusted.contains("49.43.115.136")).as("a visitor on the internet").isFalse();
		assertThat(trusted.contains("20.41.100.125")).as("a public cloud address").isFalse();
	}

	private int loginFrom(String visitorIp) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port() + "/api/auth/login"))
			.header("Content-Type", "application/json")
			.header("X-Forwarded-For", visitorIp)
			.POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"nobody@example.com\",\"password\":\"wrong-password-123\"}"))
			.build();
		return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	private int port() {
		return context.getWebServer().getPort();
	}

	private RemoteIpValve remoteIpValve() {
		TomcatWebServer server = (TomcatWebServer) context.getWebServer();
		Valve[] valves = server.getTomcat().getEngine().getPipeline().getValves();
		return Arrays.stream(valves)
			.filter(RemoteIpValve.class::isInstance)
			.map(RemoteIpValve.class::cast)
			.findFirst()
			.orElseThrow(() -> new AssertionError("RemoteIpValve is not installed: X-Forwarded-For is ignored"));
	}

}
