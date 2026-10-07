package br.com.vistoria.core.modules.clienteb2c;

import br.com.vistoria.core.modules.clienteb2c.dto.ClienteB2cResponse;
import br.com.vistoria.core.modules.usuario.Usuario;
import br.com.vistoria.core.shared.exception.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/clientes-b2c")
@RequiredArgsConstructor
@Tag(name = "Clientes B2C", description = "Cadastro e listagem de clientes B2C (perfil cliente, sem imobiliária)")
public class ClienteB2cController {

    private final ClienteB2cService service;
    private final ClienteB2cRepository clientesB2c;

    public record CreateRequest(@NotBlank String nome, @Email @NotBlank String email,
                                @NotBlank String cpf, @NotBlank String telefone,
                                @NotNull UUID cidadeId,
                                @NotEmpty List<UUID> clientServices) {}

    public record PatchRequest(String nome, String telefone, UUID cidadeId,
                               List<UUID> clientServices) {}

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Criar cliente B2C", description = "Convida (perfil=cliente) + persiste o cadastro. 409 se email já existe.")
    @SecurityRequirement(name = "bearerAuth")
    public ClienteB2cResponse criar(@Valid @RequestBody CreateRequest body) {
        ClienteB2c c = service.criarComConvite(new ClienteB2cService.CreateRequest(
                body.nome(), body.email(), body.cpf(), body.telefone(), body.cidadeId(), body.clientServices()));
        return ClienteB2cResponse.from(c, Usuario.Status.convidado, 0L);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Listar clientes B2C")
    @SecurityRequirement(name = "bearerAuth")
    public Page<ClienteB2cResponse> listar(Pageable pageable,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(required = false) UUID cidadeId,
                                           @RequestParam(required = false) Usuario.Status status) {
        return service.listar(pageable, q, cidadeId, status);
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('CLIENTE')")
    @Operation(summary = "Cliente B2C autenticado atual",
            description = "Usado pelo wizard de solicitação (Página 4) para travar a cidade do imóvel e filtrar os client_services habilitados.")
    @SecurityRequirement(name = "bearerAuth")
    public ClienteB2cResponse me(@AuthenticationPrincipal UUID userId) {
        ClienteB2c c = clientesB2c.findByUsuarioId(userId)
                .orElseThrow(() -> new NotFoundException("Cliente B2C do usuario " + userId));
        return ClienteB2cResponse.from(c, null, 0L);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Obter cliente B2C por id")
    @SecurityRequirement(name = "bearerAuth")
    public ClienteB2cResponse getOne(@PathVariable UUID id) {
        ClienteB2c c = clientesB2c.findById(id).orElseThrow(() -> new NotFoundException("Cliente " + id));
        return ClienteB2cResponse.from(c, null, 0L);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Atualizar cliente B2C (parcial)")
    @SecurityRequirement(name = "bearerAuth")
    public ClienteB2cResponse atualizar(@PathVariable UUID id, @RequestBody PatchRequest body) {
        ClienteB2c c = service.atualizar(id, new ClienteB2cService.PatchRequest(
                body.nome(), body.telefone(), body.cidadeId(), body.clientServices()));
        return ClienteB2cResponse.from(c, null, 0L);
    }

    @PostMapping("/{id}/inativar")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Inativar cliente B2C", description = "Inativa o usuário vinculado (mesma ação de 'inativar usuário').")
    @SecurityRequirement(name = "bearerAuth")
    public void inativar(@PathVariable UUID id) {
        service.inativar(id);
    }
}
