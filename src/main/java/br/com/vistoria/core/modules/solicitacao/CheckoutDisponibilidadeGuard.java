package br.com.vistoria.core.modules.solicitacao;

import br.com.vistoria.core.modules.cadastro.CadastroRepositories.ImovelRepository;
import br.com.vistoria.core.modules.cadastro.Imovel;
import br.com.vistoria.core.modules.disponibilidade.DisponibilidadeService;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.ServicoRepository;
import br.com.vistoria.core.shared.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * V3 (decisão de negócio 4) — antes de iniciar/confirmar o pagamento de uma solicitação, o
 * inspecta-api exige que exista ≥1 vistoriador elegível para CADA serviço ainda pendente, na janela
 * {@code dataDesejada} (quando informada). Se algum serviço não tiver cobertura, lança
 * {@link SemVistoriadorException} (→ 409) e o pagamento NÃO é iniciado.
 *
 * <p>A atribuição de vistoriador é publicada quando o PAGAMENTO É CONFIRMADO
 * ({@code PagamentoService.aplicarEvento} -> {@code SolicitacaoService.aplicarPagamentoConfirmado},
 * um {@code jobs.assignment.requested} por serviço ainda pendente); a elegibilidade real e o
 * retry vivem no inspecta-worker ({@code AssignmentService} + {@code fila_atribuicao}). Este guard
 * é só uma checagem de viabilidade no gatilho de cobrança — não bloqueia o create, não
 * reserva nem atribui nada.</p>
 */
@Component
@RequiredArgsConstructor
public class CheckoutDisponibilidadeGuard {

    /** Fallback quando o serviço não tem duração estimada configurada. */
    private static final int DURACAO_PADRAO_MIN = 60;

    private final SolicitacaoService solicitacaoService;
    private final ServicoRepository servicos;
    private final ImovelRepository imoveis;
    private final DisponibilidadeService disponibilidade;

    @Transactional(readOnly = true)
    public void exigirVistoriadorDisponivel(UUID solicitacaoId) {
        Solicitacao s = solicitacaoService.get(solicitacaoId);
        Imovel imovel = imoveis.findById(s.getImovelId())
                .orElseThrow(() -> new NotFoundException("Imovel " + s.getImovelId()));

        List<UUID> semCobertura = new ArrayList<>();
        for (SolicitacaoServico ss : servicos.findBySolicitacaoId(solicitacaoId)) {
            if (ss.getStatus() != SolicitacaoServico.Status.pendente) continue;
            int duracao = ss.getDuracaoEstimadaMin() != null ? ss.getDuracaoEstimadaMin() : DURACAO_PADRAO_MIN;
            boolean ok = disponibilidade.existeVistoriadorElegivel(
                    imovel.getCidadeId(), imovel.getLat(), imovel.getLng(),
                    duracao, s.isUrgente(), ss.getDataDesejada());
            if (!ok) semCobertura.add(ss.getTipoServicoId());
        }
        if (!semCobertura.isEmpty()) {
            throw new SemVistoriadorException(semCobertura);
        }
    }
}
