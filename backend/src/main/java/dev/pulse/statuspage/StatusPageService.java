package dev.pulse.statuspage;

import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import dev.pulse.auth.UserRepository;
import dev.pulse.common.ApiException;
import dev.pulse.common.FieldValidationException;
import dev.pulse.statuspage.StatusPageRequests.CreateStatusPageRequest;
import dev.pulse.statuspage.StatusPageRequests.UpdateStatusPageRequest;

/**
 * The owner's side of status pages: create, list, change, delete. A changed or deleted
 * page's public cache is cleared after the transaction commits, so a reader can't put
 * the old version back into the cache in between.
 */
@Service
public class StatusPageService {

	public static final int MAX_PAGES_PER_USER = 3;

	private static final Logger log = LoggerFactory.getLogger(StatusPageService.class);

	private final StatusPageRepository pages;
	private final UserRepository users;
	private final JdbcTemplate jdbc;
	private final PublicStatusService publicStatus;
	private final Clock clock;

	public StatusPageService(StatusPageRepository pages, UserRepository users, JdbcTemplate jdbc,
			PublicStatusService publicStatus, Clock clock) {
		this.pages = pages;
		this.users = users;
		this.jdbc = jdbc;
		this.publicStatus = publicStatus;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<StatusPageView> list(UUID userId) {
		return pages.findAllByUserIdOrderByCreatedAtAsc(userId).stream().map(this::view).toList();
	}

	@Transactional(readOnly = true)
	public StatusPageView get(UUID userId, UUID pageId) {
		return view(find(userId, pageId));
	}

	@Transactional
	public StatusPageView create(UUID userId, CreateStatusPageRequest request) {
		// Same pattern as monitors: lock the user row so parallel creates can't pass the limit.
		users.lockById(userId).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "User not found."));
		if (pages.countByUserId(userId) >= MAX_PAGES_PER_USER) {
			throw new ApiException(HttpStatus.CONFLICT,
					"You can have at most " + MAX_PAGES_PER_USER + " status pages. Delete one to add another.");
		}
		List<UUID> monitorIds = ownMonitors(userId, request.monitorIds());
		ensureSlugFree(request.slug());
		StatusPage page = save(new StatusPage(userId, request.slug(), request.title(), clock.instant()));
		replaceMonitors(page.getId(), monitorIds);
		evictAfterCommit(page.getSlug());
		log.info("status_page.created status_page_id={} user_id={}", page.getId(), userId);
		return view(page);
	}

	@Transactional
	public StatusPageView update(UUID userId, UUID pageId, UpdateStatusPageRequest request) {
		StatusPage page = find(userId, pageId);
		evictAfterCommit(page.getSlug());
		if (request.monitorIds() != null) {
			replaceMonitors(page.getId(), ownMonitors(userId, request.monitorIds()));
		}
		if (request.title() != null) {
			page.changeTitle(request.title());
		}
		if (request.slug() != null && !request.slug().equals(page.getSlug())) {
			ensureSlugFree(request.slug());
			page.changeSlug(request.slug());
			save(page);
			evictAfterCommit(page.getSlug());
		}
		return view(page);
	}

	@Transactional
	public void delete(UUID userId, UUID pageId) {
		StatusPage page = find(userId, pageId);
		pages.delete(page);
		evictAfterCommit(page.getSlug());
		log.info("status_page.deleted status_page_id={} user_id={}", pageId, userId);
	}

	/** Every id must be one of the user's monitors; another user's id gets the same answer as a made-up one. */
	private List<UUID> ownMonitors(UUID userId, List<UUID> requested) {
		Set<UUID> unique = new LinkedHashSet<>(requested);
		if (unique.size() != requested.size()) {
			throw new FieldValidationException("monitorIds", "Each monitor can be on the page only once.");
		}
		if (unique.isEmpty()) {
			return List.of();
		}
		Set<UUID> owned = new HashSet<>(jdbc.queryForList(
				"SELECT id FROM monitors WHERE user_id = ? AND id = ANY(?)", UUID.class, userId,
				unique.toArray(UUID[]::new)));
		if (!owned.containsAll(unique)) {
			throw new FieldValidationException("monitorIds", "One or more monitors were not found.");
		}
		return List.copyOf(unique);
	}

	private void replaceMonitors(UUID pageId, List<UUID> monitorIds) {
		jdbc.update("DELETE FROM status_page_monitors WHERE status_page_id = ?", pageId);
		for (int i = 0; i < monitorIds.size(); i++) {
			jdbc.update("INSERT INTO status_page_monitors (status_page_id, monitor_id, position) VALUES (?, ?, ?)",
					pageId, monitorIds.get(i), i);
		}
	}

	private void ensureSlugFree(String slug) {
		if (pages.existsBySlug(slug)) {
			throw slugTaken();
		}
	}

	/** Two requests can pass ensureSlugFree at once; the unique index decides, and the loser gets the same 409. */
	private StatusPage save(StatusPage page) {
		try {
			return pages.saveAndFlush(page);
		}
		catch (DataIntegrityViolationException ex) {
			throw slugTaken();
		}
	}

	private static ApiException slugTaken() {
		return new ApiException(HttpStatus.CONFLICT, "That address is already taken. Choose another.");
	}

	private void evictAfterCommit(String slug) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				publicStatus.evict(slug);
			}
		});
	}

	private StatusPage find(UUID userId, UUID pageId) {
		return pages.findByIdAndUserId(pageId, userId)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Status page not found."));
	}

	private StatusPageView view(StatusPage page) {
		List<UUID> monitorIds = jdbc.queryForList(
				"SELECT monitor_id FROM status_page_monitors WHERE status_page_id = ? ORDER BY position",
				UUID.class, page.getId());
		return new StatusPageView(page.getId(), page.getSlug(), page.getTitle(), monitorIds, page.getCreatedAt());
	}

	public record StatusPageView(UUID id, String slug, String title, List<UUID> monitorIds,
			java.time.Instant createdAt) {
	}

}
