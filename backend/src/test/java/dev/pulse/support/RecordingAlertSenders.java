package dev.pulse.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import dev.pulse.alert.AlertChannelType;
import dev.pulse.alert.AlertDeliveryException;
import dev.pulse.alert.AlertMessage;
import dev.pulse.alert.AlertSenders;

/** Records alerts instead of sending them. Can be told to fail the next N sends. */
public class RecordingAlertSenders implements AlertSenders {

	public record Sent(AlertChannelType type, String target, AlertMessage message) {
	}

	private final List<Sent> sent = new CopyOnWriteArrayList<>();
	private final AtomicInteger failuresLeft = new AtomicInteger();

	@Override
	public void send(AlertChannelType type, String target, AlertMessage message) throws AlertDeliveryException {
		if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
			throw new AlertDeliveryException("Slack answered HTTP 500.");
		}
		sent.add(new Sent(type, target, message));
	}

	public List<Sent> sent() {
		return sent;
	}

	public void failNext(int times) {
		failuresLeft.set(times);
	}

	public void reset() {
		sent.clear();
		failuresLeft.set(0);
	}

	@TestConfiguration(proxyBeanMethods = false)
	public static class Config {

		@Bean
		@Primary
		RecordingAlertSenders recordingAlertSenders() {
			return new RecordingAlertSenders();
		}

	}

}
