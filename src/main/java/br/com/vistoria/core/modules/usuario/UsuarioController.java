package br.com.vistoria.core.modules.usuario;

import br.com.vistoria.core.auth.AuthService;
import br.com.vistoria.core.modules.usuario.dto.UsuarioResponse;
import br.com.vistoria.core.shared.exception.NotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/usuarios")
@RequiredArgsConstructor
@Tag(name = "Usuários", description = "Gerenciamento de usuários com convites (T2.3 - RBAC)")
public class UsuarioController {

    private final AuthService authService;
    private final UsuarioRepository usuarios;

    public record InviteRequest(@Email @NotBlank String email, @NotBlank String nome,
                                @NotNull Usuario.Perfil perfil, UUID imobiliariaId,
                                /** V25: vínculos de imobiliária. Se ausente, usa `imobiliariaId` (compat). */
                                java.util.List<UUID> imobiliariaIds) {
        public InviteRequest(String email, String nome, Usuario.Perfil perfil, UUID imobiliariaId) {
            this(email, nome, perfil, imobiliariaId, null);
        }
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Convidar usuário", description = "Envia um convite por email para um novo usuário. Apenas ADMIN e SUPORTE podem convidar. Email é enviado via inspecta-messenger (q.notify). SUPORTE não pode convidar perfil=admin (T2.3). Perfil deve ser um valor válido (ADMIN, SUPORTE, IMOB_GERENCIA, etc).")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Convite enviado com sucesso, email agendado"),
            @ApiResponse(responseCode = "400", description = "Email inválido, perfil inválido ou dados ausentes"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão para convidar, ou SUPORTE tentando convidar perfil=admin (T2.3)"),
            @ApiResponse(responseCode = "409", description = "Usuário já existe com este email")
    })
    @SecurityRequirement(name = "bearerAuth")
    public void invite(@Valid @org.springframework.web.bind.annotation.RequestBody InviteRequest body) {
        java.util.List<UUID> ids = body.imobiliariaIds() != null && !body.imobiliariaIds().isEmpty()
                ? body.imobiliariaIds()
                : (body.imobiliariaId() != null ? java.util.List.of(body.imobiliariaId()) : java.util.List.of());
        authService.invite(body.email(), body.nome(), body.perfil(), ids);
    }

    @GetMapping("/{id}/imobiliarias")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA')")
    @Operation(summary = "Imobiliárias vinculadas a um usuário (V25)")
    @SecurityRequirement(name = "bearerAuth")
    public java.util.List<UUID> vinculos(@PathVariable UUID id) {
        return authService.vinculosDe(id);
    }

    public record UsuarioPatch(Usuario.Status status, Usuario.Perfil perfil, String nome, UUID imobiliariaId) {}

    public record MePatch(@NotNull Usuario.Tema tema) {}

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Atualizar status/perfil de um usuário")
    @SecurityRequirement(name = "bearerAuth")
    public void patch(@PathVariable UUID id, @org.springframework.web.bind.annotation.RequestBody UsuarioPatch body) {
        authService.atualizarUsuario(id, body.status(), body.perfil(), body.nome(), body.imobiliariaId());
    }

    @PostMapping("/{id}/imobiliarias/{imobiliariaId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Vincular usuário a uma imobiliária (V25)")
    @SecurityRequirement(name = "bearerAuth")
    public void adicionarVinculo(@PathVariable UUID id, @PathVariable UUID imobiliariaId) {
        authService.adicionarVinculo(id, imobiliariaId);
    }

    @DeleteMapping("/{id}/imobiliarias/{imobiliariaId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remover vínculo usuário ↔ imobiliária (V25)")
    @SecurityRequirement(name = "bearerAuth")
    public void removerVinculo(@PathVariable UUID id, @PathVariable UUID imobiliariaId) {
        authService.removerVinculo(id, imobiliariaId);
    }

    @PostMapping("/{id}/reenviar-convite")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Reenviar convite", description = "Reenvia o email de convite para um usuário com status=convidado. Revoga o link de convite anterior e emite um novo (T2.3). Apenas ADMIN e SUPORTE podem reenviar. Email é enviado via inspecta-messenger (q.notify).")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Convite reenviado com sucesso, email agendado"),
            @ApiResponse(responseCode = "400", description = "Usuário não possui convite pendente (status diferente de convidado)"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão para reenviar convite"),
            @ApiResponse(responseCode = "404", description = "Usuário não encontrado")
    })
    @SecurityRequirement(name = "bearerAuth")
    public void reenviarConvite(@PathVariable UUID id) {
        authService.reenviarConvite(id);
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Usuário autenticado atual",
            description = "Retorna o usuário correspondente ao token da requisição. Qualquer usuário autenticado pode chamar. Usado pela SPA para reidratar a identidade (nome/email) após um refresh de sessão via cookie, já que o JWT não carrega esses campos.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Usuário autenticado retornado com sucesso"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "404", description = "Token válido mas o usuário não existe mais")
    })
    @SecurityRequirement(name = "bearerAuth")
    public UsuarioResponse me(@AuthenticationPrincipal UUID userId) {
        return usuarios.findById(userId)
                .map(UsuarioResponse::from)
                .orElseThrow(() -> new NotFoundException("Usuario " + userId));
    }

    @PatchMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Atualizar preferências do usuário autenticado",
            description = "Persiste o tema (light/dark) na conta. Qualquer usuário autenticado pode chamar — só altera o próprio registro.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Preferências atualizadas"),
            @ApiResponse(responseCode = "400", description = "Tema inválido"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "404", description = "Token válido mas o usuário não existe mais")
    })
    @SecurityRequirement(name = "bearerAuth")
    public UsuarioResponse atualizarMe(@AuthenticationPrincipal UUID userId,
                                       @Valid @org.springframework.web.bind.annotation.RequestBody MePatch body) {
        Usuario u = usuarios.findById(userId)
                .orElseThrow(() -> new NotFoundException("Usuario " + userId));
        u.setTema(body.tema());
        return UsuarioResponse.from(usuarios.save(u));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA')")
    @Operation(summary = "Listar usuários", description = "Lista usuários do sistema com paginação e filtros opcionais "
            + "(perfil, status, q=nome/e-mail). ADMIN/SUPORTE veem todos os perfis; IMOB_GERENCIA vê apenas "
            + "gerência/operação de sua própria imobiliária (tenant-scoped, T2.3).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista de usuários retornada com sucesso"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão para listar")
    })
    @SecurityRequirement(name = "bearerAuth")
    public Page<UsuarioResponse> list(Pageable pageable,
                                      @RequestParam(required = false) Usuario.Perfil perfil,
                                      @RequestParam(required = false) Usuario.Status status,
                                      @RequestParam(required = false) String q) {
        return authService.listUsuarios(pageable, perfil, status, q).map(UsuarioResponse::from);
    }
}
