package br.com.vistoria.core.modules.integracao;

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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PlenoQueryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("PlenoQueryController - Read Endpoints (T2.5)")
class PlenoQueryControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private PlenoQueryService pleno;

    @MockBean
    private IntegracaoSyncService integracaoSync;

    @MockBean
    private br.com.vistoria.core.modules.cadastro.CadastroRepositories.ImovelRepository imoveis;

    @MockBean
    private br.com.vistoria.core.modules.imobiliaria.ImobiliariaRepository imobiliarias;

    @MockBean
    private br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.SolicitacaoRepository solicitacoes;

    @MockBean
    private br.com.vistoria.core.modules.disponibilidade.DisponibilidadeRepositories.VistoriadorRepository vistoriadores;

    @MockBean
    private br.com.vistoria.core.modules.usuario.UsuarioRepository usuarios;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @MockBean
    private ApiKeyFilter apiKeyFilter;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/pleno/imoveis/{plenoId} allows ADMIN")
    void getPlenoImovel_adminUser_allowed() throws Exception {
        mvc.perform(get("/v1/pleno/imoveis/pleno-123"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("GET /v1/pleno/imoveis/{plenoId} allows SUPORTE")
    void getPlenoImovel_suporteUser_allowed() throws Exception {
        mvc.perform(get("/v1/pleno/imoveis/pleno-456"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("GET /v1/pleno/imoveis/{plenoId} denies CLIENTE (RBAC)")
    void getPlenoImovel_clienteUser_returnsForbidden() throws Exception {
        mvc.perform(get("/v1/pleno/imoveis/pleno-123"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/pleno/solicitacoes/{plenoId} allows ADMIN")
    void getPlenoSolicitacao_adminUser_allowed() throws Exception {
        mvc.perform(get("/v1/pleno/solicitacoes/pleno-789"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("GET /v1/pleno/solicitacoes/{plenoId} allows SUPORTE")
    void getPlenoSolicitacao_suporteUser_allowed() throws Exception {
        mvc.perform(get("/v1/pleno/solicitacoes/pleno-999"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "VISTORIADOR")
    @DisplayName("GET /v1/pleno/solicitacoes/{plenoId} denies VISTORIADOR (RBAC)")
    void getPlenoSolicitacao_vistoriadorUser_returnsForbidden() throws Exception {
        mvc.perform(get("/v1/pleno/solicitacoes/pleno-123"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("unauthenticated request to /v1/pleno/imoveis returns 401")
    void getPlenoImovel_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/v1/pleno/imoveis/pleno-123"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/pleno/sync lista para ADMIN (painel P2)")
    void syncList_adminUser_ok() throws Exception {
        when(integracaoSync.listar(nullable(String.class), nullable(String.class), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        mvc.perform(get("/v1/pleno/sync?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @WithMockUser(roles = "IMOB_GERENCIA")
    @DisplayName("GET /v1/pleno/sync nega IMOB_GERENCIA")
    void syncList_imobUser_forbidden() throws Exception {
        mvc.perform(get("/v1/pleno/sync"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("POST /v1/pleno/sync/{id}/retry aceito para SUPORTE")
    void syncRetry_suporteUser_accepted() throws Exception {
        java.util.UUID syncId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000003");
        java.util.UUID recursoId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000009");
        var row = new br.com.vistoria.core.modules.integracao.IntegracaoSync();
        row.setId(syncId);
        row.setRecursoTipo("imovel");
        row.setRecursoId(recursoId);
        row.setStatus("pendente");
        row.setOrigem("espelho");
        when(integracaoSync.retentar(eq(syncId))).thenReturn(row);
        mvc.perform(post("/v1/pleno/sync/" + syncId + "/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(syncId.toString()));
    }
}
