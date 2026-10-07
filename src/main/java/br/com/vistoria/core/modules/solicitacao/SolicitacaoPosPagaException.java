package br.com.vistoria.core.modules.solicitacao;

import java.util.UUID;

/**
 * Fluxo B2B (imobiliária): a solicitação é pós-paga — o valor é liquidado por fatura
 * consolidada ({@code ExtratoService}), não por cobrança individual. Qualquer tentativa
 * de iniciar/gerar cobrança avulsa em {@code POST /v1/solicitacoes/{id}/pagamento} é
 * recusada com 410 Gone e o contrato fechado {@code {codigo:"SOLICITACAO_POS_PAGA"}}.
 * O {@code CheckoutDisponibilidadeGuard} não roda nesse caso.
 */
public class SolicitacaoPosPagaException extends RuntimeException {

    private final UUID solicitacaoId;

    public SolicitacaoPosPagaException(UUID solicitacaoId) {
        super("Solicitacao " + solicitacaoId + " tem imobiliaria: e pos-paga via fatura consolidada, "
                + "nao usa cobranca individual");
        this.solicitacaoId = solicitacaoId;
    }

    public UUID getSolicitacaoId() {
        return solicitacaoId;
    }
}
