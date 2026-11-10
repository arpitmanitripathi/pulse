package dev.pulse.monitor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Every read is filtered by the owner's id, so one user can never load
 * another user's monitor, even by guessing its id.
 */
public interface MonitorRepository extends JpaRepository<Monitor, UUID> {

	List<Monitor> findAllByUserIdOrderByCreatedAtAsc(UUID userId);

	Optional<Monitor> findByIdAndUserId(UUID id, UUID userId);

	long countByUserId(UUID userId);

}
