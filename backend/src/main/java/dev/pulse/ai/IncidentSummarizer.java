package dev.pulse.ai;

/** Writes a short plain-text explanation of an incident. */
public interface IncidentSummarizer {

	/** False when no API key is configured: incidents are then marked SKIPPED. */
	boolean enabled();

	String summarize(SummaryInput input) throws SummaryException;

}
