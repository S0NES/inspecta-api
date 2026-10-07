package br.com.vistoria.core.auth;

import br.com.vistoria.core.auth.dto.TokenResponse;
import br.com.vistoria.core.modules.usuario.Usuario;
import br.com.vistoria.core.modules.usuario.UsuarioRepository;
import br.com.vistoria.core.shared.outbox.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService - Login/Refresh/Logout (Unit Tests)")
class AuthServiceTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private UsuarioRepository usuarios;

    @Mock
    private br.com.vistoria.core.modules.usuario.UsuarioImobiliariaRepository usuarioImobiliarias;

    @Mock
    private br.com.vistoria.core.modules.disponibilidade.DisponibilidadeRepositories.VistoriadorRepository vistoriadores;

    @Mock
    private TokenRepository tokens;

    @Mock
    private PasswordEncoder encoder;

    @Mock
    private OutboxService outbox;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(jwtService, usuarios, usuarioImobiliarias, vistoriadores, tokens, encoder, outbox);
    }

    /** UUID determinístico por pequeno inteiro, só para tornar os ids do teste legíveis. */
    private static UUID id(long n) {
        return new UUID(0L, n);
    }

    @Test
    @DisplayName("login with valid email and password returns access token and refresh token")
    void login_validCredentials_returnsTokens() {
        Usuario user = new Usuario();
        user.setId(id(1));
        user.setEmail("test@example.com");
        user.setStatus(Usuario.Status.ativo);
        user.setPerfil(Usuario.Perfil.admin);
        user.setImobiliariaId(id(100));
        user.setSenhaHash("hashed_password");

        when(usuarios.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches("senha123", "hashed_password")).thenReturn(true);
        when(jwtService.issueAccessToken(eq(id(1)), eq("admin"), eq(id(100)), any())).thenReturn("access-token-123");

        TokenEntity refreshToken = new TokenEntity();
        refreshToken.setHash("hash123");
        when(tokens.save(any(TokenEntity.class))).thenReturn(refreshToken);

        AuthService.LoginResult result = authService.login("test@example.com", "senha123");

        assertThat(result.access().accessToken()).isEqualTo("access-token-123");
        assertThat(result.refreshRaw()).isNotNull();
        verify(usuarios).findByEmail("test@example.com");
        verify(encoder).matches("senha123", "hashed_password");
        verify(jwtService).issueAccessToken(eq(id(1)), eq("admin"), eq(id(100)), any());
    }

    @Test
    @DisplayName("login with non-existent email throws BadCredentialsException")
    void login_nonExistentEmail_throwsException() {
        when(usuarios.findByEmail("nonexistent@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("nonexistent@example.com", "senha123"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("invalidas");
    }

    @Test
    @DisplayName("login with incorrect password throws BadCredentialsException")
    void login_incorrectPassword_throwsException() {
        Usuario user = new Usuario();
        user.setId(id(1));
        user.setEmail("test@example.com");
        user.setStatus(Usuario.Status.ativo);
        user.setSenhaHash("hashed_password");

        when(usuarios.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches("wrongpassword", "hashed_password")).thenReturn(false);

        assertThatThrownBy(() -> authService.login("test@example.com", "wrongpassword"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("invalidas");
    }

    @Test
    @DisplayName("login with inactive user throws BadCredentialsException")
    void login_inactiveUser_throwsException() {
        Usuario user = new Usuario();
        user.setId(id(1));
        user.setEmail("test@example.com");
        user.setStatus(Usuario.Status.inativo);
        user.setSenhaHash("hashed_password");

        when(usuarios.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login("test@example.com", "senha123"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("invalidas");
    }

    @Test
    @DisplayName("login with null password hash returns 401")
    void login_nullPasswordHash_throwsException() {
        Usuario user = new Usuario();
        user.setId(id(1));
        user.setEmail("test@example.com");
        user.setStatus(Usuario.Status.ativo);
        user.setSenhaHash(null);

        when(usuarios.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login("test@example.com", "senha123"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("refresh with valid refresh token returns new access token")
    void refresh_validToken_returnsNewAccessToken() {
        Usuario user = new Usuario();
        user.setId(id(2));
        user.setStatus(Usuario.Status.ativo);
        user.setPerfil(Usuario.Perfil.suporte);
        user.setImobiliariaId(id(200));

        TokenEntity tokenEntity = new TokenEntity();
        tokenEntity.setId(id(1));
        tokenEntity.setUsuarioId(id(2));
        tokenEntity.setTipo(TokenEntity.Tipo.refresh);
        tokenEntity.setRevogado(false);
        tokenEntity.setExpiraEm(Instant.now().plusSeconds(86400));

        when(tokens.findByHashAndTipo(anyString(), eq(TokenEntity.Tipo.refresh)))
                .thenReturn(Optional.of(tokenEntity));
        when(usuarios.findById(id(2))).thenReturn(Optional.of(user));
        when(jwtService.issueAccessToken(eq(id(2)), eq("suporte"), eq(id(200)), any())).thenReturn("new-access-token");

        AuthService.LoginResult result = authService.refresh("valid-refresh-token");

        assertThat(result.access().accessToken()).isEqualTo("new-access-token");
        assertThat(result.refreshRaw()).isEqualTo("valid-refresh-token");
    }

    @Test
    @DisplayName("refresh reuses the same refresh token (no rotation)")
    void refresh_validToken_reusesRefreshRaw() {
        Usuario user = new Usuario();
        user.setId(id(2));
        user.setStatus(Usuario.Status.ativo);
        user.setPerfil(Usuario.Perfil.admin);
        user.setImobiliariaId(id(200));

        TokenEntity tokenEntity = new TokenEntity();
        tokenEntity.setId(id(1));
        tokenEntity.setUsuarioId(id(2));
        tokenEntity.setTipo(TokenEntity.Tipo.refresh);
        tokenEntity.setRevogado(false);
        tokenEntity.setExpiraEm(Instant.now().plus(Duration.ofHours(12)));

        when(tokens.findByHashAndTipo(anyString(), eq(TokenEntity.Tipo.refresh)))
                .thenReturn(Optional.of(tokenEntity));
        when(usuarios.findById(id(2))).thenReturn(Optional.of(user));
        when(jwtService.issueAccessToken(eq(id(2)), eq("admin"), eq(id(200)), any())).thenReturn("new-access-token");

        AuthService.LoginResult result = authService.refresh("same-refresh-raw");

        assertThat(result.access().accessToken()).isEqualTo("new-access-token");
        assertThat(result.refreshRaw()).isEqualTo("same-refresh-raw");
        assertThat(tokenEntity.isRevogado()).isFalse();
        verify(tokens, never()).save(any(TokenEntity.class));
    }

    @Test
    @DisplayName("refresh with revoked token throws BadCredentialsException")
    void refresh_revokedToken_throwsException() {
        TokenEntity tokenEntity = new TokenEntity();
        tokenEntity.setId(id(1));
        tokenEntity.setUsuarioId(id(2));
        tokenEntity.setTipo(TokenEntity.Tipo.refresh);
        tokenEntity.setRevogado(true);
        tokenEntity.setExpiraEm(Instant.now());

        when(tokens.findByHashAndTipo(anyString(), eq(TokenEntity.Tipo.refresh)))
                .thenReturn(Optional.of(tokenEntity));

        assertThatThrownBy(() -> authService.refresh("revoked-token"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Refresh invalido");

        // Replay/corrida de abas: recusar ESTE token, sem derrubar as outras sessões.
        verify(tokens, never()).findByUsuarioIdAndTipoAndRevogadoFalse(any(), any());
    }

    @Test
    @DisplayName("refresh with expired token throws BadCredentialsException")
    void refresh_expiredToken_throwsException() {
        TokenEntity tokenEntity = new TokenEntity();
        tokenEntity.setId(id(1));
        tokenEntity.setUsuarioId(id(2));
        tokenEntity.setTipo(TokenEntity.Tipo.refresh);
        tokenEntity.setRevogado(false);
        tokenEntity.setExpiraEm(Instant.now().minusSeconds(1));

        when(tokens.findByHashAndTipo(anyString(), eq(TokenEntity.Tipo.refresh)))
                .thenReturn(Optional.of(tokenEntity));

        assertThatThrownBy(() -> authService.refresh("expired-token"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Refresh invalido");

        // Um refresh expirado num aparelho não pode invalidar a sessão ainda válida noutro.
        verify(tokens, never()).findByUsuarioIdAndTipoAndRevogadoFalse(any(), any());
    }

    @Test
    @DisplayName("refresh with inactive user throws BadCredentialsException")
    void refresh_inactiveUser_throwsException() {
        Usuario user = new Usuario();
        user.setId(id(2));
        user.setStatus(Usuario.Status.inativo);

        TokenEntity tokenEntity = new TokenEntity();
        tokenEntity.setId(id(1));
        tokenEntity.setUsuarioId(id(2));
        tokenEntity.setTipo(TokenEntity.Tipo.refresh);
        tokenEntity.setRevogado(false);
        tokenEntity.setExpiraEm(Instant.now().plusSeconds(86400));

        when(tokens.findByHashAndTipo(anyString(), eq(TokenEntity.Tipo.refresh)))
                .thenReturn(Optional.of(tokenEntity));
        when(usuarios.findById(id(2))).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.refresh("valid-token-but-inactive-user"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("inativo");
    }

    @Test
    @DisplayName("refresh with non-existent token throws BadCredentialsException")
    void refresh_nonExistentToken_throwsException() {
        when(tokens.findByHashAndTipo(anyString(), eq(TokenEntity.Tipo.refresh)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("nonexistent-token"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Refresh invalido");
    }

    @Test
    @DisplayName("logout revokes refresh token")
    void logout_validToken_revokesToken() {
        TokenEntity tokenEntity = new TokenEntity();
        tokenEntity.setId(id(1));
        tokenEntity.setRevogado(false);

        when(tokens.findByHashAndTipo(anyString(), eq(TokenEntity.Tipo.refresh)))
                .thenReturn(Optional.of(tokenEntity));

        authService.logout("refresh-token-to-revoke");

        assertThat(tokenEntity.isRevogado()).isTrue();
        verify(tokens).findByHashAndTipo(anyString(), eq(TokenEntity.Tipo.refresh));
    }

    @Test
    @DisplayName("logout with non-existent token succeeds silently")
    void logout_nonExistentToken_succeeds() {
        when(tokens.findByHashAndTipo(anyString(), eq(TokenEntity.Tipo.refresh)))
                .thenReturn(Optional.empty());

        authService.logout("nonexistent-token");
        // Should not throw
    }

    @Test
    @DisplayName("reenviarConvite for pending user revokes old invite tokens and issues a new one")
    void reenviarConvite_usuarioConvidado_revogaTokenAntigoEEmiteNovo() {
        Usuario user = new Usuario();
        user.setId(id(3));
        user.setEmail("pendente@example.com");
        user.setNome("Pendente");
        user.setStatus(Usuario.Status.convidado);

        TokenEntity tokenAntigo = new TokenEntity();
        tokenAntigo.setId(id(10));
        tokenAntigo.setUsuarioId(id(3));
        tokenAntigo.setTipo(TokenEntity.Tipo.invite);
        tokenAntigo.setRevogado(false);

        when(usuarios.findById(id(3))).thenReturn(Optional.of(user));
        when(tokens.findByUsuarioIdAndTipoAndRevogadoFalse(id(3), TokenEntity.Tipo.invite))
                .thenReturn(List.of(tokenAntigo));
        when(tokens.save(any(TokenEntity.class))).thenAnswer(inv -> {
            TokenEntity t = inv.getArgument(0);
            t.setId(id(11));
            return t;
        });

        authService.reenviarConvite(id(3));

        assertThat(tokenAntigo.isRevogado()).isTrue();
        verify(tokens).save(any(TokenEntity.class));
        verify(outbox).publish(eq(br.com.vistoria.core.events.EventTypes.USER_INVITED), any());
    }

    @Test
    @DisplayName("reenviarConvite for non-existent user throws NotFoundException")
    void reenviarConvite_usuarioInexistente_throwsNotFound() {
        when(usuarios.findById(id(99))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.reenviarConvite(id(99)))
                .isInstanceOf(br.com.vistoria.core.shared.exception.NotFoundException.class);
    }

    @Test
    @DisplayName("reenviarConvite for inactive user without password restores the invite")
    void reenviarConvite_inativoSemSenha_restauraConvite() {
        Usuario user = new Usuario();
        user.setId(id(3));
        user.setEmail("pendente@example.com");
        user.setNome("Pendente");
        user.setStatus(Usuario.Status.inativo);

        when(usuarios.findById(id(3))).thenReturn(Optional.of(user));
        when(tokens.findByUsuarioIdAndTipoAndRevogadoFalse(id(3), TokenEntity.Tipo.invite))
                .thenReturn(List.of());
        when(tokens.save(any(TokenEntity.class))).thenAnswer(inv -> {
            TokenEntity t = inv.getArgument(0);
            t.setId(id(11));
            return t;
        });

        authService.reenviarConvite(id(3));

        assertThat(user.getStatus()).isEqualTo(Usuario.Status.convidado);
        verify(tokens).save(any(TokenEntity.class));
        verify(outbox).publish(eq(br.com.vistoria.core.events.EventTypes.USER_INVITED), any());
    }

    @Test
    @DisplayName("reenviarConvite for already active user throws IllegalStateException")
    void reenviarConvite_usuarioAtivo_throwsIllegalState() {
        Usuario user = new Usuario();
        user.setId(id(4));
        user.setStatus(Usuario.Status.ativo);

        when(usuarios.findById(id(4))).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.reenviarConvite(id(4)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("convite pendente");
    }

    @Test
    @DisplayName("acceptInvite with a weak password throws IllegalArgumentException before touching the user")
    void acceptInvite_weakPassword_throwsIllegalArgument() {
        TokenEntity tokenEntity = new TokenEntity();
        tokenEntity.setId(id(1));
        tokenEntity.setUsuarioId(id(5));
        tokenEntity.setTipo(TokenEntity.Tipo.invite);
        tokenEntity.setRevogado(false);
        tokenEntity.setExpiraEm(Instant.now().plusSeconds(3600));

        when(tokens.findByHashAndTipo(anyString(), eq(TokenEntity.Tipo.invite)))
                .thenReturn(Optional.of(tokenEntity));

        assertThatThrownBy(() -> authService.acceptInvite("raw-token", "abc", "Novo Usuario"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("acceptInvite with a strong password activates the user")
    void acceptInvite_strongPassword_activatesUser() {
        Usuario user = new Usuario();
        user.setId(id(5));
        user.setStatus(Usuario.Status.convidado);

        TokenEntity tokenEntity = new TokenEntity();
        tokenEntity.setId(id(1));
        tokenEntity.setUsuarioId(id(5));
        tokenEntity.setTipo(TokenEntity.Tipo.invite);
        tokenEntity.setRevogado(false);
        tokenEntity.setExpiraEm(Instant.now().plusSeconds(3600));

        when(tokens.findByHashAndTipo(anyString(), eq(TokenEntity.Tipo.invite)))
                .thenReturn(Optional.of(tokenEntity));
        when(usuarios.findById(id(5))).thenReturn(Optional.of(user));
        when(encoder.encode("Senha#Forte1")).thenReturn("hash-novo");

        authService.acceptInvite("raw-token", "Senha#Forte1", "Novo Usuario");

        assertThat(user.getStatus()).isEqualTo(Usuario.Status.ativo);
        assertThat(user.getSenhaHash()).isEqualTo("hash-novo");
        assertThat(tokenEntity.isRevogado()).isTrue();
    }

    @Test
    @DisplayName("esqueciSenha for an active user issues a reset code and dispatches the email")
    void esqueciSenha_usuarioAtivo_emiteCodigoEDisparaEmail() {
        Usuario user = new Usuario();
        user.setId(id(6));
        user.setEmail("ativo@example.com");
        user.setStatus(Usuario.Status.ativo);

        when(usuarios.findByEmail("ativo@example.com")).thenReturn(Optional.of(user));
        when(tokens.findByUsuarioIdAndTipoAndRevogadoFalse(id(6), TokenEntity.Tipo.reset_senha))
                .thenReturn(List.of());
        when(tokens.save(any(TokenEntity.class))).thenAnswer(inv -> {
            TokenEntity t = inv.getArgument(0);
            t.setId(id(20));
            return t;
        });

        authService.esqueciSenha("ativo@example.com");

        verify(tokens).save(any(TokenEntity.class));
        verify(outbox).publish(eq(br.com.vistoria.core.events.EventTypes.PASSWORD_RESET_REQUESTED), any());
    }

    @Test
    @DisplayName("esqueciSenha for a non-existent email does nothing and never dispatches an email")
    void esqueciSenha_emailInexistente_naoFazNada() {
        when(usuarios.findByEmail("fantasma@example.com")).thenReturn(Optional.empty());

        authService.esqueciSenha("fantasma@example.com");

        verify(tokens, org.mockito.Mockito.never()).save(any(TokenEntity.class));
        verify(outbox, org.mockito.Mockito.never()).publish(anyString(), any());
    }

    @Test
    @DisplayName("esqueciSenha for an inactive user does nothing (same as non-existent, from the caller's view)")
    void esqueciSenha_usuarioInativo_naoFazNada() {
        Usuario user = new Usuario();
        user.setId(id(7));
        user.setEmail("inativo@example.com");
        user.setStatus(Usuario.Status.inativo);

        when(usuarios.findByEmail("inativo@example.com")).thenReturn(Optional.of(user));

        authService.esqueciSenha("inativo@example.com");

        verify(tokens, org.mockito.Mockito.never()).save(any(TokenEntity.class));
        verify(outbox, org.mockito.Mockito.never()).publish(anyString(), any());
    }

    @Test
    @DisplayName("redefinirSenha with a valid code and strong password updates the hash and revokes refresh tokens")
    void redefinirSenha_codigoValido_atualizaSenhaERevogaSessoes() {
        Usuario user = new Usuario();
        user.setId(id(8));
        user.setEmail("reset@example.com");

        TokenEntity resetToken = new TokenEntity();
        resetToken.setId(id(30));
        resetToken.setUsuarioId(id(8));
        resetToken.setTipo(TokenEntity.Tipo.reset_senha);
        resetToken.setRevogado(false);
        resetToken.setExpiraEm(Instant.now().plusSeconds(600));

        TokenEntity refreshAtivo = new TokenEntity();
        refreshAtivo.setId(id(31));
        refreshAtivo.setRevogado(false);

        when(tokens.findByHashAndTipo(anyString(), eq(TokenEntity.Tipo.reset_senha)))
                .thenReturn(Optional.of(resetToken));
        when(usuarios.findById(id(8))).thenReturn(Optional.of(user));
        when(encoder.encode("Nova#Senha1")).thenReturn("hash-nova");
        when(tokens.findByUsuarioIdAndTipoAndRevogadoFalse(id(8), TokenEntity.Tipo.refresh))
                .thenReturn(List.of(refreshAtivo));

        authService.redefinirSenha("reset@example.com", "123456", "Nova#Senha1");

        assertThat(user.getSenhaHash()).isEqualTo("hash-nova");
        assertThat(resetToken.isRevogado()).isTrue();
        assertThat(refreshAtivo.isRevogado()).isTrue();
    }

    @Test
    @DisplayName("redefinirSenha with an unknown code throws the generic credential error")
    void redefinirSenha_codigoDesconhecido_throwsCredencialInvalida() {
        when(tokens.findByHashAndTipo(anyString(), eq(TokenEntity.Tipo.reset_senha)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.redefinirSenha("reset@example.com", "000000", "Nova#Senha1"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Credencial invalida");
    }

    @Test
    @DisplayName("redefinirSenha with an expired code throws the generic credential error")
    void redefinirSenha_codigoExpirado_throwsCredencialInvalida() {
        TokenEntity resetToken = new TokenEntity();
        resetToken.setId(id(30));
        resetToken.setUsuarioId(id(8));
        resetToken.setTipo(TokenEntity.Tipo.reset_senha);
        resetToken.setRevogado(false);
        resetToken.setExpiraEm(Instant.now().minusSeconds(1));

        when(tokens.findByHashAndTipo(anyString(), eq(TokenEntity.Tipo.reset_senha)))
                .thenReturn(Optional.of(resetToken));

        assertThatThrownBy(() -> authService.redefinirSenha("reset@example.com", "123456", "Nova#Senha1"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Credencial invalida");
    }

    @Test
    @DisplayName("redefinirSenha with an email that doesn't match the code's owner throws the generic credential error")
    void redefinirSenha_emailNaoCorresponde_throwsCredencialInvalida() {
        Usuario user = new Usuario();
        user.setId(id(8));
        user.setEmail("dono-real@example.com");

        TokenEntity resetToken = new TokenEntity();
        resetToken.setId(id(30));
        resetToken.setUsuarioId(id(8));
        resetToken.setTipo(TokenEntity.Tipo.reset_senha);
        resetToken.setRevogado(false);
        resetToken.setExpiraEm(Instant.now().plusSeconds(600));

        when(tokens.findByHashAndTipo(anyString(), eq(TokenEntity.Tipo.reset_senha)))
                .thenReturn(Optional.of(resetToken));
        when(usuarios.findById(id(8))).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.redefinirSenha("outro@example.com", "123456", "Nova#Senha1"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Credencial invalida");
    }

    @Test
    @DisplayName("redefinirSenha rejects a weak new password before even checking the code")
    void redefinirSenha_senhaFraca_throwsIllegalArgument() {
        assertThatThrownBy(() -> authService.redefinirSenha("reset@example.com", "123456", "abc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("esqueciSenha publishes an envelope carrying tipoComunicacao=conta")
    @SuppressWarnings("unchecked")
    void esqueciSenha_envelopeCarregaTipoComunicacaoConta() {
        Usuario user = new Usuario();
        user.setId(id(6));
        user.setEmail("ativo@example.com");
        user.setStatus(Usuario.Status.ativo);

        when(usuarios.findByEmail("ativo@example.com")).thenReturn(Optional.of(user));
        when(tokens.findByUsuarioIdAndTipoAndRevogadoFalse(id(6), TokenEntity.Tipo.reset_senha))
                .thenReturn(List.of());
        when(tokens.save(any(TokenEntity.class))).thenAnswer(inv -> {
            TokenEntity t = inv.getArgument(0);
            t.setId(id(20));
            return t;
        });

        authService.esqueciSenha("ativo@example.com");

        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(outbox).publish(eq(br.com.vistoria.core.events.EventTypes.PASSWORD_RESET_REQUESTED), captor.capture());
        assertThat(captor.getValue()).containsEntry("tipoComunicacao", "conta");
    }

    @Test
    @DisplayName("reenviarConvite publishes an invite envelope carrying tipoComunicacao=conta")
    @SuppressWarnings("unchecked")
    void reenviarConvite_envelopeCarregaTipoComunicacaoConta() {
        Usuario user = new Usuario();
        user.setId(id(3));
        user.setEmail("pendente@example.com");
        user.setNome("Pendente");
        user.setStatus(Usuario.Status.convidado);

        when(usuarios.findById(id(3))).thenReturn(Optional.of(user));
        when(tokens.findByUsuarioIdAndTipoAndRevogadoFalse(id(3), TokenEntity.Tipo.invite))
                .thenReturn(List.of());
        when(tokens.save(any(TokenEntity.class))).thenAnswer(inv -> {
            TokenEntity t = inv.getArgument(0);
            t.setId(id(11));
            return t;
        });

        authService.reenviarConvite(id(3));

        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(outbox).publish(eq(br.com.vistoria.core.events.EventTypes.USER_INVITED), captor.capture());
        assertThat(captor.getValue()).containsEntry("tipoComunicacao", "conta");
    }


    @org.junit.jupiter.api.AfterEach
    void clearTenantContext() {
        br.com.vistoria.core.config.TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("atualizarUsuario to inativo revokes pending invite tokens and can rename")
    void atualizarUsuario_inativo_revogaConviteEAtualizaNome() {
        var authentication = new UsernamePasswordAuthenticationToken(
                "admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        Usuario user = new Usuario();
        user.setId(id(3));
        user.setNome("Pendente");
        user.setPerfil(Usuario.Perfil.imob_gerencia);
        user.setStatus(Usuario.Status.convidado);

        TokenEntity invite = new TokenEntity();
        invite.setRevogado(false);

        when(usuarios.findById(id(3))).thenReturn(Optional.of(user));
        when(tokens.findByUsuarioIdAndTipoAndRevogadoFalse(id(3), TokenEntity.Tipo.refresh))
                .thenReturn(List.of());
        when(tokens.findByUsuarioIdAndTipoAndRevogadoFalse(id(3), TokenEntity.Tipo.invite))
                .thenReturn(List.of(invite));

        authService.atualizarUsuario(id(3), Usuario.Status.inativo, null, "Maria Santos", null);

        assertThat(user.getStatus()).isEqualTo(Usuario.Status.inativo);
        assertThat(user.getNome()).isEqualTo("Maria Santos");
        assertThat(invite.isRevogado()).isTrue();
    }

    @Test
    @DisplayName("atualizarUsuario cannot activate a user who never accepted the invite")
    void atualizarUsuario_ativoSemSenha_throwsIllegalState() {
        var authentication = new UsernamePasswordAuthenticationToken(
                "admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        Usuario user = new Usuario();
        user.setId(id(3));
        user.setPerfil(Usuario.Perfil.imob_gerencia);
        user.setStatus(Usuario.Status.inativo);

        when(usuarios.findById(id(3))).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.atualizarUsuario(id(3), Usuario.Status.ativo, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nao ativou o convite");
    }

    @Test
    @DisplayName("listUsuarios: sem tenant no contexto (ADMIN/SUPORTE) vê todos os perfis")
    void listUsuarios_semTenant_admin_vazioOuFiltradoPorPerfil() {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.Pageable.unpaged();
        var pagina = new org.springframework.data.domain.PageImpl<Usuario>(List.of());
        when(usuarios.search(false, Usuario.Perfil.admin, false, Usuario.Status.ativo, false, "", pageable))
                .thenReturn(pagina);

        var resultado = authService.listUsuarios(pageable, null, null, null);

        assertThat(resultado).isSameAs(pagina);
        verify(usuarios).search(false, Usuario.Perfil.admin, false, Usuario.Status.ativo, false, "", pageable);
    }

    @Test
    @DisplayName("listUsuarios: sem tenant, com perfil informado, filtra por esse perfil")
    void listUsuarios_semTenant_comFiltroDePerfil() {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.Pageable.unpaged();
        var pagina = new org.springframework.data.domain.PageImpl<Usuario>(List.of());
        when(usuarios.search(true, Usuario.Perfil.admin, false, Usuario.Status.ativo, false, "", pageable))
                .thenReturn(pagina);

        var resultado = authService.listUsuarios(pageable, Usuario.Perfil.admin, null, null);

        assertThat(resultado).isSameAs(pagina);
    }

    @Test
    @DisplayName("listUsuarios: sem tenant, q em branco vira null e status vai para o repositório")
    void listUsuarios_semTenant_repassaQeStatus() {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(1, 20);
        var pagina = new org.springframework.data.domain.PageImpl<Usuario>(List.of());
        when(usuarios.search(false, Usuario.Perfil.admin, true, Usuario.Status.ativo, true, "%maria%", pageable))
                .thenReturn(pagina);

        var resultado = authService.listUsuarios(pageable, null, Usuario.Status.ativo, " Maria ");

        assertThat(resultado).isSameAs(pagina);
        verify(usuarios).search(false, Usuario.Perfil.admin, true, Usuario.Status.ativo, true, "%maria%", pageable);
    }

    @Test
    @DisplayName("listUsuarios: IMOB_GERENCIA (tenant setado) só vê gerência/operação da própria imobiliária")
    void listUsuarios_comTenant_gerencia_escopoDaEquipe() {
        UUID tenant = id(77);
        br.com.vistoria.core.config.TenantContext.set(tenant);
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.Pageable.unpaged();
        var pagina = new org.springframework.data.domain.PageImpl<Usuario>(List.of());
        when(usuarios.searchByTenantsAndPerfilIn(
                List.of(tenant), List.of(Usuario.Perfil.imob_gerencia, Usuario.Perfil.imob_operacao),
                false, Usuario.Status.ativo, false, "", pageable))
                .thenReturn(pagina);

        var resultado = authService.listUsuarios(pageable, null, null, null);

        assertThat(resultado).isSameAs(pagina);
    }

    @Test
    @DisplayName("listUsuarios: IMOB_GERENCIA repassa q e status no recorte da equipe")
    void listUsuarios_comTenant_repassaQeStatus() {
        UUID tenant = id(77);
        br.com.vistoria.core.config.TenantContext.set(tenant);
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        var pagina = new org.springframework.data.domain.PageImpl<Usuario>(List.of());
        when(usuarios.searchByTenantsAndPerfilIn(
                List.of(tenant), List.of(Usuario.Perfil.imob_gerencia, Usuario.Perfil.imob_operacao),
                true, Usuario.Status.convidado, true, "%ana%", pageable))
                .thenReturn(pagina);

        var resultado = authService.listUsuarios(pageable, null, Usuario.Status.convidado, "Ana");

        assertThat(resultado).isSameAs(pagina);
    }

    @Test
    @DisplayName("listUsuarios: IMOB_GERENCIA pedindo perfil=admin não vaza dados de outro escopo (página vazia)")
    void listUsuarios_comTenant_gerencia_perfilForaDoEscopo_retornaVazio() {
        br.com.vistoria.core.config.TenantContext.set(id(77));
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.Pageable.unpaged();

        var resultado = authService.listUsuarios(pageable, Usuario.Perfil.admin, null, null);

        assertThat(resultado.getContent()).isEmpty();
        verify(usuarios, org.mockito.Mockito.never())
                .searchByTenantsAndPerfilIn(any(), any(), any(Boolean.class), any(), any(Boolean.class), any(), any());
    }
}
