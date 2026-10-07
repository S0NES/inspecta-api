package br.com.vistoria.core.modules.contrato;

import br.com.vistoria.core.modules.cadastro.CadastroRepositories.FaixaReferenciaRepository;
import br.com.vistoria.core.modules.cadastro.FaixaReferencia;
import br.com.vistoria.core.modules.contrato.ContratoRepositories.ContratoRepository;
import br.com.vistoria.core.modules.contrato.ContratoRepositories.ContratoServicoGrupoRepository;
import br.com.vistoria.core.modules.contrato.ContratoRepositories.FaixaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * V3 (tipagem/biletagem) — leitura consolidada do contrato ativo de uma imobiliária,
 * usada tanto pelo detalhe da imobiliária ({@code GET /v1/imobiliarias/{id}}) quanto pelo
 * sub-recurso {@code /v1/imobiliarias/{id}/contrato}. Só leitura; escrita fica no
 * {@link br.com.vistoria.core.modules.imobiliaria.ImobiliariaContratoController}.
 */
@Service
@RequiredArgsConstructor
public class ContratoResumoService {

    private final ContratoRepository contratos;
    private final ContratoServicoGrupoRepository grupos;
    private final FaixaRepository faixas;
    private final FaixaReferenciaRepository faixaReferencia;

    public record GrupoView(UUID id, UUID servicoId, BigDecimal percentual, BigDecimal percentualMinimo) {}

    /**
     * V4 (fase 2): uma linha de {@code contrato_faixas} com {@code servico_id IS NOT NULL}.
     * {@code faixaId} = {@code faixa_referencia.id} (1..5), não o PK da linha. Linhas legadas
     * keyed por {@code tipo_servico_id} (servico_id null) NÃO aparecem aqui.
     */
    public record FaixaView(UUID servicoId, Short faixaId,
                            BigDecimal valorSemMobilia, BigDecimal valorComMobilia,
                            BigDecimal paStepMetragem, BigDecimal paStepValor) {}

    public record FaixaReferenciaView(Short id, Short ordem, BigDecimal metragemMin, BigDecimal metragemMax,
                                      String rotulo) {}

    public record ContratoResumo(UUID contratoId, String modo, BigDecimal percentual, BigDecimal percentualMinimo,
                                 List<GrupoView> grupos, List<FaixaView> faixas,
                                 List<FaixaReferenciaView> faixaReferencia) {}

    /**
     * Linhas de faixa (valores por Serviço guarda-chuva × faixa de referência) do contrato,
     * já filtradas para {@code servico_id IS NOT NULL} e ordenadas por (servicoId, faixaId).
     */
    @Transactional(readOnly = true)
    public List<FaixaView> faixasDoContrato(UUID contratoId) {
        return faixas.findByContratoIdOrderByTipoServicoIdAscMetragemMinAsc(contratoId).stream()
                .filter(f -> f.getServicoId() != null)
                .sorted(Comparator.comparing(ContratoFaixa::getServicoId)
                        .thenComparing(f -> f.getFaixaReferenciaId() == null ? Short.MAX_VALUE : f.getFaixaReferenciaId()))
                .map(f -> new FaixaView(f.getServicoId(), f.getFaixaReferenciaId(),
                        f.getValorSemMob(), f.getValorComMob(), f.getPaStepMetragem(), f.getPaStepValor()))
                .toList();
    }

    /** Catálogo global e fixo das 5 faixas de metragem de referência (sem valores). */
    @Transactional(readOnly = true)
    public List<FaixaReferenciaView> catalogoFaixas() {
        return faixaReferencia.findAllByOrderByOrdemAsc().stream()
                .map(f -> new FaixaReferenciaView(f.getId(), f.getOrdem(), f.getMetragemMin(),
                        f.getMetragemMax(), f.getRotulo()))
                .toList();
    }

    /** Resumo do contrato ativo, ou {@code null} se a imobiliária ainda não tem contrato ativo. */
    @Transactional(readOnly = true)
    public ContratoResumo resumoOuNull(UUID imobiliariaId) {
        return contratos.findFirstByImobiliariaIdAndStatus(imobiliariaId, "ativo")
                .map(this::montar)
                .orElse(null);
    }

    private ContratoResumo montar(Contrato c) {
        List<GrupoView> gs = grupos.findByContratoId(c.getId()).stream()
                .map(g -> new GrupoView(g.getId(), g.getServicoId(), g.getPercentual(), g.getPercentualMinimo()))
                .toList();
        List<FaixaView> fs = faixasDoContrato(c.getId());
        return new ContratoResumo(c.getId(),
                c.getModo() == null ? null : c.getModo().name(),
                c.getPercentual(), c.getPercentualMinimo(),
                gs, fs, catalogoFaixas());
    }
}
