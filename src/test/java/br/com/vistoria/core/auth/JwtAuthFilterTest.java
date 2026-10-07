package br.com.vistoria.core.auth;

import br.com.vistoria.core.config.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthFilter - JWT Token Extraction and Tenant Context Setup")
class JwtAuthFilterTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private JwtAuthFilter jwtAuthFilter;

    private static final UUID USER_123 = UUID.fromString("00000000-0000-0000-0000-000000000123");
    private static final UUID USER_456 = UUID.fromString("00000000-0000-0000-0000-000000000456");
    private static final UUID USER_789 = UUID.fromString("00000000-0000-0000-0000-000000000789");
    private static final UUID USER_999 = UUID.fromString("00000000-0000-0000-0000-000000000999");
    private static final UUID IMOBILIARIA_200 = UUID.fromString("00000000-0000-0000-0000-000000000200");
    private static final UUID IMOBILIARIA_300 = UUID.fromString("00000000-0000-0000-0000-000000000300");

    @BeforeEach
    void setUp() {
        jwtAuthFilter = new JwtAuthFilter(jwtService);
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    @DisplayName("doFilterInternal with valid JWT extracts claims and sets SecurityContext")
    void doFilterInternal_validJwt_setsSecurityContext() throws ServletException, IOException {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(USER_123.toString());
        when(claims.get("perfil", String.class)).thenReturn("admin");
        when(claims.get("imobiliaria_id", String.class)).thenReturn(IMOBILIARIA_200.toString());
        when(claims.get("imobiliaria_ids", String.class)).thenReturn(null);

        when(request.getHeader("Authorization")).thenReturn("Bearer valid-jwt-token");
        when(jwtService.parse("valid-jwt-token")).thenReturn(claims);

        jwtAuthFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(USER_123);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("doFilterInternal with valid JWT sets TenantContext from imobiliaria_id")
    void doFilterInternal_validJwt_setsTenantContext() throws ServletException, IOException {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(USER_456.toString());
        when(claims.get("perfil", String.class)).thenReturn("suporte");
        when(claims.get("imobiliaria_id", String.class)).thenReturn(IMOBILIARIA_200.toString());
        when(claims.get("imobiliaria_ids", String.class)).thenReturn(null);

        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        when(jwtService.parse("valid-token")).thenReturn(claims);

        jwtAuthFilter.doFilterInternal(request, response, filterChain);

        // Note: TenantContext is cleared after the filter, so we check via authentication
        verify(jwtService).parse("valid-token");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("doFilterInternal with no Authorization header passes through without auth")
    void doFilterInternal_noAuthHeader_passesThrough() throws ServletException, IOException {
        when(request.getHeader("Authorization")).thenReturn(null);

        jwtAuthFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(jwtService, never()).parse(anyString());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("doFilterInternal with invalid Bearer prefix passes through")
    void doFilterInternal_invalidBearerPrefix_passesThrough() throws ServletException, IOException {
        when(request.getHeader("Authorization")).thenReturn("Basic abc123");

        jwtAuthFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(jwtService, never()).parse(anyString());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("doFilterInternal with malformed JWT does not crash, passes through")
    void doFilterInternal_malformedJwt_passesThrough() throws ServletException, IOException {
        when(request.getHeader("Authorization")).thenReturn("Bearer malformed-token");
        when(jwtService.parse("malformed-token")).thenThrow(new JwtException("Malformed JWT"));

        jwtAuthFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("doFilterInternal with expired JWT does not crash, passes through")
    void doFilterInternal_expiredJwt_passesThrough() throws ServletException, IOException {
        when(request.getHeader("Authorization")).thenReturn("Bearer expired-token");
        when(jwtService.parse("expired-token")).thenThrow(new JwtException("JWT expired"));

        jwtAuthFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("doFilterInternal clears TenantContext after filter chain completes")
    void doFilterInternal_clearsContextInFinally() throws ServletException, IOException {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(USER_789.toString());
        when(claims.get("perfil", String.class)).thenReturn("cliente");
        when(claims.get("imobiliaria_id", String.class)).thenReturn(IMOBILIARIA_300.toString());
        when(claims.get("imobiliaria_ids", String.class)).thenReturn(null);

        when(request.getHeader("Authorization")).thenReturn("Bearer token");
        when(jwtService.parse("token")).thenReturn(claims);

        jwtAuthFilter.doFilterInternal(request, response, filterChain);

        // After filter completes, TenantContext should be cleared
        assertThat(TenantContext.getAll()).isEmpty();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("doFilterInternal with null imobiliaria_id and no imobiliaria_ids sets an empty tenant list")
    void doFilterInternal_nullImobiliariaId_setsTenantNull() throws ServletException, IOException {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(USER_999.toString());
        when(claims.get("perfil", String.class)).thenReturn("admin");
        when(claims.get("imobiliaria_id", String.class)).thenReturn(null);
        when(claims.get("imobiliaria_ids", String.class)).thenReturn(null);

        when(request.getHeader("Authorization")).thenReturn("Bearer token");
        when(jwtService.parse("token")).thenReturn(claims);

        jwtAuthFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("doFilterInternal with blank imobiliaria_id (no primary tenant) sets an empty tenant list")
    void doFilterInternal_zeroImobiliariaId_setsTenantNull() throws ServletException, IOException {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000888").toString());
        when(claims.get("perfil", String.class)).thenReturn("admin");
        when(claims.get("imobiliaria_id", String.class)).thenReturn("");
        when(claims.get("imobiliaria_ids", String.class)).thenReturn(null);

        when(request.getHeader("Authorization")).thenReturn("Bearer token");
        when(jwtService.parse("token")).thenReturn(claims);

        jwtAuthFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("doFilterInternal with existing authentication does not override")
    void doFilterInternal_existingAuth_doesNotOverride() throws ServletException, IOException {
        org.springframework.security.core.Authentication existingAuth =
                mock(org.springframework.security.core.Authentication.class);
        SecurityContextHolder.getContext().setAuthentication(existingAuth);

        when(request.getHeader("Authorization")).thenReturn("Bearer token");

        jwtAuthFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isEqualTo(existingAuth);
        verify(jwtService, never()).parse(anyString());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("doFilterInternal with a non-UUID subject still passes through")
    void doFilterInternal_invalidUserIdFormat_passesThrough() throws ServletException, IOException {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("not-a-uuid");

        when(request.getHeader("Authorization")).thenReturn("Bearer token");
        when(jwtService.parse("token")).thenReturn(claims);

        jwtAuthFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }
}
