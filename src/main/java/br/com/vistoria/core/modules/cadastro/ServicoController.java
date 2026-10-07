package br.com.vistoria.core.modules.cadastro;

import br.com.vistoria.core.modules.cadastro.ServicoService.ServicoView;
import br.com.vistoria.core.shared.exception.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * V3 (tipagem/biletagem) — CRUD do "Serviço" guarda-chuva (catálogo global). Aba 1 da tela
 * de Tipagem no inspecta-web. Toda operação é restrita a admin/suporte; a delegação in-process vai
 * para {@link ServicoService} no inspecta-core (sem REST entre serviços Java).
 *
 * <p>O catálogo de Tipos de Serviço para a aba 2 continua em
 * {@code GET /v1/tipos-servico} ({@link CadastroController}).</p>
 */
@RestController
@RequestMapping("/v1/servicos")
@RequiredArgsConstructor
@Tag(name = "Serviços (guarda-chuva)", description = "Catálogo global de Serviços que agrupam Tipos de Serviço (V3). Somente admin/suporte.")
public class ServicoController {

    private final ServicoService servicos;

    public record CriarServicoRequest(String nome, List<UUID> tipoServicoIds) {}
    public record AtualizarServicoRequest(String nome, Boolean ativo, List<UUID> tipoServicoIds) {}
    public record AtualizarCodigoPlenoRequest(Integer codigo) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Listar Serviços guarda-chuva com seus Tipos de Serviço")
    @SecurityRequirement(name = "bearerAuth")
    public List<ServicoView> listar() {
        return servicos.listar();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Criar Serviço guarda-chuva")
    @SecurityRequirement(name = "bearerAuth")
    public ServicoView criar(@org.springframework.web.bind.annotation.RequestBody CriarServicoRequest body) {
        return servicos.criar(body.nome(), body.tipoServicoIds());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Atualizar Serviço guarda-chuva (parcial). tipoServicoIds ausente = não mexe nos vínculos.")
    @SecurityRequirement(name = "bearerAuth")
    public ServicoView atualizar(@PathVariable UUID id,
                                 @org.springframework.web.bind.annotation.RequestBody AtualizarServicoRequest body) {
        return servicos.atualizar(id, body.nome(), body.ativo(), body.tipoServicoIds());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Excluir Serviço guarda-chuva")
    @SecurityRequirement(name = "bearerAuth")
    public void excluir(@PathVariable UUID id) {
        servicos.excluir(id);
    }

    /**
     * Configura o código Pleno (tip_vis_codigo) de uma combinação Serviço × Tipo de Serviço.
     * {@code codigo} nulo limpa a configuração (volta ao de-para/default legado). 404 quando o
     * vínculo Serviço↔Tipo de Serviço não existe ({@link ServicoService#atualizarCodigoPleno}).
     */
    @PatchMapping("/{servicoId}/tipos/{tipoServicoId}/pleno-codigo")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Configurar o código Pleno (tip_vis_codigo) de um Tipo de Serviço dentro de um Serviço")
    @SecurityRequirement(name = "bearerAuth")
    public ServicoView atualizarCodigoPleno(@PathVariable UUID servicoId,
                                             @PathVariable UUID tipoServicoId,
                                             @org.springframework.web.bind.annotation.RequestBody AtualizarCodigoPlenoRequest body) {
        servicos.atualizarCodigoPleno(servicoId, tipoServicoId, body.codigo());
        return servicos.listar().stream()
                .filter(v -> v.id().equals(servicoId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Servico " + servicoId + " nao encontrado"));
    }
}
