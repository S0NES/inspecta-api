package br.com.vistoria.core.modules.solicitacao;

import br.com.vistoria.core.modules.disponibilidade.DisponibilidadeRepositories.VistoriadorRepository;
import br.com.vistoria.core.modules.disponibilidade.Vistoriador;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.ServicoRepository;
import br.com.vistoria.core.modules.usuario.Usuario;
import br.com.vistoria.core.modules.usuario.UsuarioRepository;
import br.com.vistoria.core.shared.exception.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Página 4 do prompt "4 telas B2C" — wizard de solicitação do perfil cliente. */
@RestController
@RequestMapping("/v1/solicitacoes")
@RequiredArgsConstructor
@Tag(name = "Solicitação B2C", description = "Rascunho com hold de vistoriador + finalização (perfil cliente)")
public class SolicitacaoB2cController {

    private final SolicitacaoB2cService service;
    private final ServicoRepository servicos;
    private final VistoriadorRepository vistoriadores;
    private final UsuarioRepository usuarios;

    public record ImovelRequest(@NotNull UUID cidadeId, String cep, @NotNull String endereco, String numero,
                                String complemento, @NotNull BigDecimal metragem, String tipo, Integer quartos,
                                Boolean cobertura, BigDecimal valorAluguel, Boolean mobiliado) {}

    public record AcompanhanteRequest(String papelSlug, @NotNull String nome, @NotNull String cpf,
                                      @NotNull String email, String telefone) {}

    public record RascunhoRequest(@Valid @NotNull ImovelRequest imovel,
                                  @NotNull UUID b2cServiceId,
                                  @NotNull Instant dataDesejada, AcompanhanteRequest acompanhante) {}

    public record ReagendarRequest(@NotNull UUID b2cServiceId, @NotNull Instant dataDesejada) {}

    public record IntervenientesRequest(@NotEmpty List<@Valid AcompanhanteRequest> intervenientes) {}

    public record RascunhoResponse(UUID solicitacaoId, UUID servicoId, String status, BigDecimal precoSnapshot,
                                   Instant dataAgendada, UUID vistoriadorId, String vistoriadorNome) {}

    private static SolicitacaoB2cService.ImovelRascunho toImovelRascunho(ImovelRequest r) {
        return new SolicitacaoB2cService.ImovelRascunho(r.cidadeId(), r.cep(), r.endereco(), r.numero(),
                r.complemento(), r.metragem(), r.tipo(), r.quartos(), Boolean.TRUE.equals(r.cobertura()),
                r.valorAluguel(), Boolean.TRUE.equals(r.mobiliado()));
    }

    private static SolicitacaoB2cService.AcompanhanteRascunho toAcompanhante(AcompanhanteRequest r) {
        if (r == null) return null;
        return new SolicitacaoB2cService.AcompanhanteRascunho(r.papelSlug(), r.nome(), r.cpf(), r.email(), r.telefone());
    }

    private RascunhoResponse montarResposta(Solicitacao s) {
        SolicitacaoServico ss = servicos.findBySolicitacaoId(s.getId()).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Servico da solicitacao " + s.getId()));
        String vistoriadorNome = null;
        if (ss.getVistoriadorId() != null) {
            vistoriadorNome = vistoriadores.findById(ss.getVistoriadorId())
                    .map(Vistoriador::getUsuarioId)
                    .flatMap(usuarios::findById)
                    .map(Usuario::getNome)
                    .orElse(null);
        }
        return new RascunhoResponse(s.getId(), ss.getId(), ss.getStatus().name(), ss.getPrecoSnapshot(),
                ss.getDataAgendada(), ss.getVistoriadorId(), vistoriadorNome);
    }

    @PostMapping("/rascunho-b2c")
    @PreAuthorize("hasRole('CLIENTE')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Criar rascunho de solicitação B2C (fim do Passo 2)",
            description = "Cria imóvel + solicitação + serviço, resolve preço e reserva vistoriador. 409 SEM_VISTORIADOR se não houver cobertura na janela desejada.")
    @SecurityRequirement(name = "bearerAuth")
    public RascunhoResponse criar(@AuthenticationPrincipal UUID userId, @Valid @RequestBody RascunhoRequest body) {
        var req = new SolicitacaoB2cService.CriarRascunhoRequest(
                toImovelRascunho(body.imovel()), body.b2cServiceId(), body.dataDesejada(),
                toAcompanhante(body.acompanhante()));
        return montarResposta(service.criarRascunhoB2c(userId, req));
    }

    @PatchMapping("/{id}/rascunho-b2c")
    @PreAuthorize("hasRole('CLIENTE')")
    @Operation(summary = "Reagendar o rascunho (Passo 1 mudou data/hora ou serviço após 409)")
    @SecurityRequirement(name = "bearerAuth")
    public RascunhoResponse reagendar(@AuthenticationPrincipal UUID userId, @PathVariable UUID id,
                                      @Valid @RequestBody ReagendarRequest body) {
        return montarResposta(service.reagendarPrecadastro(userId, id, body.b2cServiceId(), body.dataDesejada()));
    }

    @PatchMapping("/{id}/intervenientes")
    @PreAuthorize("hasRole('CLIENTE')")
    @Operation(summary = "Substituir intervenientes do Passo 3 (mantém o acompanhante do Passo 2)")
    @SecurityRequirement(name = "bearerAuth")
    public void substituirIntervenientes(@AuthenticationPrincipal UUID userId, @PathVariable UUID id,
                                         @Valid @RequestBody IntervenientesRequest body) {
        service.substituirIntervenientes(userId, id, body.intervenientes().stream()
                .map(SolicitacaoB2cController::toAcompanhante).toList());
    }

    @PostMapping("/{id}/finalizar")
    @PreAuthorize("hasRole('CLIENTE')")
    @Operation(summary = "Finalizar o rascunho (Salvar do Passo 4) — snapshot de preço final")
    @SecurityRequirement(name = "bearerAuth")
    public RascunhoResponse finalizar(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        return montarResposta(service.finalizarRascunhoB2c(userId, id));
    }
}
