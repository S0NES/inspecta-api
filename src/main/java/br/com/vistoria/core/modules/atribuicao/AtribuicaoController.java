package br.com.vistoria.core.modules.atribuicao;

import br.com.vistoria.core.events.EventTypes;
import br.com.vistoria.core.shared.outbox.OutboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Atribuições", description = "Orquestração de atribuição e reatribuição de vistoriadores via evento (outbox pattern)")
public class AtribuicaoController {

    private final OutboxService outbox;

    @PostMapping("/v1/servicos/{id}/reatribuir")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    @Operation(summary = "Reatribuir serviço a novo vistoriador", description = "Publica evento de reatribuição (ASSIGNMENT_REQUESTED) via outbox para consumo por inspecta-worker. Motivo é obrigatório.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Reatribuição aceita, evento publicado"),
            @ApiResponse(responseCode = "400", description = "Motivo obrigatório ou servicoId inválido"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão")
    })
    @SecurityRequirement(name = "bearerAuth")
    public Map<String, Object> reatribuir(@PathVariable UUID id, @RequestBody(description = "Payload com motivo e vistoriadorAtual") @org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        String motivo = body.get("motivo");
        if (motivo == null || motivo.isBlank()) throw new IllegalArgumentException("Motivo obrigatorio");
        String atualRaw = body.get("vistoriadorAtual");
        UUID atual = atualRaw == null || atualRaw.isBlank() ? null : UUID.fromString(atualRaw);

        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("servicoId", id);
        payload.put("excluirVistoriadorId", atual);
        payload.put("motivo", motivo);
        payload.put("criadoPor", "usuario");
        outbox.publish(EventTypes.ASSIGNMENT_REQUESTED, payload);
        return Map.of("aceito", true, "servicoId", id);
    }
}
