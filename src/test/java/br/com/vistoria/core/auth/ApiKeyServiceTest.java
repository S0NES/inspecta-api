package br.com.vistoria.core.auth;

import br.com.vistoria.core.modules.imobiliaria.ImobiliariaRepository;
import br.com.vistoria.core.shared.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyServiceTest {

    private TokenRepository tokens;
    private ImobiliariaRepository imobiliarias;
    private ApiKeyService service;

    private static final UUID IMOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final UUID KEY_ID = UUID.fromString("00000000-0000-0000-0000-000000000077");

    @BeforeEach
    void setUp() {
        tokens = mock(TokenRepository.class);
        imobiliarias = mock(ImobiliariaRepository.class);
        service = new ApiKeyService(tokens, imobiliarias);
        when(imobiliarias.existsById(IMOB_ID)).thenReturn(true);
    }

    @Test
    void createRejectsExpiredOrMissingExpiry() {
        assertThrows(IllegalArgumentException.class, () -> service.create(IMOB_ID, new String[]{"read"}, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(IMOB_ID, new String[]{"read"}, Instant.now().minusSeconds(10)));
    }

    @Test
    void createRejectsUnknownImobiliaria() {
        assertThrows(NotFoundException.class,
                () -> service.create(UUID.randomUUID(), new String[]{"read"}, Instant.now().plusSeconds(60)));
    }

    @Test
    void createRejectsMissingImobiliaria() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create(null, new String[]{"read"}, Instant.now().plusSeconds(60)));
    }

    @Test
    void createPersistsTokenLinkedToImobiliariaAndReturnsPlaintextOnce() {
        when(tokens.save(any(TokenEntity.class))).thenAnswer(invocation -> {
            TokenEntity entity = invocation.getArgument(0);
            entity.setId(KEY_ID);
            return entity;
        });

        ApiKeyService.CreatedKey created = service.create(IMOB_ID, new String[]{"read", "write"}, Instant.now().plusSeconds(60));

        ArgumentCaptor<TokenEntity> captor = ArgumentCaptor.forClass(TokenEntity.class);
        verify(tokens).save(captor.capture());
        TokenEntity persisted = captor.getValue();

        assertThat(created.id()).isEqualTo(KEY_ID);
        assertThat(created.imobiliariaId()).isEqualTo(IMOB_ID);
        assertThat(created.plaintext()).startsWith(persisted.getPrefix() + ".");
        assertThat(created.plaintext()).endsWith(persisted.getUltimos4());
        assertThat(created.mascara()).isEqualTo("••••" + persisted.getUltimos4());
        assertThat(persisted.getImobiliariaId()).isEqualTo(IMOB_ID);
        assertThat(persisted.getUsuarioId()).isNull();
        assertThat(persisted.getTipo()).isEqualTo(TokenEntity.Tipo.api_key);
        assertThat(persisted.getEscopos()).containsExactly("read", "write");
        assertThat(persisted.getUltimos4()).hasSize(4);
    }

    @Test
    void listNeverExposesPlaintext_onlyMascara() {
        TokenEntity t = new TokenEntity();
        t.setId(KEY_ID);
        t.setTipo(TokenEntity.Tipo.api_key);
        t.setImobiliariaId(IMOB_ID);
        t.setUltimos4("1a2b");
        t.setEscopos(new String[]{"read"});
        t.setExpiraEm(Instant.now().plusSeconds(60));
        when(tokens.findByTipoOrderByCriadoEmDesc(TokenEntity.Tipo.api_key)).thenReturn(java.util.List.of(t));

        var info = service.list().get(0);
        assertThat(info.mascara()).isEqualTo("••••1a2b");
        assertThat(info.imobiliariaId()).isEqualTo(IMOB_ID);
    }

    @Test
    void validateAcceptsMatchingSecretAndRejectsExpiredOrWrongTokens() {
        TokenEntity entity = new TokenEntity();
        entity.setPrefix("mk_test");
        entity.setTipo(TokenEntity.Tipo.api_key);
        entity.setHash(TokenHasher.sha256("secret-123"));
        entity.setExpiraEm(Instant.now().plusSeconds(60));
        entity.setRevogado(false);

        when(tokens.findByPrefixAndTipoAndRevogadoFalse(eq("mk_test"), eq(TokenEntity.Tipo.api_key)))
                .thenReturn(Optional.of(entity));

        assertThat(service.validate("mk_test.secret-123")).containsSame(entity);
        assertThat(service.validate("mk_test.wrong-secret")).isEmpty();

        entity.setExpiraEm(Instant.now().minusSeconds(10));
        assertThat(service.validate("mk_test.secret-123")).isEmpty();
    }
}
