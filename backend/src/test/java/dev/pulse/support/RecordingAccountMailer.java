package dev.pulse.support;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import dev.pulse.auth.AccountMailer;

/** Records account emails instead of sending them. Reset emails go out in the background, so tests wait. */
public class RecordingAccountMailer implements AccountMailer {

	public record Sent(String email, String link) {
	}

	private final List<Sent> sent = new CopyOnWriteArrayList<>();

	@Override
	public void sendPasswordReset(String email, String link) {
		sent.add(new Sent(email, link));
	}

	public List<Sent> sent() {
		return sent;
	}

	/** Waits up to 5 s for at least {@code count} emails. */
	public List<Sent> awaitSent(int count) throws InterruptedException {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
		while (sent.size() < count && Instant.now().isBefore(deadline)) {
			Thread.sleep(20);
		}
		return sent;
	}

	public void reset() {
		sent.clear();
	}

	@TestConfiguration(proxyBeanMethods = false)
	public static class Config {

		@Bean
		@Primary
		RecordingAccountMailer recordingAccountMailer() {
			return new RecordingAccountMailer();
		}

	}

}
