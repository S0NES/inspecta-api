package br.com.vistoria.core.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/api-keys")
@RequiredArgsConstructor
@Tag(name = "API Keys", description = "Gerenciamento de API keys para autenticação programática (T7.x)")
public class ApiKeyController {

    private final ApiKeyService service;

    public record CreateKeyRequest(UUID imobiliariaId, String[] escopos, Instant expiraEm) {}

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Listar API keys", description = "Metadados das chaves (máscara ••••1a2b, imobiliária, escopos, datas, revogada). Nunca retorna hash nem plaintext. Filtra por imobiliariaId quando informado.")
    @SecurityRequirement(name = "bearerAuth")
    public List<ApiKeyService.ApiKeyInfo> list(@RequestParam(required = false) UUID imobiliariaId) {
        return imobiliariaId == null ? service.list() : service.listByImobiliaria(imobiliariaId);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Criar nova API key", description = "Gera uma nova chave de API vinculada a uma imobiliária. Apenas ADMIN. O plaintext é exibido apenas uma vez; depois só a máscara ••••1a2b. Idempotente via header Idempotency-Key.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "API key criada com sucesso", content = @Content(schema = @Schema(implementation = Map.class))),
            @ApiResponse(responseCode = "400", description = "Dados inválidos"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão para criar API keys"),
            @ApiResponse(responseCode = "404", description = "Imobiliária não encontrada")
    })
    @SecurityRequirement(name = "bearerAuth")
    public Map<String, Object> create(
            @RequestBody(description = "Dados para criação de API key (imobiliariaId, escopos, expiraEm)") @org.springframework.web.bind.annotation.RequestBody CreateKeyRequest body) {
        var k = service.create(body.imobiliariaId(), body.escopos(), body.expiraEm());
        return Map.of("id", k.id(), "apiKey", k.plaintext(), "mascara", k.mascara(),
                "imobiliariaId", k.imobiliariaId(), "expiraEm", k.expiraEm(),
                "aviso", "Chave exibida apenas uma vez");
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revogar API key", description = "Revoga uma API key, impedindo seu uso futuro. Apenas ADMIN pode revogar.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "API key revogada com sucesso"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão para revogar"),
            @ApiResponse(responseCode = "404", description = "API key não encontrada")
    })
    @SecurityRequirement(name = "bearerAuth")
    public void revoke(@PathVariable UUID id) {
        service.revoke(id);
    }
}
