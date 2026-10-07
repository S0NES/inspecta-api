package br.com.vistoria.core.modules.assinatura;

import br.com.vistoria.core.modules.solicitacao.SolicitacaoInterveniente;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Assinaturas", description = "Orquestração de assinatura digital via Autentique")
public class AssinaturaController {

    private final AssinaturaService service;

    /**
     * Os signatários vêm SEMPRE de {@code solicitacao_intervenientes} (via
     * laudo → solicitacao_servico → solicitacao). Qualquer corpo enviado é ignorado; um laudo
     * sem interveniente devolve 400 ({@code sem_intervenientes}).
     */
    @PostMapping("/v1/documentos/{tipo}/{id}/assinar")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Solicitar assinatura de documento",
            description = "Envia um laudo para assinatura via Autentique. Os signatários são os "
                    + "intervenientes da solicitação (nome/e-mail já gravados); o corpo é ignorado.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Assinatura solicitada, signatários notificados"),
            @ApiResponse(responseCode = "400", description = "Tipo inválido ou solicitação sem interveniente"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão"),
            @ApiResponse(responseCode = "404", description = "Documento não encontrado")
    })
    @SecurityRequirement(name = "bearerAuth")
    public void assinar(@PathVariable String tipo, @PathVariable UUID id,
                        @RequestBody(required = false) List<Signatario> corpoIgnorado) {
        service.solicitarAssinatura(tipo, id, corpoIgnorado == null ? List.of() : corpoIgnorado);
    }

    public record AssinanteView(String nome, String papel, String email) {}

    @GetMapping("/v1/laudos/{id}/assinantes")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA','IMOB_OPERACAO','VISTORIADOR')")
    @Operation(summary = "Listar quem vai assinar um laudo",
            description = "Intervenientes da solicitação do laudo — o que a Autentique usa como signatários.")
    @SecurityRequirement(name = "bearerAuth")
    public List<AssinanteView> assinantes(@PathVariable UUID id) {
        return service.assinantesDoLaudo(id).stream()
                .map(p -> new AssinanteView(p.getNome(), p.getPapelSlug(), p.getEmail()))
                .toList();
    }
}
