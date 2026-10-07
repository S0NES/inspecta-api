package br.com.vistoria.core.modules.financeiro;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import br.com.vistoria.core.modules.solicitacao.CheckoutDisponibilidadeGuard;
import br.com.vistoria.core.modules.solicitacao.SemVistoriadorException;
import br.com.vistoria.core.modules.solicitacao.Solicitacao;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PagamentoController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("PagamentoController - Payment orchestration")
class PagamentoControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private ApiKeyFilter apiKeyFilter;

    @MockitoBean
    private PagamentoService service;

    @MockitoBean
    private CheckoutDisponibilidadeGuard disponibilidadeGuard;

    @MockitoBean
    private SolicitacaoService solicitacaoService;

    private static final UUID SOLICITACAO_100 = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID SOLICITACAO_50 = UUID.fromString("00000000-0000-0000-0000-000000000050");
    private static final UUID SOLICITACAO_75 = UUID.fromString("00000000-0000-0000-0000-000000000075");
    private static final UUID SOLICITACAO_42 = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final UUID TIPO_SERVICO_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TIPO_SERVICO_7 = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final UUID IMOBILIARIA_9 = UUID.fromString("00000000-0000-0000-0000-000000000009");

    private static Solicitacao solSemImobiliaria() {
        var s = new Solicitacao();
        s.setImobiliariaId(null);
        return s;
    }

    @BeforeEach
    void stubSolicitacao() {
        // Por padrão a solicitação é do fluxo reservado (sem imobiliária) — cobrança individual permitida.
        lenient().when(solicitacaoService.get(any(UUID.class))).thenReturn(solSemImobiliaria());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("POST /v1/solicitacoes/{id}/pagamento/pix (CLIENTE) solicita PIX transparente e responde 202 com os campos do PIX")
    void criarPix_cliente_accepted() throws Exception {
        var expira = java.time.Instant.parse("2026-10-02T12:00:00Z");
        var cobranca = new PagamentoService.Cobranca(SOLICITACAO_75, "pendente", null, null, false,
                "000201BR", "data:image/png;base64,AAA", expira);
        when(service.solicitarPix(SOLICITACAO_75)).thenReturn(cobranca);

        mvc.perform(post("/v1/solicitacoes/" + SOLICITACAO_75 + "/pagamento/pix"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.pixBrCode").value("000201BR"))
                .andExpect(jsonPath("$.pixQrCodeBase64").value("data:image/png;base64,AAA"))
                .andExpect(jsonPath("$.pixExpiraEm").exists());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("POST .../pagamento/pix de solicitação B2B (com imobiliária) responde 410: pós-paga via fatura")
    void criarPix_b2b_gone() throws Exception {
        var b2b = new Solicitacao();
        b2b.setImobiliariaId(IMOBILIARIA_9);
        when(solicitacaoService.get(SOLICITACAO_75)).thenReturn(b2b);

        mvc.perform(post("/v1/solicitacoes/" + SOLICITACAO_75 + "/pagamento/pix"))
                .andExpect(status().isGone());
    }

    @Test
    @WithMockUser(roles = "VISTORIADOR")
    @DisplayName("POST .../pagamento/pix é proibido para VISTORIADOR")
    void criarPix_vistoriador_forbidden() throws Exception {
        mvc.perform(post("/v1/solicitacoes/" + SOLICITACAO_75 + "/pagamento/pix"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/solicitacoes/{id}/pagamento creates charge for ADMIN")
    void criarCobranca_adminUser_createsCharge() throws Exception {
        var cobranca = new PagamentoService.Cobranca(
                SOLICITACAO_100, "PENDING", null
        );

        when(service.criarCobranca(SOLICITACAO_100)).thenReturn(cobranca);

        mvc.perform(post("/v1/solicitacoes/" + SOLICITACAO_100 + "/pagamento"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.pagamentoId").value(SOLICITACAO_100.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("POST /v1/solicitacoes/{id}/pagamento allows SUPORTE")
    void criarCobranca_suporteUser_createsCharge() throws Exception {
        var cobranca = new PagamentoService.Cobranca(
                SOLICITACAO_50, "PENDING", null
        );

        when(service.criarCobranca(SOLICITACAO_50)).thenReturn(cobranca);

        mvc.perform(post("/v1/solicitacoes/" + SOLICITACAO_50 + "/pagamento"))
                .andExpect(status().isAccepted());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("POST /v1/solicitacoes/{id}/pagamento allows CLIENTE")
    void criarCobranca_clienteUser_createsCharge() throws Exception {
        var cobranca = new PagamentoService.Cobranca(
                SOLICITACAO_75, "PENDING", null
        );

        when(service.criarCobranca(SOLICITACAO_75)).thenReturn(cobranca);

        mvc.perform(post("/v1/solicitacoes/" + SOLICITACAO_75 + "/pagamento"))
                .andExpect(status().isAccepted());
    }

    @Test
    @WithMockUser(roles = "VISTORIADOR")
    @DisplayName("POST /v1/solicitacoes/{id}/pagamento denies VISTORIADOR")
    void criarCobranca_vistoriadorUser_returnsForbidden() throws Exception {
        mvc.perform(post("/v1/solicitacoes/" + SOLICITACAO_100 + "/pagamento"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("POST /v1/solicitacoes/{id}/pagamento returns 409 SEM_VISTORIADOR when no eligible vistoriador")
    void criarCobranca_semVistoriador_returns409() throws Exception {
        doThrow(new SemVistoriadorException(java.util.List.of(TIPO_SERVICO_2, TIPO_SERVICO_7)))
                .when(disponibilidadeGuard).exigirVistoriadorDisponivel(SOLICITACAO_42);

        mvc.perform(post("/v1/solicitacoes/" + SOLICITACAO_42 + "/pagamento"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("SEM_VISTORIADOR"))
                .andExpect(jsonPath("$.servicos[0]").value(TIPO_SERVICO_2.toString()))
                .andExpect(jsonPath("$.servicos[1]").value(TIPO_SERVICO_7.toString()));
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("POST /v1/solicitacoes/{id}/pagamento returns 410 SOLICITACAO_POS_PAGA for B2B solicitacao")
    void criarCobranca_solicitacaoB2B_returns410() throws Exception {
        var b2b = new Solicitacao();
        b2b.setImobiliariaId(IMOBILIARIA_9);
        when(solicitacaoService.get(SOLICITACAO_42)).thenReturn(b2b);

        mvc.perform(post("/v1/solicitacoes/" + SOLICITACAO_42 + "/pagamento"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.codigo").value("SOLICITACAO_POS_PAGA"));

        // O guard de disponibilidade NÃO roda nesse caso.
        org.mockito.Mockito.verifyNoInteractions(disponibilidadeGuard);
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).criarCobranca(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/solicitacoes/{id}/pagamento retrieves charge status")
    void consultar_adminUser_returnsStatus() throws Exception {
        var cobranca = new PagamentoService.Cobranca(
                SOLICITACAO_100, "PAID", null
        );

        when(service.consultar(SOLICITACAO_100)).thenReturn(cobranca);

        mvc.perform(get("/v1/solicitacoes/" + SOLICITACAO_100 + "/pagamento"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }
}
