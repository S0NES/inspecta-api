package br.com.vistoria.core.modules.integracao;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import br.com.vistoria.core.modules.integracao.PlenoImportacaoRepositories.PlenoImportacaoVistoriaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PlenoImportacaoController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("PlenoImportacaoController - Importação Pleno (leitura pura)")
class PlenoImportacaoControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private ApiKeyFilter apiKeyFilter;

    @MockitoBean
    private PlenoImportacaoService service;

    @MockitoBean
    private PlenoImportacaoVistoriaRepository repository;

    private static final UUID SERVICO = UUID.fromString("00000000-0000-0000-0000-000000000042");

    private static PlenoImportacaoVistoria linha() {
        var v = new PlenoImportacaoVistoria();
        v.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        v.setSolicitacaoServicoId(SERVICO);
        v.setStatusImportacao("EM_ANDAMENTO");
        v.setTentativasVerificacao(2);
        v.setProximaVerificacaoEm(Instant.parse("2026-09-10T00:00:00Z"));
        v.setCriadoEm(Instant.now());
        return v;
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("GET /v1/pleno/importacao/{servicoId} devolve o estado da importação")
    void porServico_ok() throws Exception {
        when(repository.findBySolicitacaoServicoId(SERVICO)).thenReturn(Optional.of(linha()));

        mvc.perform(get("/v1/pleno/importacao/" + SERVICO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.solicitacaoServicoId").value(SERVICO.toString()))
                .andExpect(jsonPath("$.status").value("EM_ANDAMENTO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/pleno/importacao/{servicoId} devolve 404 quando não há importação")
    void porServico_notFound() throws Exception {
        when(repository.findBySolicitacaoServicoId(any())).thenReturn(Optional.empty());

        mvc.perform(get("/v1/pleno/importacao/" + SERVICO))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("GET /v1/pleno/importacao/{servicoId} nega CLIENTE")
    void porServico_cliente_forbidden() throws Exception {
        mvc.perform(get("/v1/pleno/importacao/" + SERVICO))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("GET /v1/pleno/importacao lista filtrando por status")
    void listar_comStatus_ok() throws Exception {
        when(service.listarPorStatus(PlenoImportacaoVistoria.Status.EM_ANDAMENTO))
                .thenReturn(List.of(linha()));

        mvc.perform(get("/v1/pleno/importacao").param("status", "EM_ANDAMENTO")
                .param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value("EM_ANDAMENTO"));
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("GET /v1/pleno/importacao sem status usa findAll")
    void listar_semStatus_ok() throws Exception {
        when(repository.findAll()).thenReturn(List.of(linha()));

        mvc.perform(get("/v1/pleno/importacao").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/pleno/importacao/{servicoId}/verificar antecipa proxima_verificacao_em sem HTTP à Pleno")
    void verificar_ok() throws Exception {
        var linha = linha();
        when(repository.findBySolicitacaoServicoId(SERVICO)).thenReturn(Optional.of(linha));
        when(repository.save(any(PlenoImportacaoVistoria.class))).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/v1/pleno/importacao/" + SERVICO + "/verificar"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.solicitacaoServicoId").value(SERVICO.toString()));

        verify(repository).save(any(PlenoImportacaoVistoria.class));
        org.junit.jupiter.api.Assertions.assertNotNull(linha.getProximaVerificacaoEm());
        org.junit.jupiter.api.Assertions.assertTrue(
                linha.getProximaVerificacaoEm().isBefore(Instant.now().plusSeconds(5)));
    }

    @Test
    @WithMockUser(roles = "IMOB_GERENCIA")
    @DisplayName("POST /v1/pleno/importacao/{servicoId}/verificar nega IMOB_GERENCIA")
    void verificar_imobGerencia_forbidden() throws Exception {
        mvc.perform(post("/v1/pleno/importacao/" + SERVICO + "/verificar"))
                .andExpect(status().isForbidden());
    }
}
