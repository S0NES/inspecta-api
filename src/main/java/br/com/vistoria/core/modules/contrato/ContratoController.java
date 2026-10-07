package br.com.vistoria.core.modules.contrato;

import br.com.vistoria.core.modules.contrato.ContratoRepositories.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * @deprecated V3 (tipagem/biletagem) — o contrato virou sub-recurso da imobiliária:
 * use {@link br.com.vistoria.core.modules.imobiliaria.ImobiliariaContratoController}
 * ({@code /v1/imobiliarias/{id}/contrato[/grupos|/faixas]}). Estas rotas {@code /v1/contratos*}
 * seguem funcionando como alias para não quebrar integrações existentes; nenhuma tela nova
 * deve usá-las.
 */
@Deprecated
@RestController
@RequestMapping("/v1/contratos")
@RequiredArgsConstructor
@Tag(name = "Contratos (legado)", description = "DEPRECATED — use /v1/imobiliarias/{id}/contrato. Mantido como alias (V3).")
public class ContratoController {

    private final ContratoRepository contratos;
    private final FaixaRepository faixas;
    private final ServicoRepository servicos;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA')")
    @Operation(summary = "Listar contratos de vistoria", description = "Lista todos os contratos de vistoria com paginação.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista de contratos retornada com sucesso"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão para listar contratos")
    })
    @SecurityRequirement(name = "bearerAuth")
    public Page<Contrato> list(@RequestParam(required = false) UUID imobiliariaId, Pageable p) {
        if (imobiliariaId != null) {
            List<Contrato> lista = contratos.findByImobiliariaIdOrderByCriadoEmDesc(imobiliariaId);
            return new org.springframework.data.domain.PageImpl<>(lista, p, lista.size());
        }
        return contratos.findAll(p);
    }

    @GetMapping("/{id}/faixas")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA')")
    @Operation(summary = "Faixas de cobertura de um contrato")
    @SecurityRequirement(name = "bearerAuth")
    public List<ContratoFaixa> listFaixas(@PathVariable UUID id) {
        return faixas.findByContratoIdOrderByTipoServicoIdAscMetragemMinAsc(id);
    }

    @GetMapping("/{id}/servicos")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA')")
    @Operation(summary = "Serviços configurados de um contrato")
    @SecurityRequirement(name = "bearerAuth")
    public List<ContratoServico> listServicos(@PathVariable UUID id) {
        return servicos.findByContratoId(id);
    }

    @GetMapping("/servicos/{tipoServicoId}/imobiliarias")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Imobiliárias que cobrem um tipo de serviço (via contratos)")
    @SecurityRequirement(name = "bearerAuth")
    public List<UUID> imobiliariasPorTipoServico(@PathVariable UUID tipoServicoId) {
        return servicos.findImobiliariaIdsByTipoServicoId(tipoServicoId);
    }

    public record ContratoPatch(String status, java.time.LocalDate vigenciaInicio, java.time.LocalDate vigenciaFim,
                                java.math.BigDecimal percentual, Integer volumeMensal, java.math.BigDecimal faturamentoMinimo) {}

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Atualizar contrato (parcial)")
    @SecurityRequirement(name = "bearerAuth")
    public Contrato patch(@PathVariable UUID id, @org.springframework.web.bind.annotation.RequestBody ContratoPatch b) {
        Contrato c = contratos.findById(id)
                .orElseThrow(() -> new br.com.vistoria.core.shared.exception.NotFoundException("Contrato " + id));
        if (b.status() != null && !b.status().isBlank()) c.setStatus(b.status().trim());
        if (b.vigenciaInicio() != null) c.setVigenciaInicio(b.vigenciaInicio());
        if (b.vigenciaFim() != null) c.setVigenciaFim(b.vigenciaFim());
        if (b.percentual() != null) c.setPercentual(b.percentual());
        if (b.volumeMensal() != null) c.setVolumeMensal(b.volumeMensal());
        if (b.faturamentoMinimo() != null) c.setFaturamentoMinimo(b.faturamentoMinimo());
        return contratos.save(c);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Criar novo contrato de vistoria", description = "Cria um novo contrato de vistoria. Apenas ADMIN pode criar contratos.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Contrato criado com sucesso", content = @Content(schema = @Schema(implementation = Contrato.class))),
            @ApiResponse(responseCode = "400", description = "Dados de entrada inválidos"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão para criar contratos")
    })
    @SecurityRequirement(name = "bearerAuth")
    public Contrato create(
            @RequestBody(description = "Dados do contrato de vistoria") @org.springframework.web.bind.annotation.RequestBody Contrato c) {
        // Guarda de sobreposicao (RELATORIO.md achado #15): sem isto era possivel deixar duas
        // linhas 'ativo' para a mesma imobiliaria, e PrecoService.contratoAtivo passa a resolver
        // qualquer uma delas de forma nao-deterministica.
        boolean entraAtivo = c.getStatus() == null || c.getStatus().isBlank()
                || "ativo".equalsIgnoreCase(c.getStatus().trim());
        if (entraAtivo && c.getImobiliariaId() != null
                && contratos.findFirstByImobiliariaIdAndStatus(c.getImobiliariaId(), "ativo").isPresent()) {
            throw new IllegalStateException(
                    "Imobiliaria ja tem contrato ativo; desative o atual antes de criar outro");
        }
        return contratos.save(c);
    }

    @PostMapping("/{id}/faixas")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Adicionar faixa de cobertura a contrato", description = "Adiciona uma faixa de metragem para um tipo de serviço em um contrato. Impede sobreposição de faixas existentes.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Faixa adicionada com sucesso", content = @Content(schema = @Schema(implementation = ContratoFaixa.class))),
            @ApiResponse(responseCode = "400", description = "Faixa sobrepõe faixa existente ou dados inválidos"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão"),
            @ApiResponse(responseCode = "404", description = "Contrato não encontrado")
    })
    @SecurityRequirement(name = "bearerAuth")
    public ContratoFaixa addFaixa(
            @PathVariable UUID id,
            @RequestBody(description = "Dados da faixa de cobertura") @org.springframework.web.bind.annotation.RequestBody ContratoFaixa f) {
        f.setContratoId(id);
        List<ContratoFaixa> existentes = faixas.findByContratoIdAndTipoServicoId(id, f.getTipoServicoId());
        boolean overlap = existentes.stream().anyMatch(e ->
                f.getMetragemMin().compareTo(e.getMetragemMax()) <= 0 &&
                f.getMetragemMax().compareTo(e.getMetragemMin()) >= 0);
        if (overlap) throw new IllegalArgumentException("Faixa sobrepoe faixa existente");
        return faixas.save(f);
    }

    @PostMapping("/{id}/servicos")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Configurar serviço para contrato (T1.8)", description = "Configura ou atualiza um tipo de serviço em um contrato. Se o serviço já existe, atualiza; caso contrário, cria um novo.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Serviço configurado com sucesso", content = @Content(schema = @Schema(implementation = ContratoServico.class))),
            @ApiResponse(responseCode = "400", description = "Dados de entrada inválidos"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão"),
            @ApiResponse(responseCode = "404", description = "Contrato não encontrado")
    })
    @SecurityRequirement(name = "bearerAuth")
    public ContratoServico setServico(
            @PathVariable UUID id,
            @RequestBody(description = "Dados do serviço de contrato") @org.springframework.web.bind.annotation.RequestBody ContratoServico s) {
        ContratoServico existente = servicos.findFirstByContratoIdAndTipoServicoId(id, s.getTipoServicoId())
                .orElseGet(ContratoServico::new);
        existente.setContratoId(id);
        existente.setTipoServicoId(s.getTipoServicoId());
        existente.setAtivo(s.isAtivo());
        return servicos.save(existente);
    }
}
