package dev.pulse.alert;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "alert_channels")
public class AlertChannel {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private AlertChannelType type;

	/** An email address, or a webhook URL (a secret: never returned unmasked). */
	@Column(nullable = false, updatable = false)
	private String target;

	@Column(nullable = false)
	private String name;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected AlertChannel() {
		// for JPA
	}

	public AlertChannel(UUID userId, AlertChannelType type, String target, String name, Instant createdAt) {
		this.userId = userId;
		this.type = type;
		this.target = target;
		this.name = name;
		this.createdAt = createdAt;
	}

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public AlertChannelType getType() {
		return type;
	}

	public String getTarget() {
		return target;
	}

	public String getName() {
		return name;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	/** Emails are shown in full; a webhook URL only as host + last 4 characters. */
	public String maskedTarget() {
		if (type == AlertChannelType.EMAIL) {
			return target;
		}
		java.net.URI uri = java.net.URI.create(target);
		String tail = target.length() > 4 ? target.substring(target.length() - 4) : "";
		return "https://" + uri.getHost() + "/…" + tail;
	}

}
