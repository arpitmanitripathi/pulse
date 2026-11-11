package dev.pulse.alert;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertChannelRepository extends JpaRepository<AlertChannel, UUID> {

	List<AlertChannel> findAllByUserIdOrderByCreatedAtAsc(UUID userId);

	Optional<AlertChannel> findByIdAndUserId(UUID id, UUID userId);

	long countByUserId(UUID userId);

}
