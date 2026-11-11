package dev.pulse.incident;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.pulse.incident.IncidentStateMachine.Event;
import dev.pulse.incident.IncidentStateMachine.Transition;
import dev.pulse.monitor.MonitorState;

/** Every rule of the state machine, one row each (docs/DESIGN.md, "Monitor states"). */
class IncidentStateMachineTest {

	@ParameterizedTest(name = "{0} f={1} s={2} + {3} -> {4} f={5} s={6} {7}")
	@CsvSource({
			// state,  fails, succ, result,  -> state, fails, succ, event
			"UNKNOWN,  0,     0,    success,    UP,     0,     1,    NONE",
			"UNKNOWN,  0,     0,    failure,    UNKNOWN,1,     0,    NONE",
			"UNKNOWN,  1,     0,    failure,    UNKNOWN,2,     0,    NONE",
			"UNKNOWN,  2,     0,    failure,    DOWN,   3,     0,    OPEN_INCIDENT",
			"UP,       0,     5,    success,    UP,     0,     6,    NONE",
			"UP,       0,     5,    failure,    UP,     1,     0,    NONE",
			"UP,       1,     0,    failure,    UP,     2,     0,    NONE",
			"UP,       2,     0,    failure,    DOWN,   3,     0,    OPEN_INCIDENT",
			"UP,       2,     0,    success,    UP,     0,     1,    NONE",   // a success resets the failure count
			"DOWN,     3,     0,    failure,    DOWN,   4,     0,    NONE",   // still down: no second incident
			"DOWN,     9,     0,    success,    DOWN,   0,     1,    NONE",
			"DOWN,     0,     1,    success,    UP,     0,     2,    RESOLVE_INCIDENT",
			"DOWN,     0,     1,    failure,    DOWN,   1,     0,    NONE" }) // one success is not enough
	void everyTransition(MonitorState state, int failures, int successes, String result, MonitorState nextState,
			int nextFailures, int nextSuccesses, Event event) {
		Transition next = IncidentStateMachine.next(state, failures, successes, result.equals("success"));

		assertThat(next).isEqualTo(new Transition(nextState, nextFailures, nextSuccesses, event));
	}

}
