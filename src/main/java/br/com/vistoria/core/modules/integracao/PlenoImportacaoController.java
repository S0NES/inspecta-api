package br.com.vistoria.core.modules.integracao;

import br.com.vistoria.core.modules.integracao.PlenoImportacaoRepositories.PlenoImportacaoVistoriaRepository;
import br.com.vistoria.core.shared.exception.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Acompanhamento operacional (equipe Inspecta) da "volta" do ciclo Pleno: solicitações já
 * espelhadas cuja vistoria roda NA Pleno até terminar, com download do PDF e envio ao
 * pipeline de assinatura. Leitura pura — o inspecta-api NUNCA chama a API da Pleno; quem faz as
 * chamadas HTTP é o inspecta-worker. Aqui só se lê o estado ({@code pleno_importacao_vistoria})
 * e, no {@code verificar}, se antecipa a próxima varredura do poller.
 */
@RestController
@RequestMapping("/v1/pleno/importacao")
@RequiredArgsConstructor
@Tag(name = "Importação Pleno", description = "Estado operacional da importação de vistorias concluídas na Pleno (leitura pura)")
public class PlenoImportacaoController {

    private final PlenoImportacaoService service;
    private final PlenoImportacaoVistoriaRepository repository;

    /** Estado da importação de uma vistoria para o painel operacional. */
    public record PlenoImportacaoResumo(UUID id, UUID solicitacaoServicoId, Integer staSolCodigoAtual,
                                        String status, int tentativasVerificacao, Instant ultimaVerificacaoEm,
                                        Instant proximaVerificacaoEm, String linkPdfExterno, UUID pdfArquivoId,
                                        UUID laudoId, Instant enviadoParaAssinaturaEm, String erroMensagem,
                                        Instant criadoEm, Instant atualizadoEm) {
        static PlenoImportacaoResumo of(PlenoImportacaoVistoria v) {
            return new PlenoImportacaoResumo(v.getId(), v.getSolicitacaoServicoId(), v.getStaSolCodigoAtual(),
                    v.getStatusImportacao(), v.getTentativasVerificacao(), v.getUltimaVerificacaoEm(),
                    v.getProximaVerificacaoEm(), v.getLinkPdfExterno(), v.getPdfArquivoId(), v.getLaudoId(),
                    v.getEnviadoParaAssinaturaEm(), v.getErroMensagem(), v.getCriadoEm(), v.getAtualizadoEm());
        }
    }

    @GetMapping("/{solicitacaoServicoId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Estado da importação Pleno de um serviço",
            description = "Retorna o acompanhamento da vistoria importada da Pleno para o serviço informado.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Estado retornado"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão"),
            @ApiResponse(responseCode = "404", description = "Nenhuma importação Pleno para esse serviço")
    })
    @SecurityRequirement(name = "bearerAuth")
    public PlenoImportacaoResumo porServico(@PathVariable UUID solicitacaoServicoId) {
        return repository.findBySolicitacaoServicoId(solicitacaoServicoId)
                .map(PlenoImportacaoResumo::of)
                .orElseThrow(() -> new NotFoundException("Importacao Pleno do servico " + solicitacaoServicoId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Lista operacional de importações Pleno",
            description = "Lista o acompanhamento das importações Pleno, opcionalmente filtrado por "
                    + "status (SOLICITADA|EM_ANDAMENTO|FINALIZADA_SEM_PDF|FINALIZADA_COM_PDF|PDF_BAIXADO"
                    + "|ENVIADA_PARA_ASSINATURA|CANCELADA|ERRO). Paginado.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista retornada"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão")
    })
    @SecurityRequirement(name = "bearerAuth")
    public Page<PlenoImportacaoResumo> listar(@RequestParam(required = false) String status, Pageable pageable) {
        List<PlenoImportacaoVistoria> linhas = (status == null || status.isBlank())
                ? repository.findAll()
                : service.listarPorStatus(PlenoImportacaoVistoria.Status.valueOf(status.trim().toUpperCase()));

        int from = (int) Math.min((long) pageable.getPageNumber() * pageable.getPageSize(), linhas.size());
        int to = (int) Math.min((long) from + pageable.getPageSize(), linhas.size());
        List<PlenoImportacaoResumo> pagina = linhas.subList(from, to).stream().map(PlenoImportacaoResumo::of).toList();
        return new PageImpl<>(pagina, pageable, linhas.size());
    }

    @PostMapping("/{solicitacaoServicoId}/verificar")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Forçar rechecagem da importação Pleno",
            description = "Antecipa a próxima verificação (proxima_verificacao_em = agora) para o poller do "
                    + "inspecta-worker reprocessar a linha no próximo ciclo. NÃO faz nenhuma chamada HTTP à Pleno.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Rechecagem agendada para o próximo ciclo do poller"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão"),
            @ApiResponse(responseCode = "404", description = "Nenhuma importação Pleno para esse serviço")
    })
    @SecurityRequirement(name = "bearerAuth")
    public PlenoImportacaoResumo verificar(@PathVariable UUID solicitacaoServicoId) {
        PlenoImportacaoVistoria v = repository.findBySolicitacaoServicoId(solicitacaoServicoId)
                .orElseThrow(() -> new NotFoundException("Importacao Pleno do servico " + solicitacaoServicoId));
        Instant agora = Instant.now();
        v.setProximaVerificacaoEm(agora);
        v.setAtualizadoEm(agora);
        return PlenoImportacaoResumo.of(repository.save(v));
    }
}
