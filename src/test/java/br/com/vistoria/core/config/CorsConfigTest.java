package br.com.vistoria.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CorsConfig - config dedicada para webhooks")
class CorsConfigTest {

    private UrlBasedCorsConfigurationSource source() {
        CorsConfig config = new CorsConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", List.of("https://app.inspecta.com.br"));
        return (UrlBasedCorsConfigurationSource) config.corsConfigurationSource();
    }

    private CorsConfiguration forPath(String path) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.addHeader("Origin", "https://algum-provedor-aleatorio.example");
        req.addHeader("Access-Control-Request-Method", "POST");
        return source().getCorsConfiguration(req);
    }

    @Test
    @DisplayName("/v1/webhooks/metricas/** usa origem curinga e sem credenciais")
    void webhookPath_curingaSemCredenciais() {
        CorsConfiguration cfg = forPath("/v1/webhooks/metricas/resend");

        assertThat(cfg).isNotNull();
        assertThat(cfg.getAllowCredentials()).isFalse();
        assertThat(cfg.checkOrigin("https://algum-provedor-aleatorio.example"))
                .isEqualTo("https://algum-provedor-aleatorio.example");
    }

    @Test
    @DisplayName("/v1/solicitacoes continua com a config credenciada de origem exata")
    void appPath_credenciadaOrigemExata() {
        CorsConfiguration cfg = forPath("/v1/solicitacoes");

        assertThat(cfg).isNotNull();
        assertThat(cfg.getAllowCredentials()).isTrue();
        assertThat(cfg.checkOrigin("https://app.inspecta.com.br")).isEqualTo("https://app.inspecta.com.br");
        assertThat(cfg.checkOrigin("https://algum-provedor-aleatorio.example")).isNull();
    }
}
