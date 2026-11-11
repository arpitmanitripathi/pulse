package dev.pulse.statuspage;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A public page at /s/{slug}. Its monitors live in status_page_monitors (see StatusPageService). */
@Entity
@Table(name = "status_pages")
public class StatusPage {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(nullable = false)
	private String slug;

	@Column(nullable = false)
	private String title;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected StatusPage() {
		// for JPA
	}

	StatusPage(UUID userId, String slug, String title, Instant now) {
		this.userId = userId;
		this.slug = slug;
		this.title = title;
		this.createdAt = now;
	}

	void changeSlug(String slug) {
		this.slug = slug;
	}

	void changeTitle(String title) {
		this.title = title;
	}

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public String getSlug() {
		return slug;
	}

	public String getTitle() {
		return title;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
