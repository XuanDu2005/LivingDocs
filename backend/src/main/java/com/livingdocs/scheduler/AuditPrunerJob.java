package com.livingdocs.scheduler;

import com.livingdocs.modules.admin.service.AuditRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Scheduled job that prunes old records based on audit retention policies.
 * Runs daily at 2:00 AM to avoid peak hours.
 */
@Component
public class AuditPrunerJob {

    private static final Logger log = LoggerFactory.getLogger(AuditPrunerJob.class);

    private final AuditRetentionService auditRetentionService;

    public AuditPrunerJob(AuditRetentionService auditRetentionService) {
        this.auditRetentionService = auditRetentionService;
    }

    /**
     * Run the audit log pruner every day at 2:00 AM.
     * Uses cron: second minute hour day month weekday
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void runPruner() {
        log.info("Starting scheduled audit log pruning job");
        try {
            AuditRetentionService.PruneResult result = auditRetentionService.pruneAll();
            log.info("Audit pruning completed: {} records pruned across {} policies",
                    result.totalPruned(), result.policiesProcessed());
        } catch (Exception e) {
            log.error("Audit pruning job failed", e);
        }
    }
}
