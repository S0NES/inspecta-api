package br.com.vistoria.core.auth;

import br.com.vistoria.core.config.TenantContext;
import br.com.vistoria.core.modules.usuario.Usuario;
import br.com.vistoria.core.modules.usuario.UsuarioRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

// Unit-level counterpart to ApiKeyFilterTest (which extends AbstractIntegrationTest and needs a
// real Postgres via Testcontainers). This class mocks ApiKeyService/UsuarioRepository directly so
// the fail-closed logic in ApiKeyFilter.doFilterInternal is exercised even in environments where
// Testcontainers/Docker isn't reachable (see AbstractIntegrationTest's disabledWithoutDocker note).
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyFilter - Fail-Closed Behavior (unit)")
class ApiKeyFilterUnitTest {

    @Mock
    private ApiKeyService apiKeyService;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private br.com.vistoria.core.modules.usuario.UsuarioImobiliariaRepository usuarioImobiliariaRepository;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private ApiKeyFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyFilter(apiKeyService, usuarioRepository, usuarioImobiliariaRepository);
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private static final UUID USUARIO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID IMOBILIARIA_ID = UUID.fromString("00000000-0000-0000-0000-000000000050");

    private TokenEntity token(UUID usuarioId, String... escopos) {
        TokenEntity t = new TokenEntity();
        t.setUsuarioId(usuarioId);
        t.setPrefix("mk_test");
        t.setEscopos(escopos);
        return t;
    }

    private Usuario usuario(UUID id, Usuario.Status status, UUID imobiliariaId) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setStatus(status);
        u.setImobiliariaId(imobiliariaId);
        return u;
    }

    @Test
    @DisplayName("valid key with active user authenticates and sets tenant context")
    void validKeyActiveUser_authenticates() throws ServletException, IOException {
        when(request.getHeader(ApiKeyFilter.HEADER)).thenReturn("mk_test.secret");
        when(apiKeyService.validate("mk_test.secret")).thenReturn(Optional.of(token(USUARIO_ID, "read", "write")));
        when(usuarioRepository.findById(USUARIO_ID)).thenReturn(Optional.of(usuario(USUARIO_ID, Usuario.Status.ativo, IMOBILIARIA_ID)));

        filter.doFilterInternal(request, response, filterChain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo("api-key:mk_test");
        assertThat(auth.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_SCOPE_READ", "ROLE_SCOPE_WRITE");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("valid key from inactive user is rejected (fail-closed)")
    void validKeyInactiveUser_doesNotAuthenticate() throws ServletException, IOException {
        when(request.getHeader(ApiKeyFilter.HEADER)).thenReturn("mk_test.secret");
        when(apiKeyService.validate("mk_test.secret")).thenReturn(Optional.of(token(USUARIO_ID, "read")));
        when(usuarioRepository.findById(USUARIO_ID)).thenReturn(Optional.of(usuario(USUARIO_ID, Usuario.Status.inativo, IMOBILIARIA_ID)));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("valid key from deleted/orphaned user is rejected (fail-closed)")
    void validKeyDeletedUser_doesNotAuthenticate() throws ServletException, IOException {
        when(request.getHeader(ApiKeyFilter.HEADER)).thenReturn("mk_test.secret");
        when(apiKeyService.validate("mk_test.secret")).thenReturn(Optional.of(token(USUARIO_ID, "read")));
        when(usuarioRepository.findById(USUARIO_ID)).thenReturn(Optional.empty());

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("invalid/unknown key is rejected")
    void invalidKey_doesNotAuthenticate() throws ServletException, IOException {
        when(request.getHeader(ApiKeyFilter.HEADER)).thenReturn("bogus-key");
        when(apiKeyService.validate("bogus-key")).thenReturn(Optional.empty());

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(usuarioRepository);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("no API key header passes through without touching apiKeyService")
    void noHeader_passesThrough() throws ServletException, IOException {
        when(request.getHeader(ApiKeyFilter.HEADER)).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(apiKeyService, never()).validate(anyString());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("existing authentication (e.g. from JwtAuthFilter) is not overridden")
    void existingAuth_isNotOverridden() throws ServletException, IOException {
        var existing = mock(org.springframework.security.core.Authentication.class);
        SecurityContextHolder.getContext().setAuthentication(existing);
        when(request.getHeader(ApiKeyFilter.HEADER)).thenReturn("mk_test.secret");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isEqualTo(existing);
        verify(apiKeyService, never()).validate(anyString());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("active user with null imobiliariaId (admin/suporte) authenticates with null tenant")
    void activeUserNullImobiliaria_authenticatesWithNullTenant() throws ServletException, IOException {
        when(request.getHeader(ApiKeyFilter.HEADER)).thenReturn("mk_test.secret");
        when(apiKeyService.validate("mk_test.secret")).thenReturn(Optional.of(token(USUARIO_ID, "read")));
        when(usuarioRepository.findById(USUARIO_ID)).thenReturn(Optional.of(usuario(USUARIO_ID, Usuario.Status.ativo, null)));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("TenantContext is always cleared after the filter chain, even without a key")
    void tenantContext_clearedAfterFilterEvenWithoutKey() throws ServletException, IOException {
        when(request.getHeader(ApiKeyFilter.HEADER)).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(TenantContext.get()).isNull();
    }
}
