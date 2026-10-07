package br.com.vistoria.core.modules.metrics;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/v1/metricas")
@RequiredArgsConstructor
@Tag(name = "Métricas de envio", description = "Leitura D-1 dos painéis (só ADMIN/SUPORTE) — lê apenas os rollups diários, nunca chama o inspecta-worker")
public class MetricasController {

    private final MetricasQueryService queryService;

    @GetMapping("/envios")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Série diária de envios por dimensão",
            description = "Envios agregados por dia (D-1), dimensão tipo de envio × tipo de comunicação. "
                    + "Filtros opcionais de tipo de envio e tipo de comunicação.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Série retornada"),
            @ApiResponse(responseCode = "400", description = "inicio > fim ou janela maior que 366 dias"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não é ADMIN nem SUPORTE")
    })
    @SecurityRequirement(name = "bearerAuth")
    public MetricasQueryService.EnviosResposta envios(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim,
            @RequestParam(required = false) String tipoEnvio,
            @RequestParam(required = false) String tipoComunicacao) {
        return queryService.envios(inicio, fim, tipoEnvio, tipoComunicacao);
    }

    @GetMapping("/solicitacoes")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Série diária de solicitações por tipo de serviço",
            description = "Solicitações agregadas por dia (D-1), por tipo de serviço: concluídas e em andamento.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Série retornada"),
            @ApiResponse(responseCode = "400", description = "inicio > fim ou janela maior que 366 dias"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não é ADMIN nem SUPORTE")
    })
    @SecurityRequirement(name = "bearerAuth")
    public MetricasQueryService.SolicitacoesResposta solicitacoes(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim) {
        return queryService.solicitacoes(inicio, fim);
    }
}
