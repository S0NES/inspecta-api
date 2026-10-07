package br.com.vistoria.core.auth;

import br.com.vistoria.core.auth.dto.TokenResponse;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("AuthController - Login/Refresh/Logout (Unit Tests)")
class AuthControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private ApiKeyFilter apiKeyFilter;

    @Autowired
    private ObjectMapper mapper;

    @Test
    @DisplayName("POST /v1/auth/login with valid credentials returns token and sets refresh cookie")
    void login_validCredentials_returnsTokenAndCookie() throws Exception {
        var mockToken = new TokenResponse("valid-jwt-token-here", 900L);
        var mockResult = new AuthService.LoginResult(mockToken, "refresh-token-raw");
        when(authService.login("test@example.com", "senha123")).thenReturn(mockResult);

        Map<String, String> body = Map.of(
                "email", "test@example.com",
                "senha", "senha123"
        );

        mvc.perform(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("valid-jwt-token-here"))
                .andExpect(header().exists("Set-Cookie"))
                .andExpect(cookie().exists("inspecta_refresh"));
    }

    @Test
    @DisplayName("POST /v1/auth/login with invalid email throws exception")
    void login_invalidEmail_returns401() throws Exception {
        // AuthService.login lanca BadCredentialsException — nao IllegalArgumentException.
        // O stub anterior so passava porque o handler mapeava IAE para 401 por engano.
        when(authService.login("nonexistent@example.com", "senha123"))
                .thenThrow(new BadCredentialsException("Credenciais invalidas"));

        Map<String, String> body = Map.of(
                "email", "nonexistent@example.com",
                "senha", "senha123"
        );

        mvc.perform(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /v1/auth/login with incorrect password throws exception")
    void login_incorrectPassword_returns401() throws Exception {
        when(authService.login("test@example.com", "wrongpassword"))
                .thenThrow(new BadCredentialsException("Credenciais invalidas"));

        Map<String, String> body = Map.of(
                "email", "test@example.com",
                "senha", "wrongpassword"
        );

        mvc.perform(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /v1/auth/refresh without refresh token returns 401")
    void refresh_noToken_returns401() throws Exception {
        mvc.perform(post("/v1/auth/refresh"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /v1/auth/refresh with valid refresh token returns new access token")
    void refresh_validToken_returnsNewAccessToken() throws Exception {
        var mockToken = new TokenResponse("new-jwt-token", 900L);
        var mockResult = new AuthService.LoginResult(mockToken, "new-refresh-token-raw");
        when(authService.refresh("valid-refresh-token")).thenReturn(mockResult);

        Cookie refreshCookie = new Cookie("inspecta_refresh", "valid-refresh-token");
        mvc.perform(post("/v1/auth/refresh")
                .cookie(refreshCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-jwt-token"))
                .andExpect(header().exists("Set-Cookie"));
    }

    @Test
    @DisplayName("POST /v1/auth/refresh with invalid token throws exception (T2.8 re-check status)")
    void refresh_invalidToken_returns401() throws Exception {
        when(authService.refresh("invalid-refresh-token"))
                .thenThrow(new BadCredentialsException("Refresh invalido"));

        Cookie invalidCookie = new Cookie("inspecta_refresh", "invalid-refresh-token");
        mvc.perform(post("/v1/auth/refresh")
                .cookie(invalidCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /v1/auth/logout clears refresh cookie")
    void logout_clearsRefreshCookie() throws Exception {
        doNothing().when(authService).logout("valid-refresh-token");

        Cookie logoutCookie = new Cookie("inspecta_refresh", "valid-refresh-token");
        mvc.perform(post("/v1/auth/logout")
                .cookie(logoutCookie))
                .andExpect(status().isNoContent())
                .andExpect(header().exists("Set-Cookie"));
    }

    @Test
    @DisplayName("POST /v1/auth/logout without refresh token succeeds gracefully")
    void logout_noToken_succeeds() throws Exception {
        mvc.perform(post("/v1/auth/logout"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("POST /v1/auth/invite/accept calls authService with token/senha/nome")
    void acceptInvite_validRequest_succeeds() throws Exception {
        doNothing().when(authService).acceptInvite("invite-token", "novaSenha123", "Nova Pessoa");

        Map<String, String> body = Map.of(
                "token", "invite-token",
                "senha", "novaSenha123",
                "nome", "Nova Pessoa"
        );

        mvc.perform(post("/v1/auth/invite/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("POST /v1/auth/esqueci-senha always returns 202, even for an unknown email")
    void esqueciSenha_qualquerEmail_retorna202() throws Exception {
        doNothing().when(authService).esqueciSenha("fantasma@example.com");

        Map<String, String> body = Map.of("email", "fantasma@example.com");

        mvc.perform(post("/v1/auth/esqueci-senha")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("POST /v1/auth/redefinir-senha with a valid code returns 204")
    void redefinirSenha_valido_retorna204() throws Exception {
        doNothing().when(authService).redefinirSenha("reset@example.com", "123456", "Nova#Senha1");

        Map<String, String> body = Map.of(
                "email", "reset@example.com",
                "codigo", "123456",
                "novaSenha", "Nova#Senha1"
        );

        mvc.perform(post("/v1/auth/redefinir-senha")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("POST /v1/auth/redefinir-senha with an invalid code returns 401 with the generic message")
    void redefinirSenha_codigoInvalido_retorna401ComMensagemGenerica() throws Exception {
        org.mockito.Mockito.doThrow(new BadCredentialsException("Credencial invalida"))
                .when(authService).redefinirSenha("reset@example.com", "000000", "Nova#Senha1");

        Map<String, String> body = Map.of(
                "email", "reset@example.com",
                "codigo", "000000",
                "novaSenha", "Nova#Senha1"
        );

        mvc.perform(post("/v1/auth/redefinir-senha")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Credencial invalida"));
    }

    @Test
    @DisplayName("POST /v1/auth/redefinir-senha with a weak password returns 400")
    void redefinirSenha_senhaFraca_retorna400() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("A senha deve ter entre 5 e 50 caracteres"))
                .when(authService).redefinirSenha("reset@example.com", "123456", "abc");

        Map<String, String> body = Map.of(
                "email", "reset@example.com",
                "codigo", "123456",
                "novaSenha", "abc"
        );

        mvc.perform(post("/v1/auth/redefinir-senha")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }
}
