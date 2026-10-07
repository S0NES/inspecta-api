package br.com.vistoria.core.modules.publico;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import br.com.vistoria.core.modules.arquivo.ArquivoService;
import br.com.vistoria.core.modules.laudo.LaudoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// PublicoControllerTest (integration, extends AbstractIntegrationTest) already covers the two
// unauthenticated GET endpoints end to end against a real Postgres. What it does NOT cover at all
// is the pair of role-guarded endpoints -- criarLink (POST) and revogar (DELETE) -- which is where
// the actual auth/role boundary for this "public link" feature lives. This slice test fills that
// gap the same way PagamentoControllerTest/RelatorioControllerTest do for their controllers:
// @WebMvcTest with the security filters swapped for mocks, verifying role matrices and 401/403.
@WebMvcTest(PublicoController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("PublicoController - link creation/revocation auth boundaries")
class PublicoControllerWebMvcTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private ApiKeyFilter apiKeyFilter;

    @MockitoBean
    private AcessoPublicoRepository acessos;

    @MockitoBean
    private LaudoService laudoService;

    @MockitoBean
    private ArquivoService arquivoService;

    private static final UUID LAUDO_42 = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final UUID LAUDO_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LAUDO_7 = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final UUID LINK_5 = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID LINK_999 = UUID.fromString("00000000-0000-0000-0000-000000000999");
    private static final UUID LINK_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/laudos/{id}/link-publico creates a link for ADMIN, defaulting to tipo=laudo")
    void criarLink_adminUser_createsLaudoLinkByDefault() throws Exception {
        mvc.perform(post("/v1/laudos/" + LAUDO_42 + "/link-publico"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith("/v1/public/laudos/")));

        ArgumentCaptor<AcessoPublico> captor = ArgumentCaptor.forClass(AcessoPublico.class);
        verify(acessos).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getTipo()).isEqualTo(AcessoPublico.Tipo.laudo);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getRecursoId()).isEqualTo(LAUDO_42);
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("POST /v1/laudos/{id}/link-publico allows SUPORTE")
    void criarLink_suporteUser_allowed() throws Exception {
        mvc.perform(post("/v1/laudos/" + LAUDO_1 + "/link-publico"))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "IMOB_GERENCIA")
    @DisplayName("POST /v1/laudos/{id}/link-publico allows IMOB_GERENCIA")
    void criarLink_imobGerenciaUser_allowed() throws Exception {
        mvc.perform(post("/v1/laudos/" + LAUDO_1 + "/link-publico"))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "VISTORIADOR")
    @DisplayName("POST /v1/laudos/{id}/link-publico allows VISTORIADOR")
    void criarLink_vistoriadorUser_allowed() throws Exception {
        mvc.perform(post("/v1/laudos/" + LAUDO_1 + "/link-publico"))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("POST /v1/laudos/{id}/link-publico denies CLIENTE")
    void criarLink_clienteUser_forbidden() throws Exception {
        mvc.perform(post("/v1/laudos/" + LAUDO_1 + "/link-publico"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(acessos);
    }

    @Test
    @DisplayName("POST /v1/laudos/{id}/link-publico requires authentication")
    void criarLink_unauthenticated_returnsUnauthorized() throws Exception {
        mvc.perform(post("/v1/laudos/" + LAUDO_1 + "/link-publico"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/laudos/{id}/link-publico?tipo=imagens creates an imagens-typed link " +
            "whose URL actually matches the GET /v1/public/imagens/{token} route")
    void criarLink_tipoImagens_createsImagensLink() throws Exception {
        mvc.perform(post("/v1/laudos/" + LAUDO_7 + "/link-publico").param("tipo", "imagens"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith("/v1/public/imagens/")));

        ArgumentCaptor<AcessoPublico> captor = ArgumentCaptor.forClass(AcessoPublico.class);
        verify(acessos).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getTipo()).isEqualTo(AcessoPublico.Tipo.imagens);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("DELETE /v1/links-publicos/{id} revokes an existing link for ADMIN")
    void revogar_adminUser_revokesLink() throws Exception {
        AcessoPublico existing = new AcessoPublico();
        existing.setId(LINK_5);
        existing.setRevogado(false);
        when(acessos.findById(LINK_5)).thenReturn(Optional.of(existing));

        mvc.perform(delete("/v1/links-publicos/" + LINK_5))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(existing.isRevogado()).isTrue();
        verify(acessos).save(existing);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("DELETE /v1/links-publicos/{id} on unknown id is a no-op, still returns 204")
    void revogar_unknownId_noOpButStillNoContent() throws Exception {
        when(acessos.findById(LINK_999)).thenReturn(Optional.empty());

        mvc.perform(delete("/v1/links-publicos/" + LINK_999))
                .andExpect(status().isNoContent());

        verify(acessos, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("DELETE /v1/links-publicos/{id} allows SUPORTE")
    void revogar_suporteUser_allowed() throws Exception {
        when(acessos.findById(LINK_1)).thenReturn(Optional.empty());

        mvc.perform(delete("/v1/links-publicos/" + LINK_1))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("DELETE /v1/links-publicos/{id} denies CLIENTE")
    void revogar_clienteUser_forbidden() throws Exception {
        mvc.perform(delete("/v1/links-publicos/" + LINK_1))
                .andExpect(status().isForbidden());

        verifyNoInteractions(acessos);
    }

    @Test
    @WithMockUser(roles = "VISTORIADOR")
    @DisplayName("DELETE /v1/links-publicos/{id} denies VISTORIADOR (only ADMIN/SUPORTE may revoke)")
    void revogar_vistoriadorUser_forbidden() throws Exception {
        mvc.perform(delete("/v1/links-publicos/" + LINK_1))
                .andExpect(status().isForbidden());

        verifyNoInteractions(acessos);
    }

    @Test
    @DisplayName("DELETE /v1/links-publicos/{id} requires authentication")
    void revogar_unauthenticated_returnsUnauthorized() throws Exception {
        mvc.perform(delete("/v1/links-publicos/" + LINK_1))
                .andExpect(status().isUnauthorized());
    }
}
