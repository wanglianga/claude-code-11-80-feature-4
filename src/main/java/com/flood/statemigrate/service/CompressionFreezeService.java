package com.flood.statemigrate.service;

import com.flood.statemigrate.domain.CompressionPackage;
import com.flood.statemigrate.domain.CompressionStatus;
import com.flood.statemigrate.repo.CompressionPackageRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 压缩包冻结传播：任一成员快照演练摘要不一致时，
 * 冻结包含该快照的归档包，并沿 derivedFromPackageRef 传递到全部派生包。
 */
@Service
public class CompressionFreezeService {

    private final CompressionPackageRepo packageRepo;

    public CompressionFreezeService(CompressionPackageRepo packageRepo) {
        this.packageRepo = packageRepo;
    }

    /** 冻结指定压缩包及其所有派生包；返回本次新冻结的包引用 */
    @Transactional
    public List<String> freezeWithDescendants(Long streamId, String packageRef, String reason) {
        List<String> frozen = new ArrayList<>();
        List<CompressionPackage> all = packageRepo.findByStreamIdOrderByIdDesc(streamId);
        List<String> frontier = new ArrayList<>(List.of(packageRef));
        while (!frontier.isEmpty()) {
            List<String> next = new ArrayList<>();
            for (CompressionPackage pkg : all) {
                boolean targeted = frontier.contains(pkg.getPackageRef());
                if (!targeted) continue;
                if (!pkg.isFrozen()) {
                    pkg.setFrozen(true);
                    pkg.setStatus(CompressionStatus.FROZEN);
                    pkg.setFreezeReason(reason);
                    packageRepo.save(pkg);
                    frozen.add(pkg.getPackageRef());
                }
                for (CompressionPackage child : all) {
                    if (pkg.getPackageRef().equals(child.getDerivedFromPackageRef())
                            && !child.isFrozen()) {
                        next.add(child.getPackageRef());
                    }
                }
            }
            frontier = next;
        }
        return frozen;
    }

    /** 该包是否直接或间接受冻结祖先牵连 */
    public boolean taintedByFrozenAncestor(CompressionPackage pkg) {
        String ref = pkg.getDerivedFromPackageRef();
        int guard = 0;
        while (ref != null && guard++ < 64) {
            CompressionPackage parent = packageRepo.findByPackageRef(ref).orElse(null);
            if (parent == null) return false;
            if (parent.isFrozen()) return true;
            ref = parent.getDerivedFromPackageRef();
        }
        return false;
    }
}
