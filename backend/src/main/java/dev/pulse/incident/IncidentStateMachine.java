package dev.pulse.incident;

import dev.pulse.monitor.MonitorState;

/**
 * Decides a monitor's next state from one check result. Plain Java: no Spring,
 * no database, so every rule is tested directly (see IncidentStateMachineTest).
 *
 * <pre>
 *            first success
 *  UNKNOWN ─────────────────────► UP
 *     │                          │  ▲
 *     │ 3 failures in a row      │  │ 2 successes in a row → resolve incident
 *     ▼          3 failures      ▼  │
 *   DOWN ◄────── in a row ───────┘  │
 *     │  → open incident            │
 *     └─────────────────────────────┘
 * </pre>
 *
 * Requiring several results in a row stops one network blip from paging
 * someone, and stops a flapping site from sending a stream of alerts.
 */
public final class IncidentStateMachine {

	public static final int FAILURES_TO_OPEN = 3;
	public static final int SUCCESSES_TO_RESOLVE = 2;

	private IncidentStateMachine() {
	}

	public static Transition next(MonitorState state, int failures, int successes, boolean success) {
		if (success) {
			int s = successes + 1;
			return switch (state) {
				case UNKNOWN, UP -> new Transition(MonitorState.UP, 0, s, Event.NONE);
				case DOWN -> s >= SUCCESSES_TO_RESOLVE
						? new Transition(MonitorState.UP, 0, s, Event.RESOLVE_INCIDENT)
						: new Transition(MonitorState.DOWN, 0, s, Event.NONE);
			};
		}
		int f = failures + 1;
		return switch (state) {
			case UNKNOWN, UP -> f >= FAILURES_TO_OPEN
					? new Transition(MonitorState.DOWN, f, 0, Event.OPEN_INCIDENT)
					: new Transition(state, f, 0, Event.NONE);
			case DOWN -> new Transition(MonitorState.DOWN, f, 0, Event.NONE);
		};
	}

	/** The state and counters to store, and what (if anything) happens to incidents. */
	public record Transition(MonitorState state, int failures, int successes, Event event) {
	}

	public enum Event {
		NONE, OPEN_INCIDENT, RESOLVE_INCIDENT
	}

}
