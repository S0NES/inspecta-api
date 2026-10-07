package br.com.vistoria.core.modules.financeiro;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Extrato consolidado do fluxo B2B (imobiliária): os {@code extrato_itens} de solicitações
 * pós-pagas ficam em aberto até serem liquidados por uma {@code fatura_consolidada} de um
 * período. Toda leitura/escrita passa pelo {@link ExtratoService} do inspecta-core; a RLS por
 * tenant já filtra as duas tabelas ({@code extrato_itens} / {@code faturas_consolidadas}).
 */
@RestController
@RequestMapping("/v1/imobiliarias/{id}/extrato")
@RequiredArgsConstructor
@Tag(name = "Extrato consolidado", description = "Itens em aberto e faturas consolidadas do fluxo B2B (imobiliária)")
public class ExtratoController {

    private final ExtratoService extrato;

    @GetMapping("/itens")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA','IMOB_OPERACAO')")
    @Operation(summary = "Itens de extrato em aberto no período",
            description = "Lista os itens de extrato (preço snapshot por serviço B2B) ainda não faturados, "
                    + "criados na janela [inicio, fim]. RLS por tenant.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Itens retornados"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão")
    })
    @SecurityRequirement(name = "bearerAuth")
    public List<ExtratoItemAberto> itens(
            @PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim) {
        return extrato.listarItensAbertos(id, inicio, fim);
    }

    /** Janela de faturamento consolidado. */
    public record GerarFaturaRequest(@NotNull LocalDate inicio, @NotNull LocalDate fim) {}

    /** {@code checkoutUrl} nasce {@code null}: o checkout é gerado assíncrono pelo inspecta-worker. */
    public record FaturaGeradaResponse(UUID faturaId, BigDecimal valorTotal, String checkoutUrl) {
        static FaturaGeradaResponse of(FaturaConsolidada f) {
            return new FaturaGeradaResponse(f.getId(), f.getValorTotal(), f.getCheckoutUrl());
        }
    }

    @PostMapping("/faturas")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA','IMOB_OPERACAO')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Gerar a fatura consolidada do mês",
            description = "A fatura é o compilado mensal do extrato: um mês inteiro (dia 1 ao último dia) e já encerrado, "
                    + "uma por imobiliária/mês. Fecha os itens em aberto do mês e publica "
                    + "jobs.payment.fatura-charge-requested para o inspecta-worker gerar o checkout.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Fatura consolidada criada; checkout assíncrono"),
            @ApiResponse(responseCode = "400", description = "periodo_deve_ser_mes_inteiro, mes_ainda_aberto ou sem_itens_abertos_no_periodo"),
            @ApiResponse(responseCode = "409", description = "Já existe fatura para a imobiliária neste mês"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão")
    })
    @SecurityRequirement(name = "bearerAuth")
    public FaturaGeradaResponse gerarFatura(@PathVariable UUID id, @RequestBody GerarFaturaRequest body) {
        FaturaConsolidada f = extrato.gerarFaturaConsolidada(id, body.inicio(), body.fim(), atorAtual());
        return FaturaGeradaResponse.of(f);
    }

    @GetMapping("/faturas")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA','IMOB_OPERACAO')")
    @Operation(summary = "Histórico de faturas consolidadas",
            description = "Faturas consolidadas da imobiliária, mais recentes primeiro. RLS por tenant.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Histórico retornado"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão")
    })
    @SecurityRequirement(name = "bearerAuth")
    public List<FaturaConsolidada> faturas(@PathVariable UUID id) {
        return extrato.listarFaturas(id);
    }

    /** Identificação de quem disparou a fatura, para trilha no evento/log (best-effort). */
    private static String atorAtual() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }
}
