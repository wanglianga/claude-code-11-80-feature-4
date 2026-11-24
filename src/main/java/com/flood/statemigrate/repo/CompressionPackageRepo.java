package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.CompressionPackage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompressionPackageRepo extends JpaRepository<CompressionPackage, Long> {
    List<CompressionPackage> findByStreamIdOrderByIdDesc(Long streamId);

    Optional<CompressionPackage> findByPackageRef(String packageRef);

    List<CompressionPackage> findByStreamIdAndFrozenTrue(Long streamId);
}
