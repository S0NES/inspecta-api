package br.com.vistoria.core.modules.integracao;

import br.com.vistoria.core.gateway.HttpGateway;
import br.com.vistoria.core.gateway.HttpGatewayRequest;
import br.com.vistoria.core.gateway.HttpGatewayResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Unit-level counterpart to PlenoClientHttpTest (which extends AbstractIntegrationTest and needs a
// real Postgres/RabbitMQ via Testcontainers to wire the full Spring context). This class exercises
// PlenoClientHttp.chamar()'s branches directly with mocked collaborators, so the HTTP-error-mapping
// and caching logic is covered even where Testcontainers/Docker isn't reachable. Because the
// @CircuitBreaker annotation only takes effect through a Spring AOP proxy, calling pessoa()/imovel()/
// solicitacao() here exercises chamar() directly rather than resilience4j's fallback wiring -- that
// wiring itself is what PlenoClientHttpTest (integration) verifies.
@ExtendWith(MockitoExtension.class)
@DisplayName("PlenoClientHttp - chamar() branches (unit)")
class PlenoClientHttpUnitTest {

    @Mock
    private PlenoTokenStore tokenStore;

    @Mock
    private PlenoAuthService authService;

    @Mock
    private HttpGateway httpGateway;

    @Mock
    private IntegracaoTokenCacheRepository cacheRepository;

    private PlenoClientHttp client;

    @BeforeEach
    void setUp() {
        client = new PlenoClientHttp(tokenStore, authService, httpGateway, cacheRepository);
        ReflectionTestUtils.setField(client, "baseUrl", "http://pleno.local");
        ReflectionTestUtils.setField(client, "cacheTtlSeconds", 900L);
        ReflectionTestUtils.setField(client, "timeoutSeconds", 3L);
    }

    @Test
    @DisplayName("blank base-url is rejected before touching the token store")
    void chamar_blankBaseUrl_throws() {
        ReflectionTestUtils.setField(client, "baseUrl", "");

        assertThatThrownBy(() -> client.pessoa("p-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inspecta.pleno.base-url");

        verifyNoInteractions(tokenStore, httpGateway);
    }

    @Test
    @DisplayName("no token in store triggers a renewal attempt before failing")
    void chamar_noToken_triesRenewalThenFails() {
        when(tokenStore.get()).thenReturn(null);

        assertThatThrownBy(() -> client.imovel("i-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Token Pleno indisponivel");

        verify(authService).renovar();
        verifyNoInteractions(httpGateway);
    }

    @Test
    @DisplayName("token obtained only after renewal is used for the call")
    void chamar_tokenAppearsAfterRenewal_usesIt() throws Exception {
        when(tokenStore.get()).thenReturn(null, "fresh-token");
        when(httpGateway.execute(any(HttpGatewayRequest.class))).thenReturn(
                new HttpGatewayResponse(200, Map.of(), "{\"success\":true,\"data\":{\"id\":\"p-1\"}}"));

        var resultado = client.pessoa("p-1");

        assertThat(resultado.fonte()).isEqualTo("pleno");
        verify(authService).renovar();

        ArgumentCaptor<HttpGatewayRequest> captor = ArgumentCaptor.forClass(HttpGatewayRequest.class);
        verify(httpGateway).execute(captor.capture());
        assertThat(captor.getValue().headers()).containsEntry("Authorization", "Bearer fresh-token");
    }

    @Test
    @DisplayName("non-2xx HTTP response is mapped to a RuntimeException with the status code")
    void chamar_httpFailureStatus_throwsWithStatus() throws Exception {
        when(tokenStore.get()).thenReturn("tok");
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(500, Map.of(), "boom"));

        assertThatThrownBy(() -> client.solicitacao("s-1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    @DisplayName("body with success=false is mapped to a RuntimeException with the error message")
    void chamar_successFalse_throwsWithErrorMessage() throws Exception {
        when(tokenStore.get()).thenReturn("tok");
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, Map.of(),
                "{\"success\":false,\"error\":{\"message\":\"nao encontrado\"}}"));

        assertThatThrownBy(() -> client.pessoa("p-404"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("nao encontrado");
    }

    @Test
    @DisplayName("body with success=false and no error message falls back to a default message")
    void chamar_successFalseNoMessage_usesDefaultMessage() throws Exception {
        when(tokenStore.get()).thenReturn("tok");
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, Map.of(), "{\"success\":false}"));

        assertThatThrownBy(() -> client.pessoa("p-1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("erro desconhecido");
    }

    @Test
    @DisplayName("blank response body is treated as an empty object and fails as success=false")
    void chamar_blankBody_treatedAsEmptyObject() throws Exception {
        when(tokenStore.get()).thenReturn("tok");
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, Map.of(), ""));

        assertThatThrownBy(() -> client.pessoa("p-1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("erro desconhecido");
    }

    @Test
    @DisplayName("gateway throwing a checked exception is wrapped with resource type in the message")
    void chamar_gatewayThrows_wrapsException() throws Exception {
        when(tokenStore.get()).thenReturn("tok");
        when(httpGateway.execute(any())).thenThrow(new java.io.IOException("timeout"));

        assertThatThrownBy(() -> client.imovel("i-1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Pleno imovel indisponivel");
    }

    @Test
    @DisplayName("successful call caches the fresh data with an expiry based on cache-ttl-seconds")
    void chamar_success_cachesFreshData() throws Exception {
        when(tokenStore.get()).thenReturn("tok");
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, Map.of(),
                "{\"success\":true,\"data\":{\"id\":\"s-1\",\"status\":\"aberta\"}}"));

        Instant before = Instant.now();
        var resultado = client.solicitacao("s-1");
        Instant after = Instant.now().plusSeconds(900).plusSeconds(1);

        assertThat(resultado.fonte()).isEqualTo("pleno");
        assertThat(resultado.dadosJson()).isEqualTo("{\"id\":\"s-1\",\"status\":\"aberta\"}");

        ArgumentCaptor<IntegracaoTokenCache> captor = ArgumentCaptor.forClass(IntegracaoTokenCache.class);
        verify(cacheRepository).save(captor.capture());
        IntegracaoTokenCache cached = captor.getValue();
        assertThat(cached.getChave()).isEqualTo("pleno:cache:solicitacao:s-1");
        assertThat(cached.getValor()).isEqualTo("{\"id\":\"s-1\",\"status\":\"aberta\"}");
        assertThat(cached.getExpiraEm()).isAfter(before.plusSeconds(899)).isBefore(after);
    }
}
