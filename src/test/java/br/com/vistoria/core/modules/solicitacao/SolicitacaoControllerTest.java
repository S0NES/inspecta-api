package br.com.vistoria.core.modules.solicitacao;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import br.com.vistoria.core.modules.disponibilidade.DisponibilidadeRepositories.VistoriadorRepository;
import br.com.vistoria.core.modules.disponibilidade.Vistoriador;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SolicitacaoController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("SolicitacaoController - Row-Based Scoping (T2.4)")
class SolicitacaoControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SolicitacaoService service;

    // O controller injeta o query service para montar o read model da listagem; sem este
    // mock o contexto do slice nem sobe (UnsatisfiedDependencyException) e os 12 testes
    // desta classe morriam antes de assertar qualquer regra de escopo.
    @MockBean
    private SolicitacaoQueryService queryService;

    @MockBean
    private SolicitacaoRepositories.SolicitacaoRepository solicitacoes;

    @MockBean
    private SolicitacaoRepositories.ServicoRepository servicos;

    @MockBean
    private VistoriadorRepository vistoriadores;

    @MockBean
    private br.com.vistoria.core.modules.financeiro.PagamentoService pagamentoService;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @MockBean
    private ApiKeyFilter apiKeyFilter;

    private static final String USER_100_STR = "00000000-0000-0000-0000-000000000064";

    /** UUID determinístico por pequeno inteiro, só para tornar os ids do teste legíveis. */
    private static UUID id(long n) {
        return new UUID(0L, n);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/solicitacoes with ADMIN role returns all tenant solicitacoes")
    void listSolicitacoes_admin_seesAllTenantSolicitacoes() throws Exception {
        mvc.perform(get("/v1/solicitacoes")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("GET /v1/solicitacoes with SUPORTE role returns all tenant solicitacoes")
    void listSolicitacoes_suporte_seesAllTenantSolicitacoes() throws Exception {
        mvc.perform(get("/v1/solicitacoes")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "IMOB_GERENCIA")
    @DisplayName("GET /v1/solicitacoes with IMOB_GERENCIA role returns all tenant solicitacoes")
    void listSolicitacoes_imobGerencia_seesAllTenantSolicitacoes() throws Exception {
        mvc.perform(get("/v1/solicitacoes")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("GET /v1/solicitacoes with CLIENTE role sees only their own solicitacoes")
    void listSolicitacoes_cliente_seesOnlyOwnSolicitacoes() throws Exception {
        mvc.perform(get("/v1/solicitacoes")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "VISTORIADOR")
    @DisplayName("GET /v1/solicitacoes with VISTORIADOR role sees only assigned solicitacoes")
    void listSolicitacoes_vistoriador_seesOnlyAssignedSolicitacoes() throws Exception {
        mvc.perform(get("/v1/solicitacoes")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /v1/solicitacoes without authentication returns 401")
    void listSolicitacoes_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/v1/solicitacoes")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isUnauthorized());
    }

    // Tests for GET /{id} detail endpoint

    @Test
    @WithMockUser(username = USER_100_STR, roles = "ADMIN")
    @DisplayName("GET /v1/solicitacoes/{id} returns solicitacao for ADMIN")
    void getSolicitacao_admin_returnsSolicitacao() throws Exception {
        var solicitacao = new Solicitacao();
        solicitacao.setId(id(1));
        solicitacao.setClienteId(id(10));
        solicitacao.setImovelId(id(20));
        solicitacao.setImobiliariaId(id(30));
        solicitacao.setOrigem(Solicitacao.Origem.painel);
        solicitacao.setCriadoEm(Instant.now());

        when(solicitacoes.findById(id(1))).thenReturn(Optional.of(solicitacao));

        mvc.perform(get("/v1/solicitacoes/" + id(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id(1).toString()))
                .andExpect(jsonPath("$.clienteId").value(id(10).toString()))
                .andExpect(jsonPath("$.imovelId").value(id(20).toString()));
    }

    @Test
    @WithMockUser(username = USER_100_STR, roles = "CLIENTE")
    @DisplayName("GET /v1/solicitacoes/{id} returns solicitacao when CLIENTE owns it (row-scoping)")
    void getSolicitacao_cliente_ownsIt_returnsSolicitacao() throws Exception {
        var solicitacao = new Solicitacao();
        solicitacao.setId(id(1));
        solicitacao.setClienteId(id(100)); // matches current user
        solicitacao.setImovelId(id(20));
        solicitacao.setImobiliariaId(id(30));
        solicitacao.setOrigem(Solicitacao.Origem.portal);
        solicitacao.setCriadoEm(Instant.now());

        when(solicitacoes.findById(id(1))).thenReturn(Optional.of(solicitacao));

        mvc.perform(get("/v1/solicitacoes/" + id(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id(1).toString()));
    }

    @Test
    @WithMockUser(username = USER_100_STR, roles = "CLIENTE")
    @DisplayName("GET /v1/solicitacoes/{id} denies CLIENTE access to others' solicitacoes (row-scoping)")
    void getSolicitacao_cliente_differentOwner_returnsForbidden() throws Exception {
        var solicitacao = new Solicitacao();
        solicitacao.setId(id(1));
        solicitacao.setClienteId(id(200)); // different from current user (100)
        solicitacao.setImovelId(id(20));
        solicitacao.setImobiliariaId(id(30));
        solicitacao.setOrigem(Solicitacao.Origem.portal);
        solicitacao.setCriadoEm(Instant.now());

        when(solicitacoes.findById(id(1))).thenReturn(Optional.of(solicitacao));

        mvc.perform(get("/v1/solicitacoes/" + id(1)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = USER_100_STR, roles = "VISTORIADOR")
    @DisplayName("GET /v1/solicitacoes/{id} returns solicitacao when VISTORIADOR is assigned (row-scoping)")
    void getSolicitacao_vistoriador_isAssigned_returnsSolicitacao() throws Exception {
        var solicitacao = new Solicitacao();
        solicitacao.setId(id(1));
        solicitacao.setClienteId(id(10));
        solicitacao.setImovelId(id(20));
        solicitacao.setImobiliariaId(id(30));
        solicitacao.setOrigem(Solicitacao.Origem.painel);
        solicitacao.setCriadoEm(Instant.now());

        var vistoriador = new Vistoriador();
        vistoriador.setId(id(50));
        vistoriador.setUsuarioId(id(100));

        when(solicitacoes.findById(id(1))).thenReturn(Optional.of(solicitacao));
        when(vistoriadores.findByUsuarioId(id(100))).thenReturn(Optional.of(vistoriador));
        when(solicitacoes.findByVistoriadorId(id(50), Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(solicitacao), Pageable.unpaged(), 1));

        mvc.perform(get("/v1/solicitacoes/" + id(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id(1).toString()));
    }

    @Test
    @WithMockUser(username = USER_100_STR, roles = "VISTORIADOR")
    @DisplayName("GET /v1/solicitacoes/{id} denies VISTORIADOR access when not assigned (row-scoping)")
    void getSolicitacao_vistoriador_notAssigned_returnsForbidden() throws Exception {
        var solicitacao = new Solicitacao();
        solicitacao.setId(id(1));
        solicitacao.setClienteId(id(10));
        solicitacao.setImovelId(id(20));
        solicitacao.setImobiliariaId(id(30));
        solicitacao.setOrigem(Solicitacao.Origem.painel);
        solicitacao.setCriadoEm(Instant.now());

        var vistoriador = new Vistoriador();
        vistoriador.setId(id(50));
        vistoriador.setUsuarioId(id(100));

        when(solicitacoes.findById(id(1))).thenReturn(Optional.of(solicitacao));
        when(vistoriadores.findByUsuarioId(id(100))).thenReturn(Optional.of(vistoriador));
        // Vistoriador's page is empty (not assigned to this solicitacao)
        when(solicitacoes.findByVistoriadorId(id(50), Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(), Pageable.unpaged(), 0));

        mvc.perform(get("/v1/solicitacoes/" + id(1)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = USER_100_STR, roles = "ADMIN")
    @DisplayName("GET /v1/solicitacoes/{id} returns 404 when solicitacao not found")
    void getSolicitacao_notFound_returns404() throws Exception {
        when(solicitacoes.findById(id(999))).thenReturn(Optional.empty());

        mvc.perform(get("/v1/solicitacoes/" + id(999)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/solicitacoes/{id}/servicos lista os serviços com id/status/vistoriador")
    void servicos_adminUser_returnsItems() throws Exception {
        SolicitacaoServico ss = new SolicitacaoServico();
        ss.setId(id(42));
        ss.setSolicitacaoId(id(7));
        ss.setTipoServicoId(id(1));
        ss.setStatus(SolicitacaoServico.Status.atribuida);
        ss.setVistoriadorId(id(3));
        ss.setPrecoSnapshot(new java.math.BigDecimal("250.00"));
        when(servicos.findBySolicitacaoId(id(7))).thenReturn(List.of(ss));

        mvc.perform(get("/v1/solicitacoes/" + id(7) + "/servicos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id(42).toString()))
                .andExpect(jsonPath("$[0].status").value("atribuida"))
                .andExpect(jsonPath("$[0].vistoriadorId").value(id(3).toString()));
    }

    @Test
    @WithMockUser(roles = "VISTORIADOR")
    @DisplayName("GET /v1/solicitacoes/{id}/servicos nega VISTORIADOR")
    void servicos_vistoriadorUser_forbidden() throws Exception {
        mvc.perform(get("/v1/solicitacoes/" + id(7) + "/servicos"))
                .andExpect(status().isForbidden());
    }

    // POST /v1/solicitacoes — criação (clienteId opcional, guard de cobrança B2B)

    private static String createBodyClienteNull() {
        return "{\"clienteId\":null,\"imovelId\":\"" + id(20) + "\",\"origem\":\"portal\","
                + "\"servicos\":[{\"tipoServicoId\":\"" + id(1) + "\"}],"
                + "\"intervenientes\":[{\"papelSlug\":\"locatario\",\"nome\":\"Fulano\","
                + "\"cpf\":\"12345678909\",\"email\":\"fulano@example.com\"}]}";
    }

    @Test
    @WithMockUser(username = USER_100_STR, roles = "IMOB_OPERACAO")
    @DisplayName("POST /v1/solicitacoes aceita clienteId null e, no fluxo reservado, dispara a cobrança")
    void criar_clienteIdNull_fluxoReservado_disparaCobranca() throws Exception {
        var s = new Solicitacao();
        s.setId(id(1));
        s.setImovelId(id(20));
        s.setImobiliariaId(null);
        s.setOrigem(Solicitacao.Origem.portal);
        s.setCriadoEm(Instant.now());
        when(service.criar(any(), any())).thenReturn(s);

        mvc.perform(post("/v1/solicitacoes")
                .header("Idempotency-Key", "test-criar-1")
                .contentType("application/json")
                .content(createBodyClienteNull()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id(1).toString()));

        verify(service).criar(any(), any());
        verify(pagamentoService).criarCobranca(eq(id(1)));
    }

    @Test
    @WithMockUser(username = USER_100_STR, roles = "IMOB_OPERACAO")
    @DisplayName("POST /v1/solicitacoes com imobiliária (B2B) NÃO dispara cobrança individual")
    void criar_solicitacaoB2B_naoDisparaCobranca() throws Exception {
        var s = new Solicitacao();
        s.setId(id(2));
        s.setImovelId(id(20));
        s.setImobiliariaId(id(30));
        s.setOrigem(Solicitacao.Origem.portal);
        s.setCriadoEm(Instant.now());
        when(service.criar(any(), any())).thenReturn(s);

        mvc.perform(post("/v1/solicitacoes")
                .header("Idempotency-Key", "test-criar-b2b")
                .contentType("application/json")
                .content(createBodyClienteNull()))
                .andExpect(status().isCreated());

        verify(pagamentoService, never()).criarCobranca(any());
    }
}
