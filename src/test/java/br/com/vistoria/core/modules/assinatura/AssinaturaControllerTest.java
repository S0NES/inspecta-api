package br.com.vistoria.core.modules.assinatura;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AssinaturaController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("AssinaturaController - Digital signature orchestration")
class AssinaturaControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private ApiKeyFilter apiKeyFilter;

    @MockitoBean
    private AssinaturaService service;

    @Autowired
    private ObjectMapper mapper;

    private static final UUID DOC_100 = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID DOC_50 = UUID.fromString("00000000-0000-0000-0000-000000000050");
    private static final UUID DOC_999 = UUID.fromString("00000000-0000-0000-0000-000000000999");

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/documentos/{tipo}/{id}/assinar requests signature for ADMIN")
    void assinar_adminUser_requestsSignature() throws Exception {
        var signatario1 = new Signatario();
        signatario1.setEmail("user1@example.com");
        signatario1.setNome("User One");
        var signatario2 = new Signatario();
        signatario2.setEmail("user2@example.com");
        signatario2.setNome("User Two");
        var signatarios = List.of(signatario1, signatario2);

        doNothing().when(service).solicitarAssinatura("laudo", DOC_100, signatarios);

        mvc.perform(post("/v1/documentos/laudo/" + DOC_100 + "/assinar")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(signatarios)))
                .andExpect(status().isAccepted());
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("POST /v1/documentos/{tipo}/{id}/assinar allows SUPORTE")
    void assinar_suporteUser_requestsSignature() throws Exception {
        var signatario = new Signatario();
        signatario.setEmail("user@example.com");
        signatario.setNome("User");
        var signatarios = List.of(signatario);

        doNothing().when(service).solicitarAssinatura("contrato", DOC_50, signatarios);

        mvc.perform(post("/v1/documentos/contrato/" + DOC_50 + "/assinar")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(signatarios)))
                .andExpect(status().isAccepted());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("POST /v1/documentos/{tipo}/{id}/assinar denies CLIENTE")
    void assinar_clienteUser_returnsForbidden() throws Exception {
        var signatario = new Signatario();
        signatario.setEmail("user@example.com");
        signatario.setNome("User");
        var signatarios = List.of(signatario);

        mvc.perform(post("/v1/documentos/laudo/" + DOC_100 + "/assinar")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(signatarios)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/documentos/{tipo}/{id}/assinar with unknown document returns 400")
    void assinar_nonexistentDocument_returnsBadRequest() throws Exception {
        var signatario = new Signatario();
        signatario.setEmail("user@example.com");
        signatario.setNome("User");
        var signatarios = List.of(signatario);

        // Signatario nao implementa equals(): a lista desserializada do JSON nunca casa com
        // a instancia montada aqui, entao o stub por igualdade jamais era aplicado e o
        // endpoint respondia 202. Combina por tipo.
        doThrow(new IllegalArgumentException("Documento nao encontrado"))
                .when(service).solicitarAssinatura(eq("laudo"), eq(DOC_999), anyList());

        mvc.perform(post("/v1/documentos/laudo/" + DOC_999 + "/assinar")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(signatarios)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /v1/documentos/{tipo}/{id}/assinar requires authentication")
    void assinar_unauthenticated_returnsUnauthorized() throws Exception {
        var signatario = new Signatario();
        signatario.setEmail("user@example.com");
        signatario.setNome("User");
        var signatarios = List.of(signatario);

        mvc.perform(post("/v1/documentos/laudo/" + DOC_100 + "/assinar")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(signatarios)))
                .andExpect(status().isUnauthorized());
    }
}
