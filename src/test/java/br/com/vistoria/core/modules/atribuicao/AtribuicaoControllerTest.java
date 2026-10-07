package br.com.vistoria.core.modules.atribuicao;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import br.com.vistoria.core.shared.outbox.OutboxService;
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

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AtribuicaoController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("AtribuicaoController - Assignment orchestration")
class AtribuicaoControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private ApiKeyFilter apiKeyFilter;

    @MockitoBean
    private OutboxService outbox;

    @Autowired
    private ObjectMapper mapper;

    private static final UUID SERVICO_100 = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID SERVICO_200 = UUID.fromString("00000000-0000-0000-0000-000000000200");
    private static final UUID VISTORIADOR_50 = UUID.fromString("00000000-0000-0000-0000-000000000050");
    private static final UUID VISTORIADOR_30 = UUID.fromString("00000000-0000-0000-0000-000000000030");

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/servicos/{id}/reatribuir publishes event for ADMIN")
    void reatribuir_adminUser_publishesEvent() throws Exception {
        doNothing().when(outbox).publish(anyString(), anyMap());

        Map<String, String> body = Map.of(
                "motivo", "Vistoriador indisponível",
                "vistoriadorAtual", VISTORIADOR_50.toString()
        );

        mvc.perform(post("/v1/servicos/" + SERVICO_100 + "/reatribuir")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.aceito").value(true))
                .andExpect(jsonPath("$.servicoId").value(SERVICO_100.toString()));
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("POST /v1/servicos/{id}/reatribuir allows SUPORTE")
    void reatribuir_suporteUser_publishesEvent() throws Exception {
        doNothing().when(outbox).publish(anyString(), anyMap());

        Map<String, String> body = Map.of(
                "motivo", "Reassignment requested",
                "vistoriadorAtual", VISTORIADOR_30.toString()
        );

        mvc.perform(post("/v1/servicos/" + SERVICO_200 + "/reatribuir")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isAccepted());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("POST /v1/servicos/{id}/reatribuir denies CLIENTE")
    void reatribuir_clienteUser_returnsForbidden() throws Exception {
        Map<String, String> body = Map.of(
                "motivo", "Motivo",
                "vistoriadorAtual", VISTORIADOR_50.toString()
        );

        mvc.perform(post("/v1/servicos/" + SERVICO_100 + "/reatribuir")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/servicos/{id}/reatribuir with blank motivo returns 400")
    void reatribuir_blankReason_returnsBadRequest() throws Exception {
        Map<String, String> body = Map.of(
                "motivo", "   ",
                "vistoriadorAtual", VISTORIADOR_50.toString()
        );

        mvc.perform(post("/v1/servicos/" + SERVICO_100 + "/reatribuir")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/servicos/{id}/reatribuir without motivo returns 400")
    void reatribuir_missingReason_returnsBadRequest() throws Exception {
        Map<String, String> body = Map.of(
                "vistoriadorAtual", VISTORIADOR_50.toString()
        );

        mvc.perform(post("/v1/servicos/" + SERVICO_100 + "/reatribuir")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /v1/servicos/{id}/reatribuir requires authentication")
    void reatribuir_unauthenticated_returnsUnauthorized() throws Exception {
        Map<String, String> body = Map.of(
                "motivo", "Reason",
                "vistoriadorAtual", VISTORIADOR_50.toString()
        );

        mvc.perform(post("/v1/servicos/" + SERVICO_100 + "/reatribuir")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }
}
