package dev.pulse.statuspage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Owner reads are filtered by user id, like monitors. */
public interface StatusPageRepository extends JpaRepository<StatusPage, UUID> {

	List<StatusPage> findAllByUserIdOrderByCreatedAtAsc(UUID userId);

	Optional<StatusPage> findByIdAndUserId(UUID id, UUID userId);

	Optional<StatusPage> findBySlug(String slug);

	boolean existsBySlug(String slug);

	long countByUserId(UUID userId);

}
