package br.com.vistoria.core.modules.financeiro;

import br.com.vistoria.core.modules.solicitacao.CheckoutDisponibilidadeGuard;
import br.com.vistoria.core.modules.solicitacao.Solicitacao;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoPosPagaException;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Pagamentos", description = "Criação de cobranças de pagamento")
public class PagamentoController {

    private final PagamentoService service;
    private final CheckoutDisponibilidadeGuard disponibilidadeGuard;
    private final SolicitacaoService solicitacaoService;

    @PostMapping("/v1/solicitacoes/{id}/pagamento")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','CLIENTE')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Criar cobrança para solicitação", description = "Cria uma cobrança (charge) via provider de pagamento. Publica evento PAYMENT_REQUESTED para inspecta-worker processar.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Cobrança criada com sucesso"),
            @ApiResponse(responseCode = "400", description = "Solicitação não encontrada ou já paga"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão ou é outro cliente"),
            @ApiResponse(responseCode = "409", description = "SEM_VISTORIADOR: nenhum vistoriador elegível para a janela; pagamento não iniciado"),
            @ApiResponse(responseCode = "410", description = "SOLICITACAO_POS_PAGA: solicitação com imobiliária é pós-paga via fatura consolidada, não usa cobrança individual")
    })
    @SecurityRequirement(name = "bearerAuth")
    public PagamentoService.Cobranca criarCobranca(@PathVariable UUID id) {
        // Fluxo B2B: solicitação com imobiliária é pós-paga (fatura consolidada). Recusa
        // com 410 SOLICITACAO_POS_PAGA ANTES do guard de disponibilidade — o guard não roda.
        Solicitacao s = solicitacaoService.get(id);
        if (s.getImobiliariaId() != null) {
            throw new SolicitacaoPosPagaException(id);
        }
        // V3 (decisão 4): sem vistoriador elegível para algum serviço pendente => 409 e o
        // pagamento nem começa. A atribuição em si continua só pós-pagamento.
        disponibilidadeGuard.exigirVistoriadorDisponivel(id);
        return service.criarCobranca(id);
    }

    @PostMapping("/v1/solicitacoes/{id}/pagamento/pix")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','CLIENTE')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Gerar PIX transparente (QR Code) para a solicitação",
            description = "Checkout transparente do provedor: a Inspecta exibe o QR Code/copia-e-cola na própria página. "
                    + "Assíncrono — responde 202 com a cobrança no estado atual; consulte GET /pagamento até "
                    + "pixBrCode vir preenchido. Reaproveita o PIX vigente; gera outro se expirou.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "PIX solicitado (ou já vigente)"),
            @ApiResponse(responseCode = "409", description = "SEM_VISTORIADOR: nenhum vistoriador elegível para a janela"),
            @ApiResponse(responseCode = "410", description = "SOLICITACAO_POS_PAGA: B2B é pós-pago via fatura consolidada")
    })
    @SecurityRequirement(name = "bearerAuth")
    public PagamentoService.Cobranca criarPix(@PathVariable UUID id) {
        Solicitacao s = solicitacaoService.get(id);
        if (s.getImobiliariaId() != null) {
            throw new SolicitacaoPosPagaException(id);
        }
        disponibilidadeGuard.exigirVistoriadorDisponivel(id);
        return service.solicitarPix(id);
    }

    @GetMapping("/v1/solicitacoes/{id}/pagamento")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','CLIENTE')")
    @Operation(summary = "Consultar cobrança de solicitação", description = "Retorna o status de cobrança para uma solicitação.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status de cobrança retornado"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão"),
            @ApiResponse(responseCode = "404", description = "Solicitação ou cobrança não encontrada")
    })
    @SecurityRequirement(name = "bearerAuth")
    public PagamentoService.Cobranca consultar(@PathVariable UUID id) {
        return service.consultar(id);
    }

    // O webhook de pagamento (POST /v1/webhooks/pagamento/{provider}) migrou para o inspecta-messenger —
    // borda pública única de webhook de terceiros (arquitetura.md §Auth). O inspecta-api não expõe mais
    // rota pública não-autenticada de webhook. O consumo do evento jobs.payment.webhook-received
    // no inspecta-worker permanece inalterado.
}
