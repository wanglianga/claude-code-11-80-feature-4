package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.SnapshotLineage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SnapshotLineageRepo extends JpaRepository<SnapshotLineage, Long> {
    Optional<SnapshotLineage> findByCheckpointId(Long checkpointId);

    List<SnapshotLineage> findByStreamIdOrderByWindowEndAscIdAsc(Long streamId);

    List<SnapshotLineage> findByStreamIdAndPartitionNoOrderByInputOffsetToAsc(Long streamId, int partitionNo);

    boolean existsByCheckpointId(Long checkpointId);
}
