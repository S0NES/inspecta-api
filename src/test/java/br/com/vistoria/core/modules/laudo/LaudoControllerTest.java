package br.com.vistoria.core.modules.laudo;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import br.com.vistoria.core.modules.arquivo.ArquivoService;
import br.com.vistoria.core.modules.disponibilidade.DisponibilidadeRepositories.VistoriadorRepository;
import br.com.vistoria.core.modules.disponibilidade.Vistoriador;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.ServicoRepository;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoServico;
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
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LaudoController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("LaudoController - GET /v1/laudos list endpoint (T12)")
class LaudoControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private LaudoService service;

    @MockBean
    private LaudoRepository laudos;

    @MockBean
    private ServicoRepository servicos;

    @MockBean
    private VistoriadorRepository vistoriadores;

    @MockBean
    private ArquivoService arquivoService;

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
    @DisplayName("GET /v1/laudos returns list for ADMIN (RLS applies at DB level)")
    void listLaudos_admin_returnsList() throws Exception {
        var laudo = new Laudo();
        laudo.setId(id(1));
        laudo.setSolicitacaoServicoId(id(100));
        laudo.setVersao(1);
        laudo.setStatus(Laudo.Status.rascunho);
        laudo.setConteudoJson("{\"ambientes\":[]}");
        laudo.setCriadoEm(Instant.now());

        when(laudos.findAll()).thenReturn(List.of(laudo));

        mvc.perform(get("/v1/laudos")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].id").value(id(1).toString()));
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("GET /v1/laudos returns list for SUPORTE (RLS applies at DB level)")
    void listLaudos_suporte_returnsList() throws Exception {
        when(laudos.findAll()).thenReturn(List.of());

        mvc.perform(get("/v1/laudos")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @WithMockUser(roles = "IMOB_GERENCIA")
    @DisplayName("GET /v1/laudos returns list for IMOB_GERENCIA (RLS applies at DB level)")
    void listLaudos_imobGerencia_returnsList() throws Exception {
        when(laudos.findAll()).thenReturn(List.of());

        mvc.perform(get("/v1/laudos")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @WithMockUser(username = USER_100_STR, roles = "VISTORIADOR")
    @DisplayName("GET /v1/laudos returns list filtered for VISTORIADOR (row-scoping by assigned services)")
    void listLaudos_vistoriador_returnsOwnLaudos() throws Exception {
        var laudo = new Laudo();
        laudo.setId(id(1));
        laudo.setSolicitacaoServicoId(id(100));
        laudo.setVersao(1);
        laudo.setStatus(Laudo.Status.rascunho);
        laudo.setConteudoJson("{\"ambientes\":[]}");
        laudo.setCriadoEm(Instant.now());

        var servico = new SolicitacaoServico();
        servico.setId(id(100));
        servico.setVistoriadorId(id(50));

        var vistoriador = new Vistoriador();
        vistoriador.setId(id(50));
        vistoriador.setUsuarioId(id(100));

        when(laudos.findAll()).thenReturn(List.of(laudo));
        when(vistoriadores.findByUsuarioId(id(100))).thenReturn(Optional.of(vistoriador));
        when(servicos.findById(id(100))).thenReturn(Optional.of(servico));

        mvc.perform(get("/v1/laudos")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].id").value(id(1).toString()));
    }

    @Test
    @WithMockUser(username = USER_100_STR, roles = "VISTORIADOR")
    @DisplayName("GET /v1/laudos filters out laudos not assigned to VISTORIADOR (row-scoping)")
    void listLaudos_vistoriador_filteredByAssignment() throws Exception {
        var laudo = new Laudo();
        laudo.setId(id(1));
        laudo.setSolicitacaoServicoId(id(100));
        laudo.setVersao(1);
        laudo.setStatus(Laudo.Status.rascunho);
        laudo.setConteudoJson("{\"ambientes\":[]}");
        laudo.setCriadoEm(Instant.now());

        var servico = new SolicitacaoServico();
        servico.setId(id(100));
        servico.setVistoriadorId(id(999)); // different vistoriador

        var vistoriador = new Vistoriador();
        vistoriador.setId(id(50));
        vistoriador.setUsuarioId(id(100));

        when(laudos.findAll()).thenReturn(List.of(laudo));
        when(vistoriadores.findByUsuarioId(id(100))).thenReturn(Optional.of(vistoriador));
        when(servicos.findById(id(100))).thenReturn(Optional.of(servico));

        mvc.perform(get("/v1/laudos")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/laudos with status filter returns filtered list")
    void listLaudos_withStatusFilter_returnsFiltered() throws Exception {
        var laudo1 = new Laudo();
        laudo1.setId(id(1));
        laudo1.setSolicitacaoServicoId(id(100));
        laudo1.setVersao(1);
        laudo1.setStatus(Laudo.Status.rascunho);
        laudo1.setConteudoJson("{\"ambientes\":[]}");
        laudo1.setCriadoEm(Instant.now());

        var laudo2 = new Laudo();
        laudo2.setId(id(2));
        laudo2.setSolicitacaoServicoId(id(101));
        laudo2.setVersao(1);
        laudo2.setStatus(Laudo.Status.assinado);
        laudo2.setConteudoJson("{\"ambientes\":[]}");
        laudo2.setCriadoEm(Instant.now());

        when(laudos.findAll()).thenReturn(List.of(laudo1, laudo2));

        mvc.perform(get("/v1/laudos")
                .param("status", "rascunho")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id(1).toString()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/laudos?origem=importado_pleno filtra por origem e expõe o campo")
    void listLaudos_withOrigemFilter_returnsFiltered() throws Exception {
        var interno = new Laudo();
        interno.setId(id(1));
        interno.setSolicitacaoServicoId(id(100));
        interno.setVersao(1);
        interno.setStatus(Laudo.Status.rascunho);
        interno.setOrigem(Laudo.Origem.gerado_internamente);
        interno.setConteudoJson("{}");
        interno.setCriadoEm(Instant.now());

        var importado = new Laudo();
        importado.setId(id(2));
        importado.setSolicitacaoServicoId(id(101));
        importado.setVersao(1);
        importado.setStatus(Laudo.Status.assinado);
        importado.setOrigem(Laudo.Origem.importado_pleno);
        importado.setConteudoJson("{}");
        importado.setCriadoEm(Instant.now());

        when(laudos.findAll()).thenReturn(List.of(interno, importado));

        mvc.perform(get("/v1/laudos")
                .param("origem", "importado_pleno")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id(2).toString()))
                .andExpect(jsonPath("$.content[0].origem").value("importado_pleno"));
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("GET /v1/laudos denies CLIENTE role")
    void listLaudos_cliente_returnsForbidden() throws Exception {
        mvc.perform(get("/v1/laudos")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /v1/laudos requires authentication")
    void listLaudos_unauthenticated_returnsForbidden() throws Exception {
        mvc.perform(get("/v1/laudos")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isUnauthorized());
    }
}
