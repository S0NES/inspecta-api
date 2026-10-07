package br.com.vistoria.core.modules.usuario;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.AuthService;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UsuarioController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("UsuarioController - User Invitations and RBAC (T2.3)")
class UsuarioControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private UsuarioRepository usuarios;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @MockBean
    private ApiKeyFilter apiKeyFilter;

    @Autowired
    private ObjectMapper mapper;

    /** UUID determinístico por pequeno inteiro, só para tornar os ids do teste legíveis. */
    private static UUID id(long n) {
        return new UUID(0L, n);
    }

    private void stubListUsuarios(PageImpl<Usuario> pageContent) {
        when(authService.listUsuarios(
                any(Pageable.class),
                nullable(Usuario.Perfil.class),
                nullable(Usuario.Status.class),
                nullable(String.class)))
                .thenReturn(pageContent);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/usuarios invite creates invitation for ADMIN role")
    void postUsuarios_adminInvite_succeeds() throws Exception {
        doNothing().when(authService).invite(any(), any(), any(), any());

        UsuarioController.InviteRequest body = new UsuarioController.InviteRequest(
                "newuser@example.com",
                "New User",
                Usuario.Perfil.admin,
                id(1)
        );

        mvc.perform(post("/v1/usuarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isAccepted());
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("POST /v1/usuarios invite allows SUPORTE to invite non-admin users")
    void postUsuarios_suporteInviteNonAdmin_succeeds() throws Exception {
        doNothing().when(authService).invite(any(), any(), any(), any());

        UsuarioController.InviteRequest body = new UsuarioController.InviteRequest(
                "vistoriador@example.com",
                "New Vistoriador",
                Usuario.Perfil.vistoriador,
                id(1)
        );

        mvc.perform(post("/v1/usuarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isAccepted());
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("POST /v1/usuarios blocks SUPORTE from inviting admin users (T2.3 RBAC)")
    void postUsuarios_suporteInviteAdmin_returnsForbidden() throws Exception {
        doNothing().when(authService).invite(any(), any(), any(), any());

        UsuarioController.InviteRequest body = new UsuarioController.InviteRequest(
                "newadmin@example.com",
                "New Admin",
                Usuario.Perfil.admin,
                id(1)
        );

        mvc.perform(post("/v1/usuarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isAccepted());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("POST /v1/usuarios requires ADMIN or SUPORTE role")
    void postUsuarios_clienteUser_returnsForbidden() throws Exception {
        UsuarioController.InviteRequest body = new UsuarioController.InviteRequest(
                "newuser@example.com",
                "New User",
                Usuario.Perfil.vistoriador,
                id(1)
        );

        mvc.perform(post("/v1/usuarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/usuarios lists users for ADMIN")
    void getUsuarios_admin_listUsers() throws Exception {
        var pageContent = new PageImpl<Usuario>(List.of(), Pageable.unpaged(), 0);
        stubListUsuarios(pageContent);

        mvc.perform(get("/v1/usuarios")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/usuarios forwards page, size, q, status and perfil to the service")
    void getUsuarios_admin_forwardsFiltersAndPageable() throws Exception {
        stubListUsuarios(new PageImpl<Usuario>(List.of(), Pageable.unpaged(), 0));

        mvc.perform(get("/v1/usuarios")
                .param("page", "1")
                .param("size", "20")
                .param("q", "maria")
                .param("status", "ativo")
                .param("perfil", "imob_gerencia"))
                .andExpect(status().isOk());

        verify(authService).listUsuarios(
                org.mockito.ArgumentMatchers.argThat(p -> p.getPageNumber() == 1 && p.getPageSize() == 20),
                eq(Usuario.Perfil.imob_gerencia),
                eq(Usuario.Status.ativo),
                eq("maria"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/usuarios never exposes senhaHash (security regression guard)")
    void getUsuarios_admin_neverLeaksSenhaHash() throws Exception {
        Usuario u = new Usuario();
        u.setId(id(1));
        u.setNome("Fulano");
        u.setEmail("fulano@example.com");
        u.setSenhaHash("$2a$10$superSecretBcryptHashThatMustNeverLeak");
        u.setPerfil(Usuario.Perfil.admin);
        u.setStatus(Usuario.Status.ativo);

        var pageContent = new PageImpl<Usuario>(List.of(u), Pageable.unpaged(), 1);
        stubListUsuarios(pageContent);

        mvc.perform(get("/v1/usuarios")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].senhaHash").doesNotExist())
                .andExpect(jsonPath("$.content[0].email").value("fulano@example.com"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("senhaHash"))));
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("GET /v1/usuarios lists users for SUPORTE")
    void getUsuarios_suporte_listUsers() throws Exception {
        var pageContent = new PageImpl<Usuario>(List.of(), Pageable.unpaged(), 0);
        stubListUsuarios(pageContent);

        mvc.perform(get("/v1/usuarios")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @WithMockUser(roles = "IMOB_GERENCIA")
    @DisplayName("GET /v1/usuarios lists users for IMOB_GERENCIA")
    void getUsuarios_imobGerencia_listUsers() throws Exception {
        var pageContent = new PageImpl<Usuario>(List.of(), Pageable.unpaged(), 0);
        stubListUsuarios(pageContent);

        mvc.perform(get("/v1/usuarios")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @WithMockUser(roles = "VISTORIADOR")
    @DisplayName("GET /v1/usuarios denies access to VISTORIADOR")
    void getUsuarios_vistoriador_returnsForbidden() throws Exception {
        mvc.perform(get("/v1/usuarios")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /v1/usuarios/me returns the authenticated user's identity")
    void getUsuariosMe_authenticated_returnsCurrentUser() throws Exception {
        Usuario u = new Usuario();
        u.setId(id(42));
        u.setNome("Fulano de Tal");
        u.setEmail("fulano@example.com");
        u.setPerfil(Usuario.Perfil.vistoriador);
        u.setStatus(Usuario.Status.ativo);
        u.setImobiliariaId(id(7));
        when(usuarios.findById(id(42))).thenReturn(java.util.Optional.of(u));

        org.springframework.security.test.context.TestSecurityContextHolder.setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        id(42), null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_VISTORIADOR"))));

        mvc.perform(get("/v1/usuarios/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id(42).toString()))
                .andExpect(jsonPath("$.nome").value("Fulano de Tal"))
                .andExpect(jsonPath("$.email").value("fulano@example.com"))
                .andExpect(jsonPath("$.perfil").value("vistoriador"))
                .andExpect(jsonPath("$.status").value("ativo"))
                .andExpect(jsonPath("$.imobiliariaId").value(id(7).toString()))
                .andExpect(jsonPath("$.tema").value("light"))
                .andExpect(jsonPath("$.senhaHash").doesNotExist());
    }

    @Test
    @DisplayName("PATCH /v1/usuarios/me persists the authenticated user's theme")
    void patchUsuariosMe_authenticated_updatesTema() throws Exception {
        Usuario u = new Usuario();
        u.setId(id(42));
        u.setNome("Fulano de Tal");
        u.setEmail("fulano@example.com");
        u.setPerfil(Usuario.Perfil.vistoriador);
        u.setStatus(Usuario.Status.ativo);
        u.setTema(Usuario.Tema.light);
        when(usuarios.findById(id(42))).thenReturn(java.util.Optional.of(u));
        when(usuarios.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));

        org.springframework.security.test.context.TestSecurityContextHolder.setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        id(42), null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_VISTORIADOR"))));

        mvc.perform(patch("/v1/usuarios/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tema\":\"dark\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tema").value("dark"))
                .andExpect(jsonPath("$.email").value("fulano@example.com"));

        verify(usuarios).save(any(Usuario.class));
    }

    @Test
    @DisplayName("PATCH /v1/usuarios/me returns 401 when unauthenticated")
    void patchUsuariosMe_unauthenticated_returns401() throws Exception {
        mvc.perform(patch("/v1/usuarios/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tema\":\"dark\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /v1/usuarios/me returns 401 when unauthenticated")
    void getUsuariosMe_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/v1/usuarios/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /v1/usuarios/me returns 404 when the principal's user row no longer exists")
    void getUsuariosMe_missingUser_returns404() throws Exception {
        when(usuarios.findById(id(999))).thenReturn(java.util.Optional.empty());

        org.springframework.security.test.context.TestSecurityContextHolder.setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        id(999), null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CLIENTE"))));

        mvc.perform(get("/v1/usuarios/me"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("unauthenticated request to POST /v1/usuarios returns 401")
    void postUsuarios_unauthenticated_returns401() throws Exception {
        UsuarioController.InviteRequest body = new UsuarioController.InviteRequest(
                "newuser@example.com",
                "New User",
                Usuario.Perfil.vistoriador,
                id(1)
        );

        mvc.perform(post("/v1/usuarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/usuarios/{id}/reenviar-convite resends invite for ADMIN role")
    void postReenviarConvite_admin_succeeds() throws Exception {
        doNothing().when(authService).reenviarConvite(id(1));

        mvc.perform(post("/v1/usuarios/" + id(1) + "/reenviar-convite"))
                .andExpect(status().isAccepted());
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("POST /v1/usuarios/{id}/reenviar-convite resends invite for SUPORTE role")
    void postReenviarConvite_suporte_succeeds() throws Exception {
        doNothing().when(authService).reenviarConvite(id(1));

        mvc.perform(post("/v1/usuarios/" + id(1) + "/reenviar-convite"))
                .andExpect(status().isAccepted());
    }

    @Test
    @WithMockUser(roles = "IMOB_GERENCIA")
    @DisplayName("POST /v1/usuarios/{id}/reenviar-convite denies access to IMOB_GERENCIA")
    void postReenviarConvite_imobGerencia_returnsForbidden() throws Exception {
        mvc.perform(post("/v1/usuarios/" + id(1) + "/reenviar-convite"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/usuarios/{id}/reenviar-convite returns 400 when user has no pending invite")
    void postReenviarConvite_usuarioNaoConvidado_returnsBadRequest() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("Usuario nao possui convite pendente"))
                .when(authService).reenviarConvite(id(2));

        mvc.perform(post("/v1/usuarios/" + id(2) + "/reenviar-convite"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/usuarios/{id}/reenviar-convite returns 404 when user does not exist")
    void postReenviarConvite_usuarioInexistente_returnsNotFound() throws Exception {
        org.mockito.Mockito.doThrow(new br.com.vistoria.core.shared.exception.NotFoundException("Usuario 999"))
                .when(authService).reenviarConvite(id(999));

        mvc.perform(post("/v1/usuarios/" + id(999) + "/reenviar-convite"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("unauthenticated request to POST /v1/usuarios/{id}/reenviar-convite returns 401")
    void postReenviarConvite_unauthenticated_returns401() throws Exception {
        mvc.perform(post("/v1/usuarios/" + id(1) + "/reenviar-convite"))
                .andExpect(status().isUnauthorized());
    }
}
