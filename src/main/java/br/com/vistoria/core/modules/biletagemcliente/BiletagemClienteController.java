package br.com.vistoria.core.modules.biletagemcliente;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/config/biletagem-cliente")
@RequiredArgsConstructor
@Tag(name = "Biletagem Cliente", description = "Preço B2C global (Página 3 do prompt 4 telas B2C) — um contrato só, sem imobiliária")
public class BiletagemClienteController {

    private final BiletagemClienteService service;

    public record PercentualItem(@NotNull UUID b2cServiceId,
                                 @NotNull BigDecimal percentual, BigDecimal minimo) {}

    public record FaixaItem(@NotNull UUID b2cServiceId,
                            @NotNull Short faixaReferenciaId,
                            @NotNull BigDecimal valorSemMob, @NotNull BigDecimal valorComMob) {}

    public record SalvarRequest(@NotNull BiletagemCliente.Modo modo,
                                List<PercentualItem> percentuais, List<FaixaItem> faixas) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Obter biletagem cliente (B2C)")
    @SecurityRequirement(name = "bearerAuth")
    public BiletagemClienteService.BiletagemClienteView get() {
        return service.get();
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Salvar biletagem cliente (B2C)", description = "Substitui modo + linhas do modo escolhido inteiras.")
    @SecurityRequirement(name = "bearerAuth")
    public BiletagemClienteService.BiletagemClienteView salvar(@Valid @RequestBody SalvarRequest body) {
        List<BiletagemClienteService.PercentualItem> p = body.percentuais() == null ? null
                : body.percentuais().stream()
                        .map(i -> new BiletagemClienteService.PercentualItem(i.b2cServiceId(), i.percentual(), i.minimo()))
                        .toList();
        List<BiletagemClienteService.FaixaItem> f = body.faixas() == null ? null
                : body.faixas().stream()
                        .map(i -> new BiletagemClienteService.FaixaItem(i.b2cServiceId(), i.faixaReferenciaId(), i.valorSemMob(), i.valorComMob()))
                        .toList();
        return service.salvar(body.modo(), p, f);
    }
}
