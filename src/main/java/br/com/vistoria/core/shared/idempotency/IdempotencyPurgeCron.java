package br.com.vistoria.core.shared.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purga chaves de idempotencia expiradas (TTL 24h). Cron de dominio no inspecta-api
 * (arquitetura.md regra 8), com @SchedulerLock por conta das replicas=2.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyPurgeCron {

    private final IdempotencyService idempotency;

    @Scheduled(cron = "0 30 3 * * *")
    @SchedulerLock(name = "idempotencyPurge", lockAtMostFor = "15m")
    public void purge() {
        int n = idempotency.purgarExpirados();
        if (n > 0) log.info("Idempotency purge: {} chaves expiradas removidas", n);
    }
}
