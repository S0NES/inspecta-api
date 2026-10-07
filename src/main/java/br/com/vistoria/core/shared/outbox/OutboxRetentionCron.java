package br.com.vistoria.core.shared.outbox;

import br.com.vistoria.core.shared.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRetentionCron {

    private final OutboxService outbox;

    @Value("${inspecta.outbox.retention-days:14}")
    private int retentionDays;

    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "outboxRetention", lockAtMostFor = "30m")
    @Transactional
    public void purgeOldRows() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = outbox.purgeOlder(cutoff, 1000);
        log.info("Outbox retention cron: purged {} rows", deleted);
    }
}
