package br.com.vistoria.core.auth;

import br.com.vistoria.core.auth.dto.TokenResponse;
import br.com.vistoria.core.config.TenantContext;
import br.com.vistoria.core.events.EventTypes;
import br.com.vistoria.core.modules.disponibilidade.DisponibilidadeRepositories.VistoriadorRepository;
import br.com.vistoria.core.modules.disponibilidade.Vistoriador;
import br.com.vistoria.core.modules.notify.NotifyTemplates;
import br.com.vistoria.core.modules.usuario.Usuario;
import br.com.vistoria.core.modules.usuario.UsuarioImobiliaria;
import br.com.vistoria.core.modules.usuario.UsuarioImobiliariaRepository;
import br.com.vistoria.core.modules.usuario.UsuarioRepository;
import br.com.vistoria.core.shared.exception.NotFoundException;
import br.com.vistoria.core.shared.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final JwtService jwtService;
    private final UsuarioRepository usuarios;
    private final UsuarioImobiliariaRepository usuarioImobiliarias;
    private final VistoriadorRepository vistoriadores;
    private final TokenRepository tokens;
    private final PasswordEncoder encoder;
    private final OutboxService outbox;

    // Domínio único global do SPA (inspecta-web). O link de convite é montado como
    // appUrl + "/convite?token=" — a rota /convite é servida pelo próprio inspecta-web.
    @Value("${inspecta.app-url}")
    private String appUrl;

    public record LoginResult(TokenResponse access, String refreshRaw) {}

    @Transactional
    public LoginResult login(String email, String senha) {
        Usuario u = usuarios.findByEmail(email)
                .filter(x -> x.getStatus() == Usuario.Status.ativo)
                .filter(x -> x.getSenhaHash() != null && encoder.matches(senha, x.getSenhaHash()))
                .orElseThrow(() -> new BadCredentialsException("Credenciais invalidas"));

        String access = jwtService.issueAccessToken(u.getId(), u.getPerfil().name(), u.getImobiliariaId(),
                vinculos(u));
        String refreshRaw = issueRefresh(u.getId());
        return new LoginResult(new TokenResponse(access, jwtService.accessTtlSeconds()), refreshRaw);
    }

    @Transactional
    public LoginResult refresh(String refreshRaw) {
        String hash = TokenHasher.sha256(refreshRaw);
        TokenEntity t = tokens.findByHashAndTipo(hash, TokenEntity.Tipo.refresh)
                .orElseThrow(() -> new BadCredentialsException("Refresh invalido"));

        // Recusa este token. Não revoga as outras sessões: um refresh expirado ou
        // reapresentado na corrida entre abas (bootstrap + 401 ao criar cidade)
        // não pode deslogar o usuário em todo lugar. Logout, troca de senha e
        // desativação continuam derrubando a família inteira.
        if (t.isRevogado() || t.getExpiraEm().isBefore(Instant.now())) {
            throw new BadCredentialsException("Refresh invalido");
        }
        Usuario u = usuarios.findById(t.getUsuarioId()).orElseThrow();

        // deactivated users cannot mint new access tokens via refresh
        if (u.getStatus() != Usuario.Status.ativo) {
            tokens.findByUsuarioIdAndTipoAndRevogadoFalse(u.getId(), TokenEntity.Tipo.refresh)
                    .forEach(x -> x.setRevogado(true));
            throw new BadCredentialsException("Usuario inativo");
        }

        // Não rotaciona o refresh: dois POSTs /refresh com o mesmo cookie (duas
        // abas, StrictMode, invalidateQueries depois de criar cidade) precisam
        // ambos vencer. Access novo, mesmo refresh até expirar ou logout.
        String access = jwtService.issueAccessToken(u.getId(), u.getPerfil().name(), u.getImobiliariaId(),
                vinculos(u));
        return new LoginResult(new TokenResponse(access, jwtService.accessTtlSeconds()), refreshRaw);
    }

    @Transactional
    public void logout(String refreshRaw) {
        String hash = TokenHasher.sha256(refreshRaw);
        tokens.findByHashAndTipo(hash, TokenEntity.Tipo.refresh).ifPresent(x -> x.setRevogado(true));
    }

    /** Vínculos de imobiliária do usuário (V25); fallback para o escalar legado. */
    private java.util.List<UUID> vinculos(Usuario u) {
        var ids = usuarioImobiliarias.findImobiliariaIdsByUsuarioId(u.getId());
        if (!ids.isEmpty()) return ids;
        return u.getImobiliariaId() != null ? java.util.List.of(u.getImobiliariaId()) : java.util.List.of();
    }

    @Transactional
    public void invite(String email, String nome, Usuario.Perfil perfil, java.util.List<UUID> imobiliariaIds) {
        if (usuarios.existsByEmail(email)) throw new IllegalArgumentException("Email ja cadastrado");

        // T2.3(a): suporte cannot invite admin
        boolean isSupporte = SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                .stream().map(GrantedAuthority::getAuthority).anyMatch(a -> a.equals("ROLE_SUPORTE"));
        if (isSupporte && perfil == Usuario.Perfil.admin)
            throw new org.springframework.security.access.AccessDeniedException("Suporte nao pode convidar perfil admin");

        java.util.List<UUID> ids = imobiliariaIds == null ? java.util.List.of()
                : imobiliariaIds.stream().filter(java.util.Objects::nonNull).distinct().toList();

        Usuario u = new Usuario();
        u.setEmail(email);
        u.setNome(nome);
        u.setPerfil(perfil);
        u.setImobiliariaId(ids.isEmpty() ? null : ids.get(0)); // primária = 1ª
        u = usuarios.saveAndFlush(u);

        for (UUID imobId : ids) {
            usuarioImobiliarias.save(new UsuarioImobiliaria(u.getId(), imobId));
        }

        // P1: perfil vistoriador precisa de uma linha em `vistoriadores` para receber
        // atribuição e espelhar para a Pleno. Cria já inativa até o admin configurar praças.
        if (perfil == Usuario.Perfil.vistoriador && vistoriadores.findByUsuarioId(u.getId()).isEmpty()) {
            Vistoriador v = new Vistoriador();
            v.setUsuarioId(u.getId());
            v.setAtivo(false);
            vistoriadores.save(v);
        }

        issueInviteTokenAndDispatch(u);
    }

    @Transactional(readOnly = true)
    public java.util.List<UUID> vinculosDe(UUID usuarioId) {
        return usuarioImobiliarias.findImobiliariaIdsByUsuarioId(usuarioId);
    }

    /** Perfis que compõem a "equipe" de uma imobiliária, na tela de Usuários (Clientes). */
    private static final java.util.List<Usuario.Perfil> PERFIS_EQUIPE_AGENCIA =
            java.util.List.of(Usuario.Perfil.imob_gerencia, Usuario.Perfil.imob_operacao);

    /**
     * Lista de "Usuários" com regra de visibilidade por papel (tela unificada de Clientes ›
     * Usuários). Paginação e filtros ({@code perfil}, {@code status}, {@code q} em nome/e-mail)
     * acontecem no banco — filtrar em memória quebraria o {@code totalElements}.
     * <ul>
     *   <li>ADMIN/SUPORTE (sem tenant no contexto): veem todos os perfis; {@code perfilFiltro}
     *       opcional restringe a um só.</li>
     *   <li>IMOB_GERENCIA (tenant-scoped): vê só gerência/operação da(s) própria(s)
     *       imobiliária(s) — nunca admin/suporte, nem outra imobiliária. Um {@code perfilFiltro}
     *       fora desse conjunto devolve página vazia em vez de vazar dados de outro escopo.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public Page<Usuario> listUsuarios(Pageable pageable, Usuario.Perfil perfilFiltro,
                                      Usuario.Status statusFiltro, String q) {
        String busca = (q == null || q.isBlank()) ? null : q.trim().toLowerCase();
        boolean temQ = busca != null;
        String like = temQ ? "%" + busca + "%" : "";
        boolean temStatus = statusFiltro != null;
        java.util.List<UUID> tenants = TenantContext.getAll();
        if (tenants.isEmpty()) {
            boolean temPerfil = perfilFiltro != null;
            return usuarios.search(
                    temPerfil, temPerfil ? perfilFiltro : Usuario.Perfil.admin,
                    temStatus, temStatus ? statusFiltro : Usuario.Status.ativo,
                    temQ, like, pageable);
        }
        java.util.List<Usuario.Perfil> perfis = perfilFiltro == null
                ? PERFIS_EQUIPE_AGENCIA
                : (PERFIS_EQUIPE_AGENCIA.contains(perfilFiltro) ? java.util.List.of(perfilFiltro) : java.util.List.of());
        if (perfis.isEmpty()) return Page.empty(pageable);
        return usuarios.searchByTenantsAndPerfilIn(
                tenants, perfis,
                temStatus, temStatus ? statusFiltro : Usuario.Status.ativo,
                temQ, like, pageable);
    }

    /** Atualiza nome, status (ativar/desativar/excluir convite), perfil e/ou imobiliária primária. */
    @Transactional
    public void atualizarUsuario(UUID usuarioId, Usuario.Status status, Usuario.Perfil perfil, String nome,
                                UUID imobiliariaId) {
        Usuario u = usuarios.findById(usuarioId).orElseThrow(() -> new NotFoundException("Usuario " + usuarioId));
        boolean isSupporte = SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                .stream().map(GrantedAuthority::getAuthority).anyMatch(a -> a.equals("ROLE_SUPORTE"));
        if (isSupporte && (perfil == Usuario.Perfil.admin || u.getPerfil() == Usuario.Perfil.admin))
            throw new org.springframework.security.access.AccessDeniedException("Suporte nao gerencia perfil admin");
        if (nome != null && !nome.isBlank()) u.setNome(nome.trim());
        if (imobiliariaId != null) {
            u.setImobiliariaId(imobiliariaId);
            var atuais = usuarioImobiliarias.findImobiliariaIdsByUsuarioId(usuarioId);
            if (!atuais.contains(imobiliariaId)) {
                usuarioImobiliarias.save(new UsuarioImobiliaria(usuarioId, imobiliariaId));
            }
        }
        if (perfil != null) {
            UUID imobFinal = imobiliariaId != null ? imobiliariaId : u.getImobiliariaId();
            if ((perfil == Usuario.Perfil.imob_gerencia || perfil == Usuario.Perfil.imob_operacao)
                    && imobFinal == null)
                throw new IllegalStateException("Imobiliaria obrigatoria para este perfil");
            u.setPerfil(perfil);
        }
        if (status != null) {
            if (status == Usuario.Status.ativo && (u.getSenhaHash() == null || u.getSenhaHash().isBlank()))
                throw new IllegalStateException("Usuario ainda nao ativou o convite");
            u.setStatus(status);
            if (status != Usuario.Status.ativo) {
                tokens.findByUsuarioIdAndTipoAndRevogadoFalse(u.getId(), TokenEntity.Tipo.refresh)
                        .forEach(t -> t.setRevogado(true));
                tokens.findByUsuarioIdAndTipoAndRevogadoFalse(u.getId(), TokenEntity.Tipo.invite)
                        .forEach(t -> t.setRevogado(true));
            }
        }
    }

    /** Adiciona/remove um vínculo de imobiliária de um usuário já existente. */
    @Transactional
    public void adicionarVinculo(UUID usuarioId, UUID imobiliariaId) {
        usuarios.findById(usuarioId).orElseThrow(() -> new NotFoundException("Usuario " + usuarioId));
        usuarioImobiliarias.save(new UsuarioImobiliaria(usuarioId, imobiliariaId));
    }

    @Transactional
    public void removerVinculo(UUID usuarioId, UUID imobiliariaId) {
        usuarioImobiliarias.deleteByUsuarioIdAndImobiliariaId(usuarioId, imobiliariaId);
    }

    /**
     * Reenvia o convite de um usuario ainda pendente (status=convidado).
     *
     * <p>Revoga qualquer token de convite ainda ativo do usuario antes de emitir um novo,
     * garantindo que apenas o link mais recente seja valido (evita dois convites validos
     * simultaneamente para o mesmo usuario). Reutiliza o mesmo caminho de emissao de token +
     * inspecta-messenger de email usado por {@link #invite}.</p>
     */
    @Transactional
    public void reenviarConvite(UUID usuarioId) {
        Usuario u = usuarios.findById(usuarioId)
                .orElseThrow(() -> new NotFoundException("Usuario " + usuarioId));

        boolean semSenha = u.getSenhaHash() == null || u.getSenhaHash().isBlank();
        if (u.getStatus() == Usuario.Status.inativo && semSenha) {
            u.setStatus(Usuario.Status.convidado);
        }

        if (u.getStatus() != Usuario.Status.convidado)
            throw new IllegalStateException("Usuario nao possui convite pendente");

        tokens.findByUsuarioIdAndTipoAndRevogadoFalse(u.getId(), TokenEntity.Tipo.invite)
                .forEach(t -> t.setRevogado(true));

        issueInviteTokenAndDispatch(u);
    }

    @Transactional
    public void acceptInvite(String rawToken, String senha, String nome) {
        String hash = TokenHasher.sha256(rawToken);
        TokenEntity t = tokens.findByHashAndTipo(hash, TokenEntity.Tipo.invite)
                .orElseThrow(() -> new BadCredentialsException("Convite invalido"));
        if (t.isRevogado() || t.getExpiraEm().isBefore(Instant.now()))
            throw new BadCredentialsException("Convite expirado");

        PasswordPolicy.validar(senha);

        Usuario u = usuarios.findById(t.getUsuarioId()).orElseThrow();
        u.setSenhaHash(encoder.encode(senha));
        if (nome != null && !nome.isBlank()) u.setNome(nome);
        u.setStatus(Usuario.Status.ativo);
        t.setRevogado(true);
    }

    /**
     * Solicita codigo de recuperacao de senha. Sempre "sucesso" do ponto de vista do chamador
     * mesmo quando o email nao existe ou o usuario nao esta ativo — nao revela isso pra nao dar
     * um oraculo de enumeracao de contas (T-seguranca). Um codigo anterior ainda ativo e revogado
     * antes de emitir o novo, para so o mais recente valer.
     */
    @Transactional
    public void esqueciSenha(String email) {
        usuarios.findByEmail(email == null ? null : email.trim())
                .filter(u -> u.getStatus() == Usuario.Status.ativo)
                .ifPresent(u -> {
                    tokens.findByUsuarioIdAndTipoAndRevogadoFalse(u.getId(), TokenEntity.Tipo.reset_senha)
                            .forEach(t -> t.setRevogado(true));

                    String codigo = TokenHasher.newNumericCode(6);
                    TokenEntity t = new TokenEntity();
                    t.setUsuarioId(u.getId());
                    t.setTipo(TokenEntity.Tipo.reset_senha);
                    t.setHash(TokenHasher.sha256(codigo));
                    t.setExpiraEm(Instant.now().plus(Duration.ofMinutes(15)));
                    tokens.save(t);

                    outbox.publish(EventTypes.PASSWORD_RESET_REQUESTED, Map.of(
                            "eventId", "reset-" + u.getId() + "-" + t.getId(),
                            "canal", "email",
                            "tipoComunicacao", "conta",
                            "templateId", NotifyTemplates.PASSWORD_RESET_CODE,
                            "destinatario", u.getEmail(),
                            "variaveis", Map.of("codigo", codigo)));
                });
    }

    /**
     * Confirma o codigo enviado por email e define a nova senha. Toda falha (codigo errado,
     * expirado, ja usado, ou email que nao bate com o dono do codigo) cai na mesma excecao
     * generica — o codigo em si ja e a prova de posse do email, entao nao ha o que distinguir
     * pro chamador sem abrir uma enumeracao.
     */
    @Transactional
    public void redefinirSenha(String email, String codigo, String novaSenha) {
        PasswordPolicy.validar(novaSenha);

        // Codigo e email chegam de formularios/apps de email — espacos em volta (ou o
        // codigo colado com \n) fariam o hash nao bater e cairiam em "Credencial invalida"
        // mesmo com o codigo certo.
        String codigoLimpo = codigo == null ? "" : codigo.trim();
        String emailLimpo = email == null ? null : email.trim();

        String hash = TokenHasher.sha256(codigoLimpo);
        TokenEntity t = tokens.findByHashAndTipo(hash, TokenEntity.Tipo.reset_senha)
                .filter(x -> !x.isRevogado() && x.getExpiraEm().isAfter(Instant.now()))
                .orElseThrow(() -> new BadCredentialsException("Credencial invalida"));

        Usuario u = usuarios.findById(t.getUsuarioId()).orElseThrow();
        if (emailLimpo == null || !u.getEmail().equalsIgnoreCase(emailLimpo))
            throw new BadCredentialsException("Credencial invalida");

        u.setSenhaHash(encoder.encode(novaSenha));
        t.setRevogado(true);

        // Troca de senha derruba todas as sessoes ativas — refresh tokens existentes nao
        // devem sobreviver a uma senha comprometida sendo trocada.
        tokens.findByUsuarioIdAndTipoAndRevogadoFalse(u.getId(), TokenEntity.Tipo.refresh)
                .forEach(x -> x.setRevogado(true));
    }

    private void issueInviteTokenAndDispatch(Usuario u) {
        String raw = TokenHasher.newToken();
        TokenEntity t = new TokenEntity();
        t.setUsuarioId(u.getId());
        t.setTipo(TokenEntity.Tipo.invite);
        t.setHash(TokenHasher.sha256(raw));
        t.setExpiraEm(Instant.now().plus(Duration.ofHours(48)));
        tokens.save(t);

        outbox.publish(EventTypes.USER_INVITED, Map.of(
                "eventId", "invite-" + u.getId() + "-" + t.getId(),
                "canal", "email",
                "tipoComunicacao", "conta",
                "templateId", NotifyTemplates.USER_INVITED,
                "destinatario", u.getEmail(),
                "variaveis", Map.of("nome", u.getNome(), "link", appUrl + "/convite?token=" + raw)));
    }

    private String issueRefresh(UUID usuarioId) {
        String raw = TokenHasher.newToken();
        TokenEntity t = new TokenEntity();
        t.setUsuarioId(usuarioId);
        t.setTipo(TokenEntity.Tipo.refresh);
        t.setHash(TokenHasher.sha256(raw));
        t.setExpiraEm(Instant.now().plus(Duration.ofDays(7)));
        tokens.save(t);
        return raw;
    }
}
