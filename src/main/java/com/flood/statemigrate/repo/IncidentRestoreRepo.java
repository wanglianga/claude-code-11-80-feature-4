package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.IncidentRestore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IncidentRestoreRepo extends JpaRepository<IncidentRestore, Long> {
    List<IncidentRestore> findByStreamIdOrderByIdDesc(Long streamId);
}
