package br.com.vistoria.core.modules.solicitacao;

import br.com.vistoria.core.config.TenantContext;
import br.com.vistoria.core.modules.disponibilidade.DisponibilidadeRepositories.VistoriadorRepository;
import br.com.vistoria.core.modules.financeiro.PagamentoService;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.SolicitacaoRepository;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.ServicoRepository;
import br.com.vistoria.core.modules.solicitacao.dto.CreateSolicitacaoRequest;
import br.com.vistoria.core.modules.solicitacao.dto.SolicitacaoListItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/solicitacoes")
@RequiredArgsConstructor
@Tag(name = "Solicitações", description = "Gerenciamento de solicitações de vistoria com row-based scoping por perfil (T2.4)")
public class SolicitacaoController {

    /** Chaves de status derivado expostas pela API, na ordem em que o painel as exibe. */
    private static final List<String> STATUS_KEYS =
            List.of("solicitada", "atribuida", "agendada", "em_campo", "concluida", "cancelada");

    private final SolicitacaoService service;
    private final SolicitacaoQueryService queryService;
    private final SolicitacaoRepository solicitacoes;
    private final ServicoRepository servicos;
    private final VistoriadorRepository vistoriadores;
    private final PagamentoService pagamentoService;
    private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SolicitacaoController.class);

    @org.springframework.beans.factory.annotation.Value("${inspecta.payment.auto-charge:true}")
    private boolean autoCharge;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA','IMOB_OPERACAO','CLIENTE')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Criar nova solicitação de vistoria", description = "Cria uma nova solicitação de vistoria. O usuário criador é associado automaticamente como cliente. Requer autenticação JWT.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Solicitação criada com sucesso", content = @Content(schema = @Schema(implementation = Solicitacao.class))),
            @ApiResponse(responseCode = "400", description = "Dados de entrada inválidos"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui perfil para criar solicitação")
    })
    @SecurityRequirement(name = "bearerAuth")
    public Solicitacao create(
            @RequestBody(description = "Dados da solicitação de vistoria") @Valid @org.springframework.web.bind.annotation.RequestBody CreateSolicitacaoRequest body) {
        UUID userId = currentUserId();
        Solicitacao s = service.criar(body, userId);

        // Cobrança AbacatePay assíncrona (via inspecta-worker): gera o link e envia para o
        // cliente do fluxo reservado (sem imobiliária). Nunca bloqueia a criação da
        // solicitação — o poller/retry reprocessa se algo falhar.
        //
        // Fluxo B2B (imobiliariaId != null): a solicitação é pós-paga — o valor entra num
        // item de extrato e é liquidado depois via fatura consolidada (ExtratoService),
        // então NÃO se dispara cobrança individual aqui.
        if (autoCharge && s.getImobiliariaId() == null) {
            try {
                pagamentoService.criarCobranca(s.getId());
            } catch (RuntimeException e) {
                // TODO(fase-abacatepay): gerar link + notificar imobiliaria
                log.warn("[pagamento] auto-cobranca nao disparada para solicitacao {}: {}", s.getId(), e.getMessage());
            }
        }
        return s;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA','IMOB_OPERACAO','VISTORIADOR','CLIENTE')")
    @Operation(summary = "Listar solicitações com row-based scoping", description = "Lista solicitações de vistoria com aplicação de row-based scoping por perfil (T2.4). "
            + "VISTORIADOR vê apenas suas atribuições; CLIENTE vê apenas suas solicitações; "
            + "ADMIN/SUPORTE/imob_* veem com RLS por tenant. Suporta paginação via query params 'page', 'size', 'sort'.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista de solicitações retornada com sucesso"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui perfil para listar")
    })
    @SecurityRequirement(name = "bearerAuth")
    public Page<SolicitacaoListItem> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID cidadeId,
            @RequestParam(required = false) Boolean urgente,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID imobiliariaId,
            Pageable p) {
        return queryService.toListItems(scoped(q, cidadeId, urgente, status, imobiliariaId, p));
    }

    @GetMapping("/resumo")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA','IMOB_OPERACAO','VISTORIADOR','CLIENTE')")
    @Operation(summary = "Contagem de solicitações por status",
            description = "Totais por status derivado, respeitando o mesmo row scoping e os mesmos "
                    + "filtros da listagem. Alimenta os cartões de métrica do painel.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Contagens retornadas"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado")
    })
    @SecurityRequirement(name = "bearerAuth")
    public Map<String, Long> resumo(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID cidadeId,
            @RequestParam(required = false) Boolean urgente) {
        // Reaproveita a consulta da listagem por status: o painel não pode contar de um jeito
        // e a lista mostrar de outro. Só o total interessa, então pede uma página de tamanho 1.
        Pageable umaLinha = PageRequest.of(0, 1);
        Map<String, Long> contagens = new LinkedHashMap<>();
        for (String statusKey : STATUS_KEYS) {
            contagens.put(statusKey, scoped(q, cidadeId, urgente, statusKey, null, umaLinha).getTotalElements());
        }
        contagens.put("total", scoped(q, cidadeId, urgente, null, null, umaLinha).getTotalElements());
        return contagens;
    }

    /**
     * row scoping por perfil, aplicado dentro da própria consulta filtrada — assim
     * filtro, contagem e paginação concordam entre si.
     */
    private Page<Solicitacao> scoped(String q, UUID cidadeId, Boolean urgente, String status, UUID imobiliariaId, Pageable p) {
        UUID currentUserId = currentUserId();
        var roles = SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                .stream().map(GrantedAuthority::getAuthority).toList();

        String busca = (q == null || q.isBlank()) ? null : q.trim();
        String statusKey = (status == null || status.isBlank()) ? null : status.trim().toLowerCase();

        UUID clienteId = null;
        UUID vistoriadorId = null;

        // VISTORIADOR: only solicitações with a serviço assigned to them
        if (roles.contains("ROLE_VISTORIADOR") && currentUserId != null) {
            var vistoriador = vistoriadores.findByUsuarioId(currentUserId);
            if (vistoriador.isEmpty()) return new PageImpl<>(java.util.List.of(), p, 0);
            vistoriadorId = vistoriador.get().getId();
        } else if (roles.contains("ROLE_CLIENTE") && currentUserId != null) {
            // CLIENTE: only their own solicitações
            clienteId = currentUserId;
        }
        // ADMIN/SUPORTE/imob roles: sem restrição de linha aqui — RLS filtra o tenant no banco.

        return solicitacoes.buscar(busca, cidadeId, urgente, statusKey, imobiliariaId, clienteId, vistoriadorId, p);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA','IMOB_OPERACAO','VISTORIADOR','CLIENTE')")
    @Operation(summary = "Obter detalhe de uma solicitação", description = "Retorna os detalhes de uma solicitação específica com row-based scoping (T2.4). "
            + "VISTORIADOR vê apenas suas atribuições; CLIENTE vê apenas suas solicitações; "
            + "ADMIN/SUPORTE/imob_* veem com RLS por tenant.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Solicitação retornada com sucesso", content = @Content(schema = @Schema(implementation = Solicitacao.class))),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Acesso negado — não é o vistoriador/cliente responsável"),
            @ApiResponse(responseCode = "404", description = "Solicitação não encontrada")
    })
    @SecurityRequirement(name = "bearerAuth")
    public Solicitacao get(@PathVariable UUID id) {
        // row-scoping enforcement — same rules as list()
        UUID currentUserId = currentUserId();
        var roles = SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                .stream().map(GrantedAuthority::getAuthority).toList();

        Solicitacao s = solicitacoes.findById(id)
                .orElseThrow(() -> new br.com.vistoria.core.shared.exception.NotFoundException("Solicitacao " + id));

        // VISTORIADOR: only their own assigned solicitações
        if (roles.contains("ROLE_VISTORIADOR") && currentUserId != null) {
            var vistoriador = vistoriadores.findByUsuarioId(currentUserId);
            if (vistoriador.isPresent()) {
                var vistoriadorPage = solicitacoes.findByVistoriadorId(vistoriador.get().getId(),
                    org.springframework.data.domain.Pageable.unpaged());
                if (!vistoriadorPage.getContent().stream().anyMatch(x -> x.getId().equals(id))) {
                    throw new org.springframework.security.access.AccessDeniedException("Acesso negado");
                }
            } else {
                throw new org.springframework.security.access.AccessDeniedException("Acesso negado");
            }
            return s;
        }

        // CLIENTE: only their own solicitações
        if (roles.contains("ROLE_CLIENTE") && currentUserId != null) {
            if (s.getClienteId() == null || !s.getClienteId().equals(currentUserId)) {
                throw new org.springframework.security.access.AccessDeniedException("Acesso negado");
            }
            return s;
        }

        // ADMIN/SUPORTE/imob roles: RLS applies in DB
        return s;
    }

    /** Serviço de uma solicitação, para as ações por-serviço da UI (reatribuir, cancelar). */
    public record ServicoItem(UUID id, UUID tipoServicoId, String status, UUID vistoriadorId,
                              java.time.Instant dataAgendada, java.math.BigDecimal precoSnapshot) {}

    @GetMapping("/{id}/servicos")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA','IMOB_OPERACAO')")
    @Operation(summary = "Listar serviços de uma solicitação",
            description = "Serviços (SolicitacaoServico) da solicitação, com id, tipo, status e vistoriador atual — "
                    + "base para as ações de reatribuir/cancelar por serviço. RLS por tenant.")
    @SecurityRequirement(name = "bearerAuth")
    public List<ServicoItem> servicos(@PathVariable UUID id) {
        return servicos.findBySolicitacaoId(id).stream()
                .map(ss -> new ServicoItem(ss.getId(), ss.getTipoServicoId(), ss.getStatus().name(),
                        ss.getVistoriadorId(), ss.getDataAgendada(), ss.getPrecoSnapshot()))
                .toList();
    }

    @GetMapping("/{id}/status")
    @Operation(summary = "Obter status derivado de uma solicitação", description = "Retorna o status derivado de uma solicitação (calculado a partir do estado dos serviços associados).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status retornado com sucesso"),
            @ApiResponse(responseCode = "404", description = "Solicitação não encontrada")
    })
    public Map<String, String> status(@PathVariable UUID id) {
        return Map.of("status", service.statusDerivado(id));
    }

    @PostMapping("/servicos/{servicoId}/cancelar")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cancelar um serviço de uma solicitação", description = "Cancela um serviço específico dentro de uma solicitação de vistoria. Apenas ADMIN, SUPORTE e IMOB_GERENCIA podem executar esta ação.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Serviço cancelado com sucesso"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão para cancelar"),
            @ApiResponse(responseCode = "404", description = "Serviço não encontrado")
    })
    @SecurityRequirement(name = "bearerAuth")
    public void cancelar(@PathVariable UUID servicoId) {
        service.cancelarServico(servicoId);
    }

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
