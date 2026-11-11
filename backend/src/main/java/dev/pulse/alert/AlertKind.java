package dev.pulse.alert;

/** DOWN and RECOVERED are stored in alert_outbox; TEST is only ever sent directly. */
public enum AlertKind {
	DOWN, RECOVERED, TEST
}
