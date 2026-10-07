package br.com.vistoria.core.modules.financeiro;

import br.com.vistoria.core.config.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Relatórios", description = "Relatórios financeiros e operacionais por imobiliária e período")
public class RelatorioController {

    private final RelatorioService service;

    @GetMapping("/v1/relatorios/imobiliaria")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA')")
    @Operation(summary = "Relatório financeiro por imobiliária", description = "Retorna relatório de cobranças, pagamentos e comissões para uma imobiliária em um mês. IMOB_GERENCIA vê apenas sua imobiliária via tenant-scoping.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Relatório retornado"),
            @ApiResponse(responseCode = "400", description = "Mês inválido ou imobiliariaId obrigatório"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão")
    })
    @SecurityRequirement(name = "bearerAuth")
    public RelatorioService.RelatorioImobiliaria relatorio(@RequestParam String mes,
                                                           @RequestParam(required = false) java.util.UUID imobiliariaId) {
        // V25: gerência vinculada a N imobiliárias pode pedir o relatório de qualquer
        // uma delas (imobiliariaId); sem param, cai na primária. Admin/suporte (sem
        // tenant) escolhe qualquer imobiliária pelo param.
        java.util.List<java.util.UUID> tenants = TenantContext.getAll();
        java.util.UUID alvo;
        if (tenants.isEmpty()) {
            alvo = imobiliariaId;
        } else if (imobiliariaId != null && tenants.contains(imobiliariaId)) {
            alvo = imobiliariaId;
        } else {
            alvo = tenants.get(0);
        }
        if (alvo == null) throw new IllegalArgumentException("imobiliariaId obrigatorio");
        return service.imobiliaria(alvo, mes);
    }

    @GetMapping("/v1/relatorios/consumo")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Consumo pago por imobiliária no mês (cross-imobiliária)")
    @SecurityRequirement(name = "bearerAuth")
    public java.util.List<RelatorioService.ConsumoImobiliaria> consumo(@RequestParam String mes) {
        return service.consumoCrossImobiliaria(mes);
    }

    @GetMapping("/v1/relatorios/vistorias")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA','IMOB_OPERACAO')")
    @Operation(summary = "Extrato granular por vistoria",
            description = "Uma linha por solicitacao_servico no mês de criação. "
                    + "Admin/suporte vê todas; gerência/operação fica no recorte do tenant. "
                    + "Busca (`nome`) casa endereço, nome da imobiliária ou id da solicitação.")
    @SecurityRequirement(name = "bearerAuth")
    public VistoriasPage vistorias(
            @RequestParam String mes,
            @RequestParam(required = false) java.util.UUID imobiliariaId,
            @RequestParam(required = false) String nome,
            org.springframework.data.domain.Pageable pageable) {
        java.util.List<java.util.UUID> tenants = TenantContext.getAll();
        java.util.UUID alvo = imobiliariaId;
        if (!tenants.isEmpty()) {
            if (imobiliariaId != null && tenants.contains(imobiliariaId)) {
                alvo = imobiliariaId;
            } else if (tenants.size() == 1) {
                alvo = tenants.get(0);
            } else if (imobiliariaId == null) {
                alvo = null; // várias imobiliárias do mesmo usuário — RLS recorta
            } else {
                throw new IllegalArgumentException("imobiliariaId fora do escopo");
            }
        }
        return service.vistorias(mes, alvo, nome, pageable);
    }
}
