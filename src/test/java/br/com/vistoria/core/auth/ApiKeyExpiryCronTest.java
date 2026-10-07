package br.com.vistoria.core.auth;

import br.com.vistoria.core.modules.usuario.Usuario;
import br.com.vistoria.core.modules.usuario.UsuarioRepository;
import br.com.vistoria.core.shared.outbox.OutboxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyExpiryCron - Expiring API key notifications")
class ApiKeyExpiryCronTest {

    @Mock
    private ApiKeyService apiKeyService;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private OutboxService outboxService;

    @InjectMocks
    private ApiKeyExpiryCron cron;

    private static final UUID USUARIO_ID = UUID.fromString("00000000-0000-0000-0000-000000000064");
    private static final UUID OUTRO_USUARIO_ID = UUID.fromString("00000000-0000-0000-0000-000000000999");
    private static final UUID KEY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID KEY_ID_42 = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Test
    @DisplayName("warnExpiring publishes notification for expiring keys")
    void warnExpiring_withExpiringKeys_publishesNotifications() {
        var usuario = new Usuario();
        usuario.setId(USUARIO_ID);
        usuario.setEmail("user@example.com");

        var expiringKey = new TokenEntity();
        expiringKey.setId(KEY_ID);
        expiringKey.setUsuarioId(USUARIO_ID);
        expiringKey.setPrefix("test_prefix");
        expiringKey.setExpiraEm(Instant.now().plusSeconds(172800)); // 2 days

        when(apiKeyService.expiringWithin(Duration.ofDays(7)))
                .thenReturn(List.of(expiringKey));
        when(usuarioRepository.findById(USUARIO_ID))
                .thenReturn(Optional.of(usuario));

        cron.warnExpiring();

        verify(outboxService, times(1)).publish(
                eq("notify.requested.api-key-expiring"),
                argThat((Map<String, Object> map) -> map.get("canal").equals("email") &&
                        map.get("templateId").equals(br.com.vistoria.core.modules.notify.NotifyTemplates.API_KEY_EXPIRING) &&
                        map.get("destinatario").equals("user@example.com"))
        );
    }

    @Test
    @DisplayName("warnExpiring skips notification when usuario not found")
    void warnExpiring_usuarioNotFound_skipsNotification() {
        var expiringKey = new TokenEntity();
        expiringKey.setId(KEY_ID);
        expiringKey.setUsuarioId(OUTRO_USUARIO_ID);
        expiringKey.setPrefix("test_prefix");
        expiringKey.setExpiraEm(Instant.now().plusSeconds(172800));

        when(apiKeyService.expiringWithin(Duration.ofDays(7)))
                .thenReturn(List.of(expiringKey));
        when(usuarioRepository.findById(OUTRO_USUARIO_ID))
                .thenReturn(Optional.empty());

        cron.warnExpiring();

        verify(outboxService, never()).publish(anyString(), anyMap());
    }

    @Test
    @DisplayName("warnExpiring handles no expiring keys gracefully")
    void warnExpiring_noExpiringKeys_succeeds() {
        when(apiKeyService.expiringWithin(Duration.ofDays(7)))
                .thenReturn(List.of());

        cron.warnExpiring();

        verify(outboxService, never()).publish(anyString(), anyMap());
    }

    @Test
    @DisplayName("warnExpiring includes eventId with key id and expiry epoch")
    void warnExpiring_eventIdIncludesKeyAndEpoch() {
        var usuario = new Usuario();
        usuario.setId(USUARIO_ID);
        usuario.setEmail("user@example.com");

        var expiry = Instant.ofEpochSecond(1700000000L);
        var expiringKey = new TokenEntity();
        expiringKey.setId(KEY_ID_42);
        expiringKey.setUsuarioId(USUARIO_ID);
        expiringKey.setPrefix("pfx");
        expiringKey.setExpiraEm(expiry);

        when(apiKeyService.expiringWithin(Duration.ofDays(7)))
                .thenReturn(List.of(expiringKey));
        when(usuarioRepository.findById(USUARIO_ID))
                .thenReturn(Optional.of(usuario));

        cron.warnExpiring();

        verify(outboxService).publish(
                eq("notify.requested.api-key-expiring"),
                argThat((Map<String, Object> map) -> map.get("eventId").toString()
                        .contains("keyexp-" + KEY_ID_42 + "-1700000000"))
        );
    }
}
