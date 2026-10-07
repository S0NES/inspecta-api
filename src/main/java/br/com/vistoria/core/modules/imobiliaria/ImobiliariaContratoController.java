package br.com.vistoria.core.modules.imobiliaria;

import br.com.vistoria.core.modules.contrato.Contrato;
import br.com.vistoria.core.modules.contrato.ContratoFaixa;
import br.com.vistoria.core.modules.contrato.ContratoResumoService;
import br.com.vistoria.core.modules.contrato.ContratoResumoService.ContratoResumo;
import br.com.vistoria.core.modules.contrato.ContratoResumoService.FaixaReferenciaView;
import br.com.vistoria.core.modules.contrato.ContratoResumoService.GrupoView;
import br.com.vistoria.core.modules.contrato.ContratoServicoGrupo;
import br.com.vistoria.core.modules.contrato.ContratoRepositories.ContratoRepository;
import br.com.vistoria.core.modules.contrato.ContratoRepositories.ContratoServicoGrupoRepository;
import br.com.vistoria.core.modules.contrato.ContratoRepositories.FaixaRepository;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.ServicoRepository;
import br.com.vistoria.core.shared.exception.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * V3 (tipagem/biletagem) — o contrato passa a ser sub-recurso da imobiliária. O tipo de
 * biletagem ({@code modo}: faixa|percentual) é único por imobiliária (há sempre no máximo
 * um contrato {@code status='ativo'}); só os VALORES variam por Serviço guarda-chuva
 * ({@code /grupos}) ou por faixa de metragem ({@code /faixas}).
 *
 * <p>Rotas legadas de {@code /v1/contratos} ({@link br.com.vistoria.core.modules.contrato.ContratoController})
 * continuam existindo como alias {@code @Deprecated} — não foram removidas para não quebrar
 * integrações; novas telas devem usar {@code /v1/imobiliarias/{id}/contrato}.</p>
 *
 * <p>Escrita: admin/suporte.</p>
 */
@RestController
@RequestMapping("/v1/imobiliarias/{imobiliariaId}/contrato")
@RequiredArgsConstructor
@Tag(name = "Imobiliárias — Contrato", description = "Contrato de biletagem como sub-recurso da imobiliária (V3)")
public class ImobiliariaContratoController {

    private final ContratoRepository contratos;
    private final ContratoServicoGrupoRepository grupos;
    private final FaixaRepository faixas;
    private final ServicoRepository servicos;
    private final ContratoResumoService resumoService;

    public record ContratoUpsertRequest(String modo, BigDecimal percentual, BigDecimal percentualMinimo) {}
    public record GrupoItem(UUID servicoId, BigDecimal percentual, BigDecimal percentualMinimo) {}

    /**
     * V4 (fase 2): valor de biletagem para um par (Serviço guarda-chuva × faixa de referência).
     * {@code faixaId} = {@code faixa_referencia.id} (1..5). {@code paStep*} só fazem sentido na
     * faixa 5, mas são persistidos/retornados como vierem.
     */
    public record FaixaItem(UUID servicoId, Short faixaId,
                            BigDecimal valorSemMobilia, BigDecimal valorComMobilia,
                            BigDecimal paStepMetragem, BigDecimal paStepValor) {}


    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA')")
    @Operation(summary = "Resumo do contrato ativo da imobiliária (modo, percentual, grupos, faixas)")
    @SecurityRequirement(name = "bearerAuth")
    public ContratoResumo get(@PathVariable UUID imobiliariaId) {
        ContratoResumo r = resumoService.resumoOuNull(imobiliariaId);
        if (r == null) throw new NotFoundException("Imobiliaria " + imobiliariaId + " sem contrato ativo");
        return r;
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Transactional
    @Operation(summary = "Criar/atualizar o contrato ativo. modo é único por imobiliária.")
    @SecurityRequirement(name = "bearerAuth")
    public ContratoResumo upsert(@PathVariable UUID imobiliariaId,
                                 @org.springframework.web.bind.annotation.RequestBody ContratoUpsertRequest body) {
        Contrato.Modo modo = parseModo(body.modo());
        Contrato c = contratos.findFirstByImobiliariaIdAndStatus(imobiliariaId, "ativo")
                .orElseGet(() -> {
                    Contrato novo = new Contrato();
                    novo.setImobiliariaId(imobiliariaId);
                    novo.setStatus("ativo");
                    return novo;
                });
        if (modo != null) c.setModo(modo);
        if (c.getModo() == null) throw new IllegalArgumentException("modo obrigatorio (faixa|percentual)");
        c.setPercentual(body.percentual());
        c.setPercentualMinimo(body.percentualMinimo());
        contratos.save(c);
        return resumoService.resumoOuNull(imobiliariaId);
    }


    @GetMapping("/grupos")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA')")
    @Operation(summary = "Valores de biletagem por Serviço guarda-chuva do contrato ativo")
    @SecurityRequirement(name = "bearerAuth")
    public List<GrupoView> listGrupos(@PathVariable UUID imobiliariaId) {
        Contrato c = contratoAtivo(imobiliariaId);
        return grupos.findByContratoId(c.getId()).stream()
                .map(g -> new GrupoView(g.getId(), g.getServicoId(), g.getPercentual(), g.getPercentualMinimo()))
                .toList();
    }

    @PutMapping("/grupos")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Transactional
    @Operation(summary = "Substituir a lista de grupos (valores por Serviço) do contrato ativo")
    @SecurityRequirement(name = "bearerAuth")
    public List<GrupoView> putGrupos(@PathVariable UUID imobiliariaId,
                                     @org.springframework.web.bind.annotation.RequestBody List<GrupoItem> itens) {
        Contrato c = contratoAtivo(imobiliariaId);
        grupos.deleteByContratoId(c.getId());
        if (itens != null) {
            for (GrupoItem it : itens) {
                if (it.servicoId() == null) throw new IllegalArgumentException("servicoId obrigatorio em cada grupo");
                ContratoServicoGrupo g = new ContratoServicoGrupo();
                g.setContratoId(c.getId());
                g.setServicoId(it.servicoId());
                g.setPercentual(it.percentual());
                g.setPercentualMinimo(it.percentualMinimo());
                grupos.save(g);
            }
        }
        return listGrupos(imobiliariaId);
    }

    @DeleteMapping("/grupos")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Transactional
    @Operation(summary = "Remover um grupo (por servicoId) ou todos os grupos do contrato ativo")
    @SecurityRequirement(name = "bearerAuth")
    public Map<String, Object> deleteGrupos(@PathVariable UUID imobiliariaId,
                                            @RequestParam(required = false) UUID servicoId) {
        Contrato c = contratoAtivo(imobiliariaId);
        if (servicoId == null) {
            grupos.deleteByContratoId(c.getId());
            return Map.of("removidos", "todos");
        }
        var alvo = grupos.findFirstByContratoIdAndServicoId(c.getId(), servicoId)
                .orElseThrow(() -> new NotFoundException("Grupo servicoId " + servicoId + " no contrato " + c.getId()));
        grupos.delete(alvo);
        return Map.of("removidos", 1);
    }


    public record FaixasResponse(List<ContratoResumoService.FaixaView> faixas,
                                 List<FaixaReferenciaView> faixaReferencia) {}

    @GetMapping("/faixas")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA')")
    @Operation(summary = "Valores de biletagem por (Serviço × faixa de referência) do contrato ativo + catálogo global")
    @SecurityRequirement(name = "bearerAuth")
    public FaixasResponse listFaixas(@PathVariable UUID imobiliariaId) {
        Contrato c = contratoAtivo(imobiliariaId);
        return new FaixasResponse(resumoService.faixasDoContrato(c.getId()), resumoService.catalogoFaixas());
    }

    @PutMapping("/faixas")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Transactional
    @Operation(summary = "Substituir todos os valores por (Serviço × faixa) do contrato ativo (replace-all)")
    @SecurityRequirement(name = "bearerAuth")
    public FaixasResponse putFaixas(@PathVariable UUID imobiliariaId,
                                    @org.springframework.web.bind.annotation.RequestBody List<FaixaItem> itens) {
        Contrato c = contratoAtivo(imobiliariaId);
        // replace-all somente das linhas do novo modelo (servico_id IS NOT NULL); linhas legadas
        // keyed por tipo_servico_id (servico_id null) ficam intactas. contrato_repasses dessas
        // linhas cai por ON DELETE CASCADE — não recriamos repasses aqui (igual à fase 1).
        faixas.findByContratoIdOrderByTipoServicoIdAscMetragemMinAsc(c.getId()).stream()
                .filter(f -> f.getServicoId() != null)
                .forEach(faixas::delete);
        if (itens != null) {
            Set<String> vistos = new HashSet<>();
            for (FaixaItem it : itens) {
                if (it.servicoId() == null || it.faixaId() == null
                        || it.valorSemMobilia() == null || it.valorComMobilia() == null)
                    throw new IllegalArgumentException(
                            "servicoId, faixaId, valorSemMobilia e valorComMobilia sao obrigatorios");
                if (it.faixaId() < 1 || it.faixaId() > 5)
                    throw new IllegalArgumentException("faixaId deve estar entre 1 e 5 (faixa_referencia.id)");
                if (it.valorSemMobilia().signum() < 0 || it.valorComMobilia().signum() < 0
                        || (it.paStepMetragem() != null && it.paStepMetragem().signum() < 0)
                        || (it.paStepValor() != null && it.paStepValor().signum() < 0))
                    throw new IllegalArgumentException("valores nao podem ser negativos");
                if (!servicos.existsById(it.servicoId()))
                    throw new NotFoundException("Servico " + it.servicoId() + " nao encontrado");
                if (!vistos.add(it.servicoId() + ":" + it.faixaId()))
                    throw new IllegalArgumentException(
                            "faixa duplicada no corpo para (servicoId=" + it.servicoId() + ", faixaId=" + it.faixaId() + ")");
                ContratoFaixa f = new ContratoFaixa();
                f.setContratoId(c.getId());
                f.setServicoId(it.servicoId());
                f.setFaixaReferenciaId(it.faixaId());
                f.setValorSemMob(it.valorSemMobilia());
                f.setValorComMob(it.valorComMobilia());
                f.setPaStepMetragem(it.paStepMetragem());
                f.setPaStepValor(it.paStepValor());
                faixas.save(f);
            }
        }
        return listFaixas(imobiliariaId);
    }


    private Contrato contratoAtivo(UUID imobiliariaId) {
        return contratos.findFirstByImobiliariaIdAndStatus(imobiliariaId, "ativo")
                .orElseThrow(() -> new NotFoundException("Imobiliaria " + imobiliariaId + " sem contrato ativo"));
    }

    private static Contrato.Modo parseModo(String modo) {
        if (modo == null || modo.isBlank()) return null;
        try {
            return Contrato.Modo.valueOf(modo.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("modo invalido: " + modo + " (use faixa|percentual)");
        }
    }
}
