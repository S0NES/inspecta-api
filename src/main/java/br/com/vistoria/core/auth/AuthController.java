package br.com.vistoria.core.auth;

import br.com.vistoria.core.auth.dto.LoginRequest;
import br.com.vistoria.core.auth.dto.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Endpoints de autenticação, refresh e logout")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "Login com email e senha", description = "Autentica um usuário com email e senha, retornando um JWT access token. O refresh token é retornado como HTTP-only cookie 'inspecta_refresh'.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login bem-sucedido", content = @Content(schema = @Schema(implementation = TokenResponse.class))),
            @ApiResponse(responseCode = "400", description = "Email ou senha inválidos"),
            @ApiResponse(responseCode = "401", description = "Usuário inativo ou não encontrado")
    })
    public ResponseEntity<TokenResponse> login(
            @RequestBody(description = "Credenciais de login") @Valid @org.springframework.web.bind.annotation.RequestBody LoginRequest body) {
        var r = authService.login(body.email(), body.senha());
        return withRefreshCookie(r);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Renovar JWT access token", description = "Renova um JWT access token usando o refresh token armazenado em cookie. O refresh token é verificado e o usuário deve estar ativo. A status do usuário é verificada no caminho de refresh (T2.8).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token renovado com sucesso", content = @Content(schema = @Schema(implementation = TokenResponse.class))),
            @ApiResponse(responseCode = "401", description = "Refresh token ausente, inválido ou usuário inativo")
    })
    public ResponseEntity<TokenResponse> refresh(@CookieValue(name = "inspecta_refresh", required = false) String refresh) {
        if (refresh == null) return ResponseEntity.status(401).build();
        return withRefreshCookie(authService.refresh(refresh));
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout e invalidação de refresh token", description = "Invalida o refresh token do usuário e limpa o cookie 'inspecta_refresh'.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Logout bem-sucedido, cookie limpo"),
            @ApiResponse(responseCode = "200", description = "Logout bem-sucedido sem refresh token presente")
    })
    public ResponseEntity<Void> logout(@CookieValue(name = "inspecta_refresh", required = false) String refresh) {
        if (refresh != null) authService.logout(refresh);
        ResponseCookie clearRefresh = ResponseCookie.from("inspecta_refresh", "")
                .httpOnly(true).secure(true).sameSite("None")
                .maxAge(0).path("/v1/auth").build();
        ResponseCookie clearAccess = ResponseCookie.from("inspecta_access", "")
                .httpOnly(true).secure(true).sameSite("None")
                .maxAge(0).path("/").build();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearRefresh.toString())
                .header(HttpHeaders.SET_COOKIE, clearAccess.toString())
                .build();
    }

    @PostMapping("/esqueci-senha")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Solicitar codigo de recuperacao de senha", description = "Envia por email um codigo de 6 digitos para redefinir a senha, valido por 15 minutos. "
            + "Sempre responde 202, mesmo se o email nao existir ou a conta nao estiver ativa — nao revela isso ao chamador (evita enumeracao de contas).")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Solicitacao aceita (codigo enviado se a conta existir e estiver ativa)")
    })
    public ResponseEntity<Void> esqueciSenha(
            @RequestBody(description = "Payload com email") @org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        authService.esqueciSenha(body.get("email"));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/redefinir-senha")
    @Operation(summary = "Redefinir senha com codigo de recuperacao", description = "Confirma o codigo enviado por email e define a nova senha (que deve seguir a politica de senha forte: "
            + "5 a 50 caracteres, com letra maiuscula, minuscula e caractere especial). Codigo invalido, expirado, ja usado, "
            + "ou que nao pertence ao email informado retornam sempre o mesmo erro generico (T-seguranca).")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Senha redefinida com sucesso; sessoes anteriores foram encerradas"),
            @ApiResponse(responseCode = "400", description = "Senha nova nao atende a politica de senha forte"),
            @ApiResponse(responseCode = "401", description = "Codigo invalido, expirado ou que nao corresponde ao email")
    })
    public ResponseEntity<Void> redefinirSenha(
            @RequestBody(description = "Payload com email, codigo e novaSenha") @org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        authService.redefinirSenha(body.get("email"), body.get("codigo"), body.get("novaSenha"));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/invite/accept")
    @Operation(summary = "Aceitar convite de usuário", description = "Aceita um convite de usuário (enviado por email via inspecta-messenger). O token de convite é validado, a senha é definida e o perfil é ativado.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Convite aceito com sucesso, usuário ativado"),
            @ApiResponse(responseCode = "400", description = "Senha nova não atende à política de senha forte"),
            @ApiResponse(responseCode = "401", description = "Token de convite inválido ou expirado"),
            @ApiResponse(responseCode = "404", description = "Usuário não encontrado para o token")
    })
    public ResponseEntity<Void> acceptInvite(
            @RequestBody(description = "Payload com token, senha e nome") @org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        authService.acceptInvite(body.get("token"), body.get("senha"), body.get("nome"));
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<TokenResponse> withRefreshCookie(AuthService.LoginResult r) {
        // SameSite=None (não Strict/Lax): inspecta-web e inspecta-api são domínios diferentes em prod
        // (Cloudflare Pages vs Dokploy, ver CorsConfig.java) — SameSite=Strict/Lax faz o
        // navegador nunca enviar o cookie de volta em fetch cross-site, o que quebrava
        // toda chamada autenticada após o login. Seguro porque secure=true (só HTTPS) e
        // o CORS já restringe a origem exata (nunca "*") com allowCredentials.
        ResponseCookie refreshCookie = ResponseCookie.from("inspecta_refresh", r.refreshRaw())
                .httpOnly(true).secure(true).sameSite("None")
                .path("/v1/auth").maxAge(Duration.ofDays(7)).build();
        ResponseCookie accessCookie = ResponseCookie.from("inspecta_access", r.access().accessToken())
                .httpOnly(true).secure(true).sameSite("None")
                .path("/").maxAge(Duration.ofSeconds(r.access().expiresInSeconds())).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .body(r.access());
    }
}
