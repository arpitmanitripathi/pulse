package dev.pulse.monitor;

public enum MonitorState {
	/** Not checked yet, or reset after the URL changed or the monitor was resumed. */
	UNKNOWN,
	UP,
	DOWN
}
