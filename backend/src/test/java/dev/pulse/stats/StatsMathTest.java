package dev.pulse.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.pulse.common.FieldValidationException;

class StatsMathTest {

	static final Instant H0 = Instant.parse("2026-11-12T10:00:00Z");

	static Bucket at(String time, int checks, int failures, Integer p50, Integer p95) {
		return new Bucket(Instant.parse(time), checks, failures, p50, p95);
	}

	@Test
	void uptimeIsRoundedDownSoAFailureNeverShowsAs100() {
		assertThat(StatsMath.uptime(1440, 0)).isEqualTo(100.0);
		assertThat(StatsMath.uptime(1440, 1)).isEqualTo(99.93);   // 99.9305…
		assertThat(StatsMath.uptime(100_000, 4)).isEqualTo(99.99); // 99.996, not 100.00
		assertThat(StatsMath.uptime(3, 1)).isEqualTo(66.66);       // 66.666…
		assertThat(StatsMath.uptime(4, 4)).isEqualTo(0.0);
		assertThat(StatsMath.uptime(0, 0)).as("no checks is not 0 %").isNull();
	}

	@Test
	void combiningAddsCountsAndWeightsLatencyBySuccessfulChecks() {
		// hour A: 60 checks, 0 failed, p50 100, p95 200   (weight 60)
		// hour B: 60 checks, 40 failed, p50 400, p95 1000 (weight 20)
		// p50 = (100×60 + 400×20) / 80 = 175 ; p95 = (200×60 + 1000×20) / 80 = 400
		Bucket all = StatsMath.combine(H0, List.of(
				at("2026-11-12T10:00:00Z", 60, 0, 100, 200),
				at("2026-11-12T11:00:00Z", 60, 40, 400, 1000)));

		assertThat(all.checks()).isEqualTo(120);
		assertThat(all.failures()).isEqualTo(40);
		assertThat(all.uptime()).isEqualTo(66.66);
		assertThat(all.latencyP50()).isEqualTo(175);
		assertThat(all.latencyP95()).isEqualTo(400);
	}

	@Test
	void anHourWithOnlyFailuresHasNoLatencyAndDoesNotCountTowardsIt() {
		Bucket all = StatsMath.combine(H0, List.of(
				at("2026-11-12T10:00:00Z", 10, 10, null, null),
				at("2026-11-12T11:00:00Z", 10, 0, 50, 90)));

		assertThat(all.latencyP50()).isEqualTo(50);
		assertThat(all.uptime()).isEqualTo(50.0);
		assertThat(StatsMath.combine(H0, List.of(at("2026-11-12T10:00:00Z", 5, 5, null, null))).latencyP50())
			.isNull();
		assertThat(StatsMath.combine(H0, List.of()).uptime()).isNull();
	}

	@Test
	void hourlySeriesHasEveryHourIncludingEmptyOnes() {
		List<Bucket> series = StatsMath.hourly(List.of(at("2026-11-12T11:00:00Z", 60, 1, 80, 120)), H0, 3);

		assertThat(series).extracting(Bucket::start).containsExactly(H0, H0.plusSeconds(3600), H0.plusSeconds(7200));
		assertThat(series.get(0).uptime()).isNull();
		assertThat(series.get(1).checks()).isEqualTo(60);
		assertThat(series.get(2).checks()).isZero();
	}

	@Test
	void dailySeriesGroupsHoursByUtcDay() {
		Instant day = Instant.parse("2026-11-11T00:00:00Z");
		List<Bucket> series = StatsMath.daily(List.of(
				at("2026-11-11T00:00:00Z", 60, 0, 100, 100),
				at("2026-11-11T23:00:00Z", 60, 6, 300, 300),
				at("2026-11-12T00:00:00Z", 10, 10, null, null)), day, 3);

		assertThat(series.get(0).checks()).isEqualTo(120);
		assertThat(series.get(0).uptime()).isEqualTo(95.0);
		assertThat(series.get(0).latencyP50()).isEqualTo(195); // (100×60 + 300×54) / 114 = 194.7
		assertThat(series.get(1).uptime()).isEqualTo(0.0);
		assertThat(series.get(2).uptime()).isNull();
	}

	@Test
	void windowsStartAtWholeHoursAndDays() {
		Instant now = Instant.parse("2026-11-12T10:37:12Z");

		assertThat(StatsWindow.H24.from(now)).isEqualTo("2026-11-11T10:37:12Z");
		assertThat(StatsWindow.D7.from(now)).isEqualTo("2026-11-05T11:00:00Z");  // 168 hours incl. the current one
		assertThat(StatsWindow.D30.from(now)).isEqualTo("2026-10-14T00:00:00Z"); // 30 days incl. today
		assertThat(StatsWindow.parse("7d")).isEqualTo(StatsWindow.D7);
		assertThatThrownBy(() -> StatsWindow.parse("1y")).isInstanceOf(FieldValidationException.class)
			.hasMessage("Window must be 24h, 7d or 30d.");
	}

}
