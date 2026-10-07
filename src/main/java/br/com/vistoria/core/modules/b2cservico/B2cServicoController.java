package br.com.vistoria.core.modules.b2cservico;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Catálogo dos serviços que um Cliente B2C pode ser habilitado a solicitar
 * (ver {@link B2cService}) — substitui o antigo enum hardcoded {@code client_service}.
 * Consumido pela tela Biletagem Cliente (tabs), pelo wizard de cadastro de Cliente B2C
 * (acesso por serviço) e pelo wizard de solicitação do próprio cliente.
 */
@RestController
@RequestMapping("/v1/b2c-servicos")
@RequiredArgsConstructor
@Tag(name = "Serviços B2C", description = "Catálogo de serviços solicitáveis por Clientes B2C")
public class B2cServicoController {

    private final B2cServicoService service;

    public record B2cServicoView(UUID id, String nome, String descricao, String icone, boolean ativo) {
        static B2cServicoView from(B2cService s) {
            return new B2cServicoView(s.getId(), s.getNome(), s.getDescricao(), s.getIcone(), s.isAtivo());
        }
    }

    public record CriarRequest(@NotBlank String nome, String descricao, String icone, Short ordem) {}
    public record AtualizarRequest(String nome, String descricao, String icone, Boolean ativo, Short ordem) {}

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Listar serviços B2C",
            description = "Por padrão só os ativos; admin/suporte pode pedir todos com incluirInativos=true.")
    @SecurityRequirement(name = "bearerAuth")
    public List<B2cServicoView> listar(@RequestParam(defaultValue = "false") boolean incluirInativos) {
        return service.listar(!incluirInativos).stream().map(B2cServicoView::from).toList();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Criar serviço B2C", description = "Cria também o tipos_servico correspondente (necessário para solicitacao_servicos).")
    @SecurityRequirement(name = "bearerAuth")
    public B2cServicoView criar(@Valid @RequestBody CriarRequest body) {
        return B2cServicoView.from(service.criar(
                new B2cServicoService.CreateRequest(body.nome(), body.descricao(), body.icone(), body.ordem())));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Atualizar serviço B2C (parcial)")
    @SecurityRequirement(name = "bearerAuth")
    public B2cServicoView atualizar(@PathVariable UUID id, @RequestBody AtualizarRequest body) {
        return B2cServicoView.from(service.atualizar(id, new B2cServicoService.PatchRequest(
                body.nome(), body.descricao(), body.icone(), body.ativo(), body.ordem())));
    }
}
