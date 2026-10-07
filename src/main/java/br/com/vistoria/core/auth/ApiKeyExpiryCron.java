package br.com.vistoria.core.auth;

import br.com.vistoria.core.modules.notify.NotifyTemplates;
import br.com.vistoria.core.modules.usuario.UsuarioRepository;
import br.com.vistoria.core.shared.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ApiKeyExpiryCron {

    private final ApiKeyService apiKeyService;
    private final UsuarioRepository usuarios;
    private final OutboxService outbox;

    @Scheduled(cron = "0 0 8 * * *")
    @SchedulerLock(name = "apiKeyExpiryWarn", lockAtMostFor = "10m")
    @Transactional
    public void warnExpiring() {
        apiKeyService.expiringWithin(Duration.ofDays(7)).forEach(t -> {
            String destinatario = usuarios.findById(t.getUsuarioId()).map(u -> u.getEmail()).orElse(null);
            if (destinatario == null) {
                log.warn("API key {} expirando sem usuario dono valido (usuarioId={}), notificacao nao enviada",
                        t.getPrefix(), t.getUsuarioId());
                return;
            }
            outbox.publish("notify.requested.api-key-expiring", Map.of(
                    "eventId", "keyexp-" + t.getId() + "-" + t.getExpiraEm().getEpochSecond(),
                    "canal", "email",
                    "templateId", NotifyTemplates.API_KEY_EXPIRING,
                    "destinatario", destinatario,
                    "variaveis", Map.of("prefix", t.getPrefix(), "expiraEm", t.getExpiraEm().toString())));
        });
    }
}
