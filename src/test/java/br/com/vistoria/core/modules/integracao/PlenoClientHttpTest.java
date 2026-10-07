package br.com.vistoria.core.modules.integracao;

import br.com.vistoria.core.AbstractIntegrationTest;
import br.com.vistoria.core.gateway.HttpGatewayResponse;
import br.com.vistoria.core.gateway.MockHttpGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("PlenoClientHttp - Timeout and Fallback Tests")
class PlenoClientHttpTest extends AbstractIntegrationTest {

    @Autowired
    private PlenoClientHttp plenoClientHttp;

    @Autowired
    private MockHttpGateway mockHttpGateway;

    @Autowired
    private PlenoTokenStore tokenStore;

    @DynamicPropertySource
    static void configurePleno(DynamicPropertyRegistry registry) {
        // Set a dummy base URL to satisfy PlenoClientHttp initialization
        registry.add("inspecta.pleno.base-url", () -> "http://localhost:8888");
        registry.add("inspecta.pleno.timeout-seconds", () -> "3");
    }

    @BeforeEach
    void setUp() {
        mockHttpGateway.limpar();
        // PlenoClientHttp.chamar() calls tokenStore.get() first and throws
        // "Token Pleno indisponivel" if it's null; it only tries authService.renovar() as a
        // recovery step, which itself is a silent no-op whenever client-id/client-secret
        // aren't configured (PlenoAuthService.renovar(), by design — see its own guard).
        // Neither of those properties is set here (this class only overrides base-url and
        // timeout-seconds), so every call would fail before ever reaching the mocked HTTP
        // gateway response below. Seed a valid token directly so each test's single
        // programarProximaComAtraso(...) response is consumed by the actual pessoa/imovel/
        // solicitacao data call under test, not silently short-circuited by the auth gap.
        tokenStore.put("test-access-token", 3600);
    }

    @Test
    @DisplayName("successful call: returns fresh data from Pleno")
    void testSuccessfulPlenoCall() {
        String plenoId = "pessoa-999";

        // Schedule a successful response that returns quickly
        String responseBody = """
                {
                    "success": true,
                    "data": {"id": "pessoa-999", "name": "Novo Pessoa"}
                }
                """;
        mockHttpGateway.programarProximaComAtraso(
                Duration.ofMillis(500),
                new HttpGatewayResponse(200, Map.of(), responseBody)
        );

        PlenoClient.Resultado resultado = plenoClientHttp.pessoa(plenoId);

        assertThat(resultado.fonte())
                .as("Successful call should return data from pleno source")
                .isEqualTo("pleno");

        // PlenoClientHttp.chamar() returns body.path("data").toString() — Jackson's
        // JsonNode#toString() serializes compact (no space after ':'), regardless of the
        // spacing in the source payload's text block above.
        assertThat(resultado.dadosJson())
                .as("Successful call should return the fresh data")
                .isEqualTo("{\"id\":\"pessoa-999\",\"name\":\"Novo Pessoa\"}");

        assertThat(resultado.recursoTipo())
                .as("Result should preserve resource type")
                .isEqualTo("pessoa");
    }

    @Test
    @DisplayName("circuit-breaker fallback: returns unavailable when call fails")
    void testCircuitBreakerFallbackReturnsUnavailable() {
        String plenoId = "solicitacao-789";

        // Schedule a response that will fail
        mockHttpGateway.programarProximaComAtraso(
                Duration.ofSeconds(1),
                new HttpGatewayResponse(500, Map.of(), "Internal Server Error")
        );

        PlenoClient.Resultado resultado = plenoClientHttp.solicitacao(plenoId);

        // Verify that the result indicates unavailable (fallback was triggered)
        assertThat(resultado.fonte())
                .as("Fallback should return unavailable source when call fails")
                .isEqualTo("indisponivel");

        assertThat(resultado.dadosJson())
                .as("Fallback should return null data when unavailable")
                .isNull();

        assertThat(resultado.recursoTipo())
                .as("Fallback should preserve the resource type")
                .isEqualTo("solicitacao");
    }
}
