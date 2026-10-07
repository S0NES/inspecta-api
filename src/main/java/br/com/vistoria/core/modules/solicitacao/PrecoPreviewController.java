package br.com.vistoria.core.modules.solicitacao;

import br.com.vistoria.core.modules.contrato.PrecoService;
import br.com.vistoria.core.shared.exception.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * V3 (tipagem/biletagem) — pré-visualização do preço de biletagem na criação da solicitação
 * B2B. Delega ao {@link PrecoService} do inspecta-core (mesma resolução usada na persistência).
 *
 * <p>200 {@code {valor, repasse, faixaId}} quando resolve; 422 {@code {codigo, mensagem}}
 * quando o contrato/serviço não está configurado. Solicitação avulsa (sem
 * {@code imobiliariaId}) NÃO tem preview — o preço é manual; nesse caso devolve 422
 * {@code AVULSA_SEM_PREVIEW}.</p>
 */
@RestController
@RequestMapping("/v1/solicitacoes")
@RequiredArgsConstructor
@Tag(name = "Solicitações — Preço", description = "Pré-visualização de biletagem (V3)")
public class PrecoPreviewController {

    private final PrecoService precoService;

    @GetMapping("/preco-preview")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA','IMOB_OPERACAO','CLIENTE')")
    @Operation(summary = "Pré-visualizar preço de biletagem de um serviço para uma imobiliária")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<?> preview(@RequestParam UUID imobiliariaId,
                                     @RequestParam UUID tipoServicoId,
                                     @RequestParam(required = false) BigDecimal metragem,
                                     @RequestParam(defaultValue = "false") boolean mobiliado,
                                     @RequestParam(required = false) BigDecimal valorAluguel) {
        // A imobiliária é sempre a do imóvel (o Wizard já a tem). Não existe mais "avulsa sem preview".
        try {
            PrecoService.Preco p = precoService.resolver(imobiliariaId, tipoServicoId, metragem, mobiliado, valorAluguel);
            return ResponseEntity.ok(new PrecoPreviewResponse(p.valor(), p.repasse(), p.faixaId()));
        } catch (NotFoundException e) {
            return unprocessable("CONTRATO_NAO_CONFIGURADO", e.getMessage());
        } catch (IllegalArgumentException e) {
            return unprocessable("PARAMETRO_INVALIDO", e.getMessage());
        }
    }

    public record PrecoPreviewResponse(BigDecimal valor, BigDecimal repasse, UUID faixaId) {}

    private static ResponseEntity<Map<String, String>> unprocessable(String codigo, String mensagem) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("codigo", codigo, "mensagem", mensagem == null ? "" : mensagem));
    }
}
