package br.com.vistoria.core.modules.metrics;

import br.com.vistoria.core.AbstractIntegrationTest;
import br.com.vistoria.core.auth.ApiKeyService;
import br.com.vistoria.core.modules.usuario.Usuario;
import br.com.vistoria.core.modules.usuario.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cadeia de seguranca real (ApiKeyFilter + SecurityConfig + CorsConfig) sobre o webhook de metricas.
 * Testcontainers: pulado sem Docker local, roda em CI.
 */
@DisplayName("Webhook de metricas - cadeia de seguranca (Testcontainers, skipped sem Docker)")
class MetricasWebhookSecurityIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UsuarioRepository usuarios;

    @Autowired
    private ApiKeyService apiKeyService;

    private String keyComMetricas;
    private String keySemMetricas;

    @BeforeEach
    void setUp() {
        Usuario u = new Usuario();
        u.setEmail("servico-metricas@example.com");
        u.setNome("Servico Metricas");
        u.setPerfil(Usuario.Perfil.admin);
        u.setStatus(Usuario.Status.ativo);
        u.setSenhaHash("hash");
        usuarios.save(u);

        keyComMetricas = apiKeyService.create(u.getId(), new String[]{"metricas"},
                Instant.now().plusSeconds(86400)).plaintext();
        keySemMetricas = apiKeyService.create(u.getId(), new String[]{"read"},
                Instant.now().plusSeconds(86400)).plaintext();
    }

    @Test
    @DisplayName("sem X-API-Key -> 401")
    void semChave_401() throws Exception {
        mvc.perform(post("/v1/webhooks/metricas/resend")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("chave sem escopo metricas -> 403")
    void chaveEscopoErrado_403() throws Exception {
        mvc.perform(post("/v1/webhooks/metricas/resend")
                        .header("X-API-Key", keySemMetricas)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("chave com escopo metricas + payload resend valido -> 202")
    void chaveValida_202() throws Exception {
        String payload = "{\"type\":\"email.delivered\",\"data\":{\"email_id\":\"ext-1\"}}";
        mvc.perform(post("/v1/webhooks/metricas/resend")
                        .header("X-API-Key", keyComMetricas)
                        .contentType("application/json").content(payload))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("preflight CORS de origem arbitraria e' ecoado sem Allow-Credentials")
    void preflightCors_origemArbitraria() throws Exception {
        mvc.perform(options("/v1/webhooks/metricas/resend")
                        .header("Origin", "https://provedor-qualquer.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://provedor-qualquer.example"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }
}
