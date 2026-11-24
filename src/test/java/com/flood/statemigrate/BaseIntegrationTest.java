package com.flood.statemigrate;

import com.flood.statemigrate.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
abstract class BaseIntegrationTest {

    @Autowired protected StreamDefinitionRepo streamRepo;
    @Autowired protected RulePackageRepo rulePackageRepo;
    @Autowired protected CheckpointManifestRepo checkpointRepo;
    @Autowired protected StreamEventRepo eventRepo;
    @Autowired protected StateProofRepo proofRepo;
    @Autowired protected MigrationPlanRepo planRepo;
    @Autowired protected DualTrackRunRepo runRepo;
    @Autowired protected DualTrackDiffRepo diffRepo;
    @Autowired protected SwitchGateRepo gateRepo;
    @Autowired protected RollbackRecordRepo rollbackRepo;
    @Autowired protected EvidenceEntryRepo evidenceRepo;
    @Autowired protected RetentionPolicyRepo retentionPolicyRepo;
    @Autowired protected SnapshotLineageRepo lineageRepo;
    @Autowired protected PartitionScaleEventRepo scaleEventRepo;
    @Autowired protected BypassRuleRepo bypassRuleRepo;
    @Autowired protected RecoveryDrillRepo drillRepo;
    @Autowired protected CompressionPackageRepo compressionPackageRepo;
    @Autowired protected IncidentRestoreRepo incidentRestoreRepo;

    protected ScenarioBuilder scenario;

    @BeforeEach
    void cleanDb() {
        incidentRestoreRepo.deleteAll();
        compressionPackageRepo.deleteAll();
        drillRepo.deleteAll();
        lineageRepo.deleteAll();
        scaleEventRepo.deleteAll();
        bypassRuleRepo.deleteAll();
        retentionPolicyRepo.deleteAll();
        diffRepo.deleteAll();
        runRepo.deleteAll();
        evidenceRepo.deleteAll();
        planRepo.deleteAll();
        gateRepo.deleteAll();
        rollbackRepo.deleteAll();
        checkpointRepo.deleteAll();
        eventRepo.deleteAll();
        proofRepo.deleteAll();
        rulePackageRepo.deleteAll();
        streamRepo.deleteAll();
        scenario = new ScenarioBuilder(streamRepo, rulePackageRepo, eventRepo, checkpointRepo);
    }
}
