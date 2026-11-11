package dev.pulse.check;

/**
 * Runs one check. Never throws: every problem becomes a failed {@link CheckOutcome}.
 * An interface so scheduler tests can use a fake that counts calls.
 */
public interface Checker {

	CheckOutcome check(ClaimedMonitor monitor);

}
