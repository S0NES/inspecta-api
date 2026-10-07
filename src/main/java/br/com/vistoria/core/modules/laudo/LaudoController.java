package br.com.vistoria.core.modules.laudo;

import br.com.vistoria.core.modules.arquivo.Arquivo;
import br.com.vistoria.core.modules.arquivo.ArquivoService;
import br.com.vistoria.core.modules.laudo.dto.LaudoResponse;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@Tag(name = "Laudos", description = "Criação, edição e finalização de laudos (inspeção/avaliação) com versionamento imutável (T12)")
public class LaudoController {

    private final LaudoService service;
    private final LaudoRepository laudos;
    private final br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.ServicoRepository servicos;
    private final br.com.vistoria.core.modules.disponibilidade.DisponibilidadeRepositories.VistoriadorRepository vistoriadores;
    private final ArquivoService arquivoService;

    @PostMapping("/v1/laudos")
    @PreAuthorize("hasAnyRole('ADMIN','VISTORIADOR')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Criar novo laudo", description = "Cria um novo laudo (inspeção/avaliação) para um serviço específico. Apenas ADMIN e VISTORIADOR podem criar.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Laudo criado com sucesso", content = @Content(schema = @Schema(implementation = Laudo.class))),
            @ApiResponse(responseCode = "400", description = "servicoId ausente ou serviço não encontrado"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão para criar laudos")
    })
    @SecurityRequirement(name = "bearerAuth")
    public Laudo create(@RequestBody(description = "Payload com servicoId") @org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        return service.criar(UUID.fromString(body.get("servicoId")));
    }

    @PatchMapping("/v1/laudos/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','VISTORIADOR')")
    @Operation(summary = "Atualizar conteúdo de laudo em rascunho", description = "Atualiza o conteúdo JSON de um laudo ainda em rascunho. Uma vez finalizado, o laudo é imutável (T12).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Laudo atualizado com sucesso", content = @Content(schema = @Schema(implementation = Laudo.class))),
            @ApiResponse(responseCode = "400", description = "Laudo já finalizado, não pode ser editado"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão"),
            @ApiResponse(responseCode = "404", description = "Laudo não encontrado")
    })
    @SecurityRequirement(name = "bearerAuth")
    public Laudo patch(@PathVariable UUID id, @RequestBody(description = "Payload com conteudoJson") @org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        return service.atualizarConteudo(id, body.get("conteudoJson"));
    }

    @PostMapping("/v1/laudos/{id}/finalizar")
    @PreAuthorize("hasAnyRole('ADMIN','VISTORIADOR')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Finalizar laudo (tornar imutável)", description = "Finaliza um laudo, tornando-o imutável. Publishes evento para processamento posterior (assinatura, etc).")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Laudo finalizado com sucesso, processamento iniciado"),
            @ApiResponse(responseCode = "400", description = "Laudo já finalizado ou inválido"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão"),
            @ApiResponse(responseCode = "404", description = "Laudo não encontrado")
    })
    @SecurityRequirement(name = "bearerAuth")
    public void finalizar(@PathVariable UUID id) { service.finalizar(id); }

    @PostMapping("/v1/laudos/{id}/nova-versao")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Criar nova versão de laudo (T12)", description = "Cria uma nova versão de um laudo já finalizado. Laudo assinado é imutável; mudanças requerem nova versão (T12 - invariant #12).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Nova versão criada com sucesso", content = @Content(schema = @Schema(implementation = Laudo.class))),
            @ApiResponse(responseCode = "400", description = "Laudo não está em estado válido para nova versão"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão"),
            @ApiResponse(responseCode = "404", description = "Laudo não encontrado")
    })
    @SecurityRequirement(name = "bearerAuth")
    public Laudo novaVersao(@PathVariable UUID id) { return service.novaVersao(id); }

    @GetMapping("/v1/laudos")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA','IMOB_OPERACAO','VISTORIADOR')")
    @Operation(summary = "Listar laudos com row-based scoping", description = "Lista laudos com aplicação de row-based scoping por perfil. "
            + "VISTORIADOR vê apenas laudos de seus servicos; ADMIN/SUPORTE/imob_* veem com RLS por tenant. "
            + "Suporta paginação e filtros opcionais (solicitacaoServicoId, status).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista de laudos retornada com sucesso"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui perfil para listar")
    })
    @SecurityRequirement(name = "bearerAuth")
    public Page<LaudoResponse> list(
            @RequestParam(required = false) UUID solicitacaoServicoId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String origem,
            Pageable p) {
        UUID currentUserId = currentUserId();
        var roles = SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                .stream().map(GrantedAuthority::getAuthority).toList();

        List<Laudo> result = laudos.findAll();

        // Apply query param filters
        if (solicitacaoServicoId != null) {
            result = result.stream()
                    .filter(l -> l.getSolicitacaoServicoId().equals(solicitacaoServicoId))
                    .collect(Collectors.toList());
        }
        if (status != null && !status.isBlank()) {
            try {
                Laudo.Status statusEnum = Laudo.Status.valueOf(status);
                result = result.stream()
                        .filter(l -> l.getStatus() == statusEnum)
                        .collect(Collectors.toList());
            } catch (IllegalArgumentException e) {
                result = List.of();
            }
        }
        if (origem != null && !origem.isBlank()) {
            try {
                Laudo.Origem origemEnum = Laudo.Origem.valueOf(origem);
                result = result.stream()
                        .filter(l -> l.getOrigem() == origemEnum)
                        .collect(Collectors.toList());
            } catch (IllegalArgumentException e) {
                result = List.of();
            }
        }

        // VISTORIADOR: only laudos for servicos assigned to them
        if (roles.contains("ROLE_VISTORIADOR") && currentUserId != null) {
            var vistoriador = vistoriadores.findByUsuarioId(currentUserId);
            if (vistoriador.isPresent()) {
                UUID vistoriadorId = vistoriador.get().getId();
                result = result.stream()
                        .filter(laudo -> {
                            var servico = servicos.findById(laudo.getSolicitacaoServicoId()).orElse(null);
                            return servico != null && servico.getVistoriadorId() != null &&
                                   servico.getVistoriadorId().equals(vistoriadorId);
                        })
                        .collect(Collectors.toList());
            } else {
                result = List.of();
            }
        }

        // ADMIN/SUPORTE/imob roles: RLS applies in DB
        List<LaudoResponse> responses = result.stream().map(this::toResponse).collect(Collectors.toList());
        return new PageImpl<>(responses, p, responses.size());
    }

    private LaudoResponse toResponse(Laudo l) {
        Long arquivoTamanho = null;
        String arquivoMime = null;
        if (l.getPdfArquivoId() != null) {
            Arquivo pdf = arquivoService.get(l.getPdfArquivoId());
            arquivoTamanho = pdf.getTamanho();
            arquivoMime = pdf.getMime();
        }

        int fotosCount = servicos.findById(l.getSolicitacaoServicoId())
                .map(servico -> arquivoService.listarImagensPorDono("solicitacao", servico.getSolicitacaoId()).size())
                .orElse(0);

        return LaudoResponse.from(l, arquivoTamanho, arquivoMime, fotosCount);
    }

    @GetMapping("/v1/laudos/{id}")
    @Operation(summary = "Obter laudo por ID", description = "Retorna um laudo específico. Acesso público se laudo for compartilhado via link (T2.10), autenticado caso contrário.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Laudo retornado com sucesso", content = @Content(schema = @Schema(implementation = Laudo.class))),
            @ApiResponse(responseCode = "404", description = "Laudo não encontrado")
    })
    public Laudo get(@PathVariable UUID id) { return service.get(id); }

    private UUID currentUserId() {
        Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (p instanceof UUID l) return l;
        if (p instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        if (p instanceof UserDetails ud) {
            try {
                return UUID.fromString(ud.getUsername());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

}
