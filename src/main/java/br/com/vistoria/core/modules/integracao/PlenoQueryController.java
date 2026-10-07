package br.com.vistoria.core.modules.integracao;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Integração Pleno", description = "Endpoints de leitura sincronos para Pleno com circuit breaker e cache (T2.5, T2.9)")
public class PlenoQueryController {

    private final PlenoQueryService pleno;
    private final IntegracaoSyncService integracaoSync;
    private final br.com.vistoria.core.modules.cadastro.CadastroRepositories.ImovelRepository imoveis;
    private final br.com.vistoria.core.modules.imobiliaria.ImobiliariaRepository imobiliarias;
    private final br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.SolicitacaoRepository solicitacoes;
    private final br.com.vistoria.core.modules.disponibilidade.DisponibilidadeRepositories.VistoriadorRepository vistoriadores;
    private final br.com.vistoria.core.modules.usuario.UsuarioRepository usuarios;

    /**
     * Nome legível do recurso pro painel de integração — "Imovel #2" sozinho não diz nada
     * pro operador decidir se aquele "FALHOU" importa; com o nome dá pra reconhecer na hora.
     * Falha ao resolver (linha órfã, id de outro tenant, etc.) devolve null — o front cai
     * de volta pro rótulo genérico "{tipo} #{id}".
     */
    private String nomeDoRecurso(String recursoTipo, UUID recursoId) {
        try {
            return switch (recursoTipo) {
                case "imovel" -> imoveis.findById(recursoId).map(i -> i.getEndereco()).orElse(null);
                case "imobiliaria" -> imobiliarias.findById(recursoId)
                        .map(i -> i.getNomeFantasia() != null && !i.getNomeFantasia().isBlank()
                                ? i.getNomeFantasia() : i.getRazaoSocial())
                        .orElse(null);
                case "solicitacao" -> solicitacoes.findById(recursoId)
                        .map(s -> imoveis.findById(s.getImovelId()).map(i -> i.getEndereco()).orElse(null))
                        .orElse(null);
                case "vistoriador" -> vistoriadores.findById(recursoId)
                        .map(v -> usuarios.findById(v.getUsuarioId()).map(u -> u.getNome()).orElse(null))
                        .orElse(null);
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    /** Estado do espelho Pleno de um recurso local, para o badge "espelho Pleno pendente/falhou" na UI (T6.15/T6.19). */
    public record PlenoSyncStatus(String recursoTipo, UUID recursoId, String status, String externalId,
                                  String erro, java.time.Instant ultimoSync, String origem) {}

    @GetMapping("/v1/pleno/sync/{recursoTipo}/{recursoId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA','IMOB_OPERACAO')")
    @Operation(summary = "Estado do espelho Pleno de um recurso (T6.15/T6.19)",
            description = "Le integracao_sync local (sem tocar a Pleno). recursoTipo: imobiliaria|vistoriador|imovel|solicitacao. "
                    + "status: pendente|sincronizado|erro. Sem linha ainda = pendente.")
    @SecurityRequirement(name = "bearerAuth")
    public PlenoSyncStatus sync(@PathVariable String recursoTipo, @PathVariable UUID recursoId) {
        return integracaoSync.buscar(recursoTipo, recursoId)
                .map(s -> new PlenoSyncStatus(recursoTipo, recursoId, s.getStatus(), s.getExternalId(),
                        s.getErro(), s.getUltimoSync(), s.getOrigem()))
                .orElse(new PlenoSyncStatus(recursoTipo, recursoId, "pendente", null, null, null, "espelho"));
    }

    /** Linha do painel de integração Pleno (admin). `recursoNome` é best-effort (null se não resolver). */
    public record PlenoSyncRow(UUID id, String recursoTipo, UUID recursoId, String recursoNome, String status,
                               String externalId, String erro, java.time.Instant ultimoSync, String origem) {}

    @GetMapping("/v1/pleno/sync")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Painel de sincronização Pleno (P2)",
            description = "Lista integracao_sync (recurso, status, erro, último sync, origem). "
                    + "Filtro opcional por status: pendente|sincronizado|erro. Paginado.")
    @SecurityRequirement(name = "bearerAuth")
    public Page<PlenoSyncRow> syncList(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) String q,
                                       Pageable pageable) {
        return integracaoSync.listar(status, q, pageable)
                .map(s -> new PlenoSyncRow(s.getId(), s.getRecursoTipo(), s.getRecursoId(),
                        nomeDoRecurso(s.getRecursoTipo(), s.getRecursoId()), s.getStatus(),
                        s.getExternalId(), s.getErro(), s.getUltimoSync(), s.getOrigem()));
    }

    @PostMapping("/v1/pleno/sync/{id}/retry")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Re-tentar espelhamento de uma linha (P2)",
            description = "Volta a linha para pendente (se não sincronizada), limpa o erro e re-publica o evento de espelhamento.")
    @SecurityRequirement(name = "bearerAuth")
    public PlenoSyncRow syncRetry(@PathVariable UUID id) {
        var s = integracaoSync.retentar(id);
        return new PlenoSyncRow(s.getId(), s.getRecursoTipo(), s.getRecursoId(),
                nomeDoRecurso(s.getRecursoTipo(), s.getRecursoId()), s.getStatus(),
                s.getExternalId(), s.getErro(), s.getUltimoSync(), s.getOrigem());
    }

    @GetMapping("/v1/pleno/pessoas/{plenoId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Consultar pessoa no Pleno (T2.5)", description = "Busca dados de uma pessoa no Pleno por ID. Requer 3s timeout com circuit breaker fallback. Cache via Postgres com TTL. Apenas ADMIN e SUPORTE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dados da pessoa retornados com sucesso", content = @Content(schema = @Schema(implementation = PlenoClient.Resultado.class))),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão"),
            @ApiResponse(responseCode = "404", description = "Pessoa não encontrada no Pleno"),
            @ApiResponse(responseCode = "504", description = "Timeout na chamada ao Pleno, circuit breaker acionado")
    })
    @SecurityRequirement(name = "bearerAuth")
    public PlenoClient.Resultado pessoa(@PathVariable String plenoId) {
        return pleno.pessoa(plenoId);
    }

    @GetMapping("/v1/pleno/imoveis/{plenoId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Consultar imóvel no Pleno (T2.5)", description = "Busca dados de um imóvel no Pleno por ID. Requer 3s timeout com circuit breaker fallback. Cache via Postgres com TTL. Apenas ADMIN e SUPORTE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dados do imóvel retornados com sucesso", content = @Content(schema = @Schema(implementation = PlenoClient.Resultado.class))),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão"),
            @ApiResponse(responseCode = "404", description = "Imóvel não encontrado no Pleno"),
            @ApiResponse(responseCode = "504", description = "Timeout na chamada ao Pleno, circuit breaker acionado")
    })
    @SecurityRequirement(name = "bearerAuth")
    public PlenoClient.Resultado imovel(@PathVariable String plenoId) {
        return pleno.imovel(plenoId);
    }

    @GetMapping("/v1/pleno/solicitacoes/{plenoId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Consultar solicitação no Pleno (T2.5)", description = "Busca dados de uma solicitação no Pleno por ID. Requer 3s timeout com circuit breaker fallback. Cache via Postgres com TTL. Apenas ADMIN e SUPORTE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dados da solicitação retornados com sucesso", content = @Content(schema = @Schema(implementation = PlenoClient.Resultado.class))),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão"),
            @ApiResponse(responseCode = "404", description = "Solicitação não encontrada no Pleno"),
            @ApiResponse(responseCode = "504", description = "Timeout na chamada ao Pleno, circuit breaker acionado")
    })
    @SecurityRequirement(name = "bearerAuth")
    public PlenoClient.Resultado solicitacao(@PathVariable String plenoId) {
        return pleno.solicitacao(plenoId);
    }
}
