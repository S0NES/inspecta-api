package br.com.vistoria.core.auth;

import br.com.vistoria.core.AbstractIntegrationTest;
import br.com.vistoria.core.modules.usuario.Usuario;
import br.com.vistoria.core.modules.usuario.UsuarioRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// NOTE: every assertion below targets /actuator/metrics, not /actuator/health.
// SecurityConfig.java permitAll()s /actuator/health unconditionally, so a 401/200
// there proves nothing about ApiKeyFilter. /actuator/metrics falls through to
// .anyRequest().authenticated() with no extra @PreAuthorize role check, so it is
// the plainest endpoint that isolates "did some filter authenticate this request".

@DisplayName("ApiKeyFilter - Fail-Closed Behavior (T2.7)")
class ApiKeyFilterTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UsuarioRepository usuarios;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private JwtService jwtService;

    private Usuario testUser;
    private String validApiKey;

    @BeforeEach
    void setUp() {
        testUser = new Usuario();
        testUser.setEmail("apikey@example.com");
        testUser.setNome("API Key User");
        testUser.setPerfil(Usuario.Perfil.admin);
        testUser.setStatus(Usuario.Status.ativo);
        testUser.setSenhaHash("hash");
        usuarios.save(testUser);

        // Create a valid API key for this user
        var keyData = apiKeyService.create(testUser.getId(), new String[]{"read", "write"},
                Instant.now().plusSeconds(86400));
        validApiKey = keyData.plaintext();
    }

    @Test
    @DisplayName("request with valid API key from active user succeeds (fail-open path)")
    void apiKeyFilter_validKeyActiveUser_succeeds() throws Exception {
        mvc.perform(get("/actuator/metrics")
                .header("X-API-Key", validApiKey))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("request with API key from inactive user is rejected (fail-closed, T2.7)")
    void apiKeyFilter_validKeyInactiveUser_returns401() throws Exception {
        // Deactivate the user
        testUser.setStatus(Usuario.Status.inativo);
        usuarios.save(testUser);

        // Try to use API key - should fail even though key itself is valid
        mvc.perform(get("/actuator/metrics")
                .header("X-API-Key", validApiKey))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("request with API key from deleted user is rejected (fail-closed, T2.7)")
    void apiKeyFilter_validKeyDeletedUser_returns401() throws Exception {
        // Delete the user
        usuarios.deleteById(testUser.getId());

        // Try to use API key - should fail even though key itself is valid
        mvc.perform(get("/actuator/metrics")
                .header("X-API-Key", validApiKey))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("request with invalid API key is rejected")
    void apiKeyFilter_invalidKey_returns401() throws Exception {
        mvc.perform(get("/actuator/metrics")
                .header("X-API-Key", "invalid-key-that-does-not-exist"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("request with revoked API key is rejected")
    void apiKeyFilter_revokedKey_returns401() throws Exception {
        var keyData = apiKeyService.create(testUser.getId(), new String[]{"read"},
                Instant.now().plusSeconds(86400));

        // Use the key first (should work)
        mvc.perform(get("/actuator/metrics")
                .header("X-API-Key", keyData.plaintext()))
                .andExpect(status().isOk());

        // Revoke the key
        apiKeyService.revoke(keyData.id());

        // Try to use revoked key - should fail
        mvc.perform(get("/actuator/metrics")
                .header("X-API-Key", keyData.plaintext()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("request without API key but with valid JWT succeeds")
    void apiKeyFilter_noKeyWithJwt_succeeds() throws Exception {
        // Real access token, exercised through the real JwtAuthFilter — proves ApiKeyFilter's
        // "already authenticated, skip" guard (SecurityContextHolder...getAuthentication()
        // == null) doesn't get in JwtAuthFilter's way when no X-API-Key header is present.
        String jwt = jwtService.issueAccessToken(testUser.getId(), testUser.getPerfil().name(), null);

        mvc.perform(get("/actuator/metrics").cookie(new Cookie("inspecta_access", jwt)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("request with neither API key nor JWT fails")
    void apiKeyFilter_noKeyNoJwt_returnsUnauthorized() throws Exception {
        mvc.perform(get("/v1/solicitacoes")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isUnauthorized());
    }
}
