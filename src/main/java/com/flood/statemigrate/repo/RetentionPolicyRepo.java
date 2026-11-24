package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.RetentionPolicy;
import com.flood.statemigrate.domain.WarningLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RetentionPolicyRepo extends JpaRepository<RetentionPolicy, Long> {
    List<RetentionPolicy> findByStreamIdOrderByWarningLevelAsc(Long streamId);

    Optional<RetentionPolicy> findByStreamIdAndWarningLevel(Long streamId, WarningLevel warningLevel);
}
