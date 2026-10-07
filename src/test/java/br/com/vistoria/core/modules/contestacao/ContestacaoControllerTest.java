package br.com.vistoria.core.modules.contestacao;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ContestacaoController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("ContestacaoController - GET /v1/contestacoes list endpoint")
class ContestacaoControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ContestacaoService service;

    @MockBean
    private ContestacaoService.ReparoRepository reparos;

    @MockBean
    private ContestacaoService.ContestacaoRepository contestacoes;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @MockBean
    private ApiKeyFilter apiKeyFilter;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/contestacoes returns empty list for ADMIN (permission-based)")
    void listContestacoes_admin_returnsList() throws Exception {
        when(contestacoes.findAll()).thenReturn(List.of());

        mvc.perform(get("/v1/contestacoes")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("GET /v1/contestacoes returns list for SUPORTE")
    void listContestacoes_suporte_returnsList() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID laudoId = UUID.fromString("00000000-0000-0000-0000-000000000100");
        UUID autorId = UUID.fromString("00000000-0000-0000-0000-000000000050");

        var contestacao = new Contestacao();
        contestacao.setId(id);
        contestacao.setLaudoId(laudoId);
        contestacao.setNodeUuid(UUID.randomUUID());
        contestacao.setAutorId(autorId);
        contestacao.setTexto("Contestação de teste");
        contestacao.setStatus(Contestacao.Status.aberta);
        contestacao.setCriadoEm(Instant.now());

        when(contestacoes.findAll()).thenReturn(List.of(contestacao));

        mvc.perform(get("/v1/contestacoes")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].id").value(id.toString()))
                .andExpect(jsonPath("$.content[0].laudoId").value(laudoId.toString()));
    }

    @Test
    @WithMockUser(roles = "IMOB_GERENCIA")
    @DisplayName("GET /v1/contestacoes returns list for IMOB_GERENCIA (RLS applies at DB level)")
    void listContestacoes_imobGerencia_returnsList() throws Exception {
        when(contestacoes.findAll()).thenReturn(List.of());

        mvc.perform(get("/v1/contestacoes")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @WithMockUser(roles = "VISTORIADOR")
    @DisplayName("GET /v1/contestacoes returns list for VISTORIADOR")
    void listContestacoes_vistoriador_returnsList() throws Exception {
        when(contestacoes.findAll()).thenReturn(List.of());

        mvc.perform(get("/v1/contestacoes")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("GET /v1/contestacoes denies CLIENTE role (permission-based)")
    void listContestacoes_cliente_returnsForbidden() throws Exception {
        mvc.perform(get("/v1/contestacoes")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /v1/contestacoes requires authentication")
    void listContestacoes_unauthenticated_returnsForbidden() throws Exception {
        mvc.perform(get("/v1/contestacoes")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isUnauthorized());
    }
}
