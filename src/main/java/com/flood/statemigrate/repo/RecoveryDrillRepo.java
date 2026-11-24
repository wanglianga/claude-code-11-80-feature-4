package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.DrillStatus;
import com.flood.statemigrate.domain.RecoveryDrill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecoveryDrillRepo extends JpaRepository<RecoveryDrill, Long> {
    List<RecoveryDrill> findByStreamIdOrderByIdDesc(Long streamId);

    List<RecoveryDrill> findByCheckpointIdOrderByIdAsc(Long checkpointId);

    List<RecoveryDrill> findByStreamIdAndCheckpointIdOrderByIdDesc(Long streamId, Long checkpointId);

    long countByStreamIdAndCheckpointIdAndStatusAndDigestMatchTrue(
            Long streamId, Long checkpointId, DrillStatus status);
}
