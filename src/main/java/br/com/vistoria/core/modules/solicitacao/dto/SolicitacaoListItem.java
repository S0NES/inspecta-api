package br.com.vistoria.core.modules.solicitacao.dto;

/**
 * Read model de uma solicitação para as telas de listagem do inspecta-web.
 *
 * <p>A entidade {@code Solicitacao} guarda só as chaves (imovelId, clienteId, ...); montar o
 * endereço, o vistoriador e o valor exige juntar imóvel, serviços, tipo de serviço e
 * vistoriador. Fazer isso no cliente custaria uma request por linha — por isso o join mora
 * aqui e o front recebe os campos já prontos para exibir.</p>
 *
 * <p>{@code status} é o status derivado dos serviços (ver {@code SolicitacaoService.statusDerivado}),
 * traduzido para os rótulos que a UI usa.</p>
 */
public record SolicitacaoListItem(
        String id,
        java.util.UUID solicitacaoId,
        String endereco,
        String imagem,
        String tipo,
        String vistoriador,
        String data,
        String hora,
        String status,
        /** Preço cobrado formatado; {@code null} para o vistoriador (ver PrecoVisibilidadePolicy). */
        String valor,
        boolean urgente,
        /** Estado da cobrança (nao_iniciado / checkout_ready / pago / erro) — alimenta o CTA "Pagar" da lista/Kanban. */
        String paymentStatus,
        /** URL de checkout do provedor, quando já pronta; {@code null} enquanto o checkout não existe. */
        String checkoutUrl
) {}
