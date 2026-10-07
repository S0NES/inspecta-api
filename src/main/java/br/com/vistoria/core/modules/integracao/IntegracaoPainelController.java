package br.com.vistoria.core.modules.integracao;

import br.com.vistoria.core.modules.assinatura.AssinaturaService;
import br.com.vistoria.core.modules.assinatura.Signatario;
import br.com.vistoria.core.modules.financeiro.FinanceiroRepositories.PagamentoRepository;
import br.com.vistoria.core.modules.financeiro.Pagamento;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Painel operacional de integrações além da Pleno: Autentique (signatários) e
 * AbacatePay (pagamentos). Leitura pura — sem mock, só o que já está gravado.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Integrações", description = "Painel de monitoramento Autentique e AbacatePay")
public class IntegracaoPainelController {

    private final AssinaturaService assinaturas;
    private final PagamentoRepository pagamentos;

    public record AutentiqueRow(
            UUID id,
            String nome,
            String email,
            String status,
            String externalDocumentId,
            String externalSignerId,
            Instant criadoEm,
            Instant assinadoEm,
            String documentoTipo,
            UUID documentoId
    ) {
        static AutentiqueRow of(Signatario s) {
            return new AutentiqueRow(s.getId(), s.getNome(), s.getEmail(), s.getStatusAssinatura(),
                    s.getExternalDocumentId(), s.getExternalSignerId(), s.getCriadoEm(),
                    s.getAssinadoEm(), s.getDocumentoTipo(), s.getDocumentoId());
        }
    }

    public record AbacatePayRow(
            UUID id,
            UUID solicitacaoId,
            String provider,
            String externalId,
            String status,
            BigDecimal valor,
            String checkoutUrl,
            Instant criadoEm
    ) {
        static AbacatePayRow of(Pagamento p) {
            return new AbacatePayRow(p.getId(), p.getSolicitacaoId(), p.getProvider(),
                    p.getExternalId(), p.getStatus(), p.getValor(), p.getCheckoutUrl(), p.getCriadoEm());
        }
    }

    @GetMapping("/v1/integracoes/autentique")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Painel Autentique — signatários reais")
    @SecurityRequirement(name = "bearerAuth")
    public Page<AutentiqueRow> autentique(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            Pageable pageable) {
        return assinaturas.listarPainel(status, q, pageable).map(AutentiqueRow::of);
    }

    @GetMapping("/v1/integracoes/abacatepay")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Painel AbacatePay — cobranças reais")
    @SecurityRequirement(name = "bearerAuth")
    public Page<AbacatePayRow> abacatepay(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            Pageable pageable) {
        boolean temStatus = status != null && !status.isBlank();
        String termo = q == null ? "" : q.trim().toLowerCase();
        boolean temQ = !termo.isBlank();
        return pagamentos.painel(temStatus, temStatus ? status : "",
                temQ, temQ ? "%" + termo + "%" : "", pageable).map(AbacatePayRow::of);
    }
}
