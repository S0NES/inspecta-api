package br.com.vistoria.core.modules.financeiro;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import br.com.vistoria.core.modules.disponibilidade.VistoriadorService;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(VistoriadorFinanceiroController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({TestMethodSecurityConfig.class, br.com.vistoria.core.shared.PrecoVisibilidadePolicy.class})
@DisplayName("VistoriadorFinanceiroController - Surveyor payments")
class VistoriadorFinanceiroControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private ApiKeyFilter apiKeyFilter;

    @MockitoBean
    private PagamentoVistoriadorService service;

    @MockitoBean
    private VistoriadorService vistoriadorService;

    @Autowired
    private ObjectMapper mapper;

    private static final String VISTORIADOR_1_STR = "00000000-0000-0000-0000-000000000001";
    private static final UUID VISTORIADOR_1 = UUID.fromString(VISTORIADOR_1_STR);
    private static final UUID VISTORIADOR_999 = UUID.fromString("00000000-0000-0000-0000-000000000999");

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/vistoriadores/{id}/extrato returns statement for ADMIN")
    void extrato_adminUser_returnsStatement() throws Exception {
        var extrato = new PagamentoVistoriadorService.Extrato(
                "2025-01", List.of(), BigDecimal.valueOf(500)
        );

        when(service.extrato(VISTORIADOR_1, "2025-01")).thenReturn(extrato);

        // PagamentoVistoriadorService.Extrato (inspecta-core) nao carrega o id do vistoriador,
        // so periodo/servicos/total; o id ja e o path param que o chamador forneceu.
        mvc.perform(get("/v1/vistoriadores/" + VISTORIADOR_1 + "/extrato?periodo=2025-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodo").value("2025-01"))
                .andExpect(jsonPath("$.total").value(500));
    }

    @Test
    @WithMockUser(username = VISTORIADOR_1_STR, roles = "VISTORIADOR")
    @DisplayName("GET /v1/vistoriadores/{id}/extrato allows VISTORIADOR to see own statement")
    void extrato_vistoriadorUser_seesOwnStatement() throws Exception {
        var extrato = new PagamentoVistoriadorService.Extrato(
                "2025-01", List.of(), BigDecimal.valueOf(200)
        );

        when(vistoriadorService.idPorUsuario(VISTORIADOR_1)).thenReturn(Optional.of(VISTORIADOR_1));
        when(service.extrato(VISTORIADOR_1, "2025-01")).thenReturn(extrato);

        mvc.perform(get("/v1/vistoriadores/" + VISTORIADOR_1 + "/extrato?periodo=2025-01"))
                .andExpect(status().isOk());
    }

    private static br.com.vistoria.core.modules.solicitacao.SolicitacaoServico servicoConcluido() {
        var ss = new br.com.vistoria.core.modules.solicitacao.SolicitacaoServico();
        ss.setId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"));
        ss.setStatus(br.com.vistoria.core.modules.solicitacao.SolicitacaoServico.Status.concluida);
        ss.setPrecoSnapshot(new BigDecimal("200.00"));
        ss.setRepasseSnapshot(new BigDecimal("80.00"));
        return ss;
    }

    @Test
    @WithMockUser(username = VISTORIADOR_1_STR, roles = "VISTORIADOR")
    @DisplayName("extrato do VISTORIADOR não expõe o preço cobrado do cliente, só o repasse")
    void extrato_vistoriadorNaoVePrecoCobrado() throws Exception {
        when(vistoriadorService.idPorUsuario(VISTORIADOR_1)).thenReturn(Optional.of(VISTORIADOR_1));
        when(service.extrato(VISTORIADOR_1, "2025-01")).thenReturn(new PagamentoVistoriadorService.Extrato(
                "2025-01", List.of(servicoConcluido()), new BigDecimal("80.00")));

        mvc.perform(get("/v1/vistoriadores/" + VISTORIADOR_1 + "/extrato?periodo=2025-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicos[0].precoSnapshot").doesNotExist())
                .andExpect(jsonPath("$.servicos[0].repasseSnapshot").value(80.00));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("extrato do ADMIN continua trazendo o preço cobrado")
    void extrato_adminVePrecoCobrado() throws Exception {
        when(service.extrato(VISTORIADOR_1, "2025-01")).thenReturn(new PagamentoVistoriadorService.Extrato(
                "2025-01", List.of(servicoConcluido()), new BigDecimal("80.00")));

        mvc.perform(get("/v1/vistoriadores/" + VISTORIADOR_1 + "/extrato?periodo=2025-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicos[0].precoSnapshot").value(200.00));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/pagamentos-vistoriador rejeita valor negativo (Bean Validation aplicada)")
    void registrar_valorNegativoRejeitado() throws Exception {
        mvc.perform(post("/v1/pagamentos-vistoriador")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vistoriadorId\":\"" + VISTORIADOR_1 + "\",\"periodo\":\"2025-01\",\"valor\":-5}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser(username = VISTORIADOR_1_STR, roles = "VISTORIADOR")
    @DisplayName("GET /v1/vistoriadores/{id}/extrato denies VISTORIADOR seeing others' statement")
    void extrato_vistoriadorUser_deniesOtherStatement() throws Exception {
        when(vistoriadorService.idPorUsuario(VISTORIADOR_1)).thenReturn(Optional.of(VISTORIADOR_1));

        mvc.perform(get("/v1/vistoriadores/" + VISTORIADOR_999 + "/extrato?periodo=2025-01"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/pagamentos-vistoriador registers payment for ADMIN")
    void registrar_adminUser_registersPayment() throws Exception {
        var pagamento = new PagamentoVistoriador();
        pagamento.setId(VISTORIADOR_1);
        pagamento.setVistoriadorId(VISTORIADOR_1);
        pagamento.setPeriodo("2025-01");
        pagamento.setValor(BigDecimal.valueOf(500));

        when(service.registrar(VISTORIADOR_1, "2025-01", BigDecimal.valueOf(500), null))
                .thenReturn(pagamento);

        var body = Map.of(
                "vistoriadorId", VISTORIADOR_1,
                "periodo", "2025-01",
                "valor", BigDecimal.valueOf(500)
        );

        mvc.perform(post("/v1/pagamentos-vistoriador")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.valor").value(500));
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("POST /v1/pagamentos-vistoriador denies SUPORTE")
    void registrar_suporteUser_returnsForbidden() throws Exception {
        var body = Map.of(
                "vistoriadorId", VISTORIADOR_1,
                "periodo", "2025-01",
                "valor", BigDecimal.valueOf(500)
        );

        mvc.perform(post("/v1/pagamentos-vistoriador")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /v1/vistoriadores/{id}/extrato requires authentication")
    void extrato_unauthenticated_returnsUnauthorized() throws Exception {
        mvc.perform(get("/v1/vistoriadores/" + VISTORIADOR_1 + "/extrato?periodo=2025-01"))
                .andExpect(status().isUnauthorized());
    }
}
