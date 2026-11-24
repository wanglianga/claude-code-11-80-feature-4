package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.PartitionScaleEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface PartitionScaleEventRepo extends JpaRepository<PartitionScaleEvent, Long> {
    List<PartitionScaleEvent> findByStreamIdOrderByOccurredAtAscIdAsc(Long streamId);

    List<PartitionScaleEvent> findByStreamIdAndOccurredAtAfterOrderByOccurredAtAscIdAsc(
            Long streamId, Instant after);
}
