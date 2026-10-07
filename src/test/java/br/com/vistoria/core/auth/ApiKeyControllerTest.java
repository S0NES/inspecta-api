package br.com.vistoria.core.auth;

import br.com.vistoria.core.config.TestMethodSecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ApiKeyController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("ApiKeyController - API key management")
class ApiKeyControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ApiKeyService service;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private ApiKeyFilter apiKeyFilter;

    @Autowired
    private ObjectMapper mapper;

    private static final UUID KEY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID IMOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000064");

    private Map<String, Object> body(Object expiry) {
        return Map.of("imobiliariaId", IMOB_ID, "escopos", new String[]{"read", "write"}, "expiraEm", expiry);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/api-keys cria a chave e devolve o plaintext uma vez + máscara")
    void create_adminUser_createsKeyWithPlaintext() throws Exception {
        var expiry = Instant.now().plusSeconds(86400 * 30);
        var createdKey = new ApiKeyService.CreatedKey(KEY_ID, "mk_abc.secret1a2b", "••••1a2b", IMOB_ID, expiry);
        when(service.create(eq(IMOB_ID), any(String[].class), any(Instant.class))).thenReturn(createdKey);

        mvc.perform(post("/v1/api-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body(expiry))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(KEY_ID.toString()))
                .andExpect(jsonPath("$.apiKey").value("mk_abc.secret1a2b"))
                .andExpect(jsonPath("$.mascara").value("••••1a2b"))
                .andExpect(jsonPath("$.imobiliariaId").value(IMOB_ID.toString()))
                .andExpect(jsonPath("$.prefix").doesNotExist())
                .andExpect(jsonPath("$.aviso").exists());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("POST /v1/api-keys nega não-ADMIN")
    void create_nonAdminUser_returnsForbidden() throws Exception {
        mvc.perform(post("/v1/api-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body(Instant.now().plusSeconds(86400)))))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/api-keys com expiração inválida -> 400")
    void create_invalidExpiry_returnsBadRequest() throws Exception {
        var pastExpiry = Instant.now().minusSeconds(1000);
        when(service.create(eq(IMOB_ID), any(String[].class), any(Instant.class)))
                .thenThrow(new IllegalArgumentException("expira_em obrigatorio e futuro"));

        mvc.perform(post("/v1/api-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body(pastExpiry))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("DELETE /v1/api-keys/{id} revoga para ADMIN")
    void revoke_adminUser_revokesKey() throws Exception {
        doNothing().when(service).revoke(KEY_ID);
        mvc.perform(delete("/v1/api-keys/" + KEY_ID)).andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("DELETE /v1/api-keys/{id} nega não-ADMIN")
    void revoke_nonAdminUser_returnsForbidden() throws Exception {
        mvc.perform(delete("/v1/api-keys/" + KEY_ID)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/api-keys lista só a máscara (sem hash nem plaintext)")
    void list_adminUser_returnsMetadata() throws Exception {
        var info = new ApiKeyService.ApiKeyInfo(KEY_ID, "••••1a2b", IMOB_ID, new String[]{"read"},
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"), false, false);
        when(service.list()).thenReturn(java.util.List.of(info));

        mvc.perform(get("/v1/api-keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].mascara").value("••••1a2b"))
                .andExpect(jsonPath("$[0].imobiliariaId").value(IMOB_ID.toString()))
                .andExpect(jsonPath("$[0].revogado").value(false))
                .andExpect(jsonPath("$[0].hash").doesNotExist())
                .andExpect(jsonPath("$[0].prefix").doesNotExist())
                .andExpect(jsonPath("$[0].apiKey").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/api-keys?imobiliariaId= filtra por imobiliária")
    void list_filtraPorImobiliaria() throws Exception {
        when(service.listByImobiliaria(IMOB_ID)).thenReturn(java.util.List.of());
        mvc.perform(get("/v1/api-keys").param("imobiliariaId", IMOB_ID.toString()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("GET /v1/api-keys nega não-ADMIN")
    void list_nonAdmin_forbidden() throws Exception {
        mvc.perform(get("/v1/api-keys")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /v1/api-keys exige autenticação")
    void create_unauthenticated_returnsUnauthorized() throws Exception {
        mvc.perform(post("/v1/api-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body(Instant.now().plusSeconds(86400)))))
                .andExpect(status().isUnauthorized());
    }
}
