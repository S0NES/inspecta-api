package br.com.vistoria.core.modules.financeiro;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ExtratoController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("ExtratoController - Extrato consolidado B2B")
class ExtratoControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private ApiKeyFilter apiKeyFilter;

    @MockitoBean
    private ExtratoService extrato;

    private static final UUID IMOB = UUID.fromString("00000000-0000-0000-0000-000000000009");

    @Test
    @WithMockUser(roles = "IMOB_OPERACAO")
    @DisplayName("GET .../extrato/itens lista itens em aberto para IMOB_OPERACAO")
    void itens_imobOperacao_ok() throws Exception {
        var item = new ExtratoItemAberto(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                new BigDecimal("250.00"), Instant.now(), "Av. Paulista", "1000", "Vistoria de entrada");
        when(extrato.listarItensAbertos(eq(IMOB), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(item));

        mvc.perform(get("/v1/imobiliarias/" + IMOB + "/extrato/itens")
                .param("inicio", "2026-09-01")
                .param("fim", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].valor").value(250.00))
                .andExpect(jsonPath("$[0].enderecoImovel").value("Av. Paulista"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET .../extrato/itens permite ADMIN")
    void itens_admin_ok() throws Exception {
        when(extrato.listarItensAbertos(any(), any(), any())).thenReturn(List.of());
        mvc.perform(get("/v1/imobiliarias/" + IMOB + "/extrato/itens")
                .param("inicio", "2026-09-01").param("fim", "2026-09-30"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "VISTORIADOR")
    @DisplayName("GET .../extrato/itens nega VISTORIADOR")
    void itens_vistoriador_forbidden() throws Exception {
        mvc.perform(get("/v1/imobiliarias/" + IMOB + "/extrato/itens")
                .param("inicio", "2026-09-01").param("fim", "2026-09-30"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET .../extrato/itens exige autenticação")
    void itens_unauthenticated() throws Exception {
        mvc.perform(get("/v1/imobiliarias/" + IMOB + "/extrato/itens")
                .param("inicio", "2026-09-01").param("fim", "2026-09-30"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "IMOB_GERENCIA")
    @DisplayName("POST .../extrato/faturas gera fatura consolidada e devolve 202")
    void gerarFatura_202() throws Exception {
        var fatura = new FaturaConsolidada();
        fatura.setId(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));
        fatura.setImobiliariaId(IMOB);
        fatura.setValorTotal(new BigDecimal("500.00"));
        when(extrato.gerarFaturaConsolidada(eq(IMOB), any(LocalDate.class), any(LocalDate.class), any()))
                .thenReturn(fatura);

        mvc.perform(post("/v1/imobiliarias/" + IMOB + "/extrato/faturas")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"inicio\":\"2026-09-01\",\"fim\":\"2026-09-30\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.faturaId").value("00000000-0000-0000-0000-0000000000aa"))
                .andExpect(jsonPath("$.valorTotal").value(500.00))
                .andExpect(jsonPath("$.checkoutUrl").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "VISTORIADOR")
    @DisplayName("POST .../extrato/faturas nega VISTORIADOR")
    void gerarFatura_vistoriador_forbidden() throws Exception {
        mvc.perform(post("/v1/imobiliarias/" + IMOB + "/extrato/faturas")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"inicio\":\"2026-09-01\",\"fim\":\"2026-09-30\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("GET .../extrato/faturas devolve o histórico")
    void faturas_historico_ok() throws Exception {
        var fatura = new FaturaConsolidada();
        fatura.setId(UUID.fromString("00000000-0000-0000-0000-0000000000bb"));
        fatura.setImobiliariaId(IMOB);
        fatura.setValorTotal(new BigDecimal("120.00"));
        fatura.setStatus("pendente");
        when(extrato.listarFaturas(IMOB)).thenReturn(List.of(fatura));

        mvc.perform(get("/v1/imobiliarias/" + IMOB + "/extrato/faturas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("00000000-0000-0000-0000-0000000000bb"))
                .andExpect(jsonPath("$[0].status").value("pendente"));
    }
}
