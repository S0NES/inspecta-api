package br.com.vistoria.core.modules.financeiro;

import br.com.vistoria.core.modules.disponibilidade.VistoriadorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Pagamentos Vistoriador", description = "Extrato e registro de pagamentos a vistoriadores")
public class VistoriadorFinanceiroController {

    private final PagamentoVistoriadorService service;
    private final VistoriadorService vistoriadorService;
    private final br.com.vistoria.core.shared.PrecoVisibilidadePolicy precoPolicy;

    public record RegistrarPagamentoRequest(@NotNull UUID vistoriadorId, @NotBlank String periodo,
                                            @NotNull @Positive BigDecimal valor, UUID comprovanteArquivoId) {}

    @GetMapping("/v1/vistoriadores/{id}/extrato")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','VISTORIADOR')")
    @Operation(summary = "Obter extrato de vistoriador", description = "Retorna extrato de ganhos e pagamentos para um vistoriador em um período. VISTORIADOR vê apenas seu próprio extrato.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Extrato retornado"),
            @ApiResponse(responseCode = "403", description = "VISTORIADOR tentando ver extrato de outro"),
            @ApiResponse(responseCode = "404", description = "Vistoriador não encontrado")
    })
    @SecurityRequirement(name = "bearerAuth")
    public ExtratoView extrato(@PathVariable UUID id, @RequestParam String periodo) {
        var e = service.extrato(resolverAlvo(id), periodo);
        boolean ocultarPreco = precoPolicy.ocultarPrecoCobrado();
        return new ExtratoView(e.periodo(),
                e.servicos().stream().map(ss -> ServicoExtratoView.de(ss, ocultarPreco)).toList(),
                e.total());
    }

    /** Extrato sem expor a entidade; {@code precoSnapshot} (preço cobrado do cliente) sai nulo/omitido p/ o vistoriador. */
    public record ExtratoView(String periodo, java.util.List<ServicoExtratoView> servicos, BigDecimal total) {}

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record ServicoExtratoView(UUID id, UUID solicitacaoId, UUID tipoServicoId, String status,
                                     java.time.Instant dataDesejada, java.time.Instant dataAgendada,
                                     BigDecimal precoSnapshot, BigDecimal repasseSnapshot) {
        static ServicoExtratoView de(br.com.vistoria.core.modules.solicitacao.SolicitacaoServico ss, boolean ocultarPreco) {
            return new ServicoExtratoView(ss.getId(), ss.getSolicitacaoId(), ss.getTipoServicoId(),
                    ss.getStatus() == null ? null : ss.getStatus().name(),
                    ss.getDataDesejada(), ss.getDataAgendada(),
                    ocultarPreco ? null : ss.getPrecoSnapshot(), ss.getRepasseSnapshot());
        }
    }

    @PostMapping("/v1/pagamentos-vistoriador")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registrar pagamento a vistoriador", description = "Registra um pagamento (ACH/transferência) a um vistoriador com período e valor. Apenas ADMIN pode registrar.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Pagamento registrado"),
            @ApiResponse(responseCode = "400", description = "Dados inválidos"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não é ADMIN")
    })
    @SecurityRequirement(name = "bearerAuth")
    public PagamentoVistoriador registrar(@RequestBody(description = "Dados do pagamento") @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody RegistrarPagamentoRequest body) {
        return service.registrar(body.vistoriadorId(), body.periodo(), body.valor(), body.comprovanteArquivoId());
    }

    /**
     * Id do vistoriador cujo extrato sera consultado. Admin/suporte usam o id do path como veio.
     * Um principal so-VISTORIADOR sempre cai no proprio registro: o inspecta-web manda o {@code usuario_id}
     * no path (nao tem o id da entidade vistoriador a mao — era o que causava o 403 do RELATORIO.md
     * achado #6), entao aceitamos tanto o id da entidade quanto o {@code usuario_id} do dono e
     * resolvemos para o id real. Pedir explicitamente o extrato de outro vistoriador continua 403.
     */
    private UUID resolverAlvo(UUID idPath) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isVistoriadorApenas = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .allMatch(a -> a.equals("ROLE_VISTORIADOR"));
        if (!isVistoriadorApenas) return idPath;

        UUID userId = currentUserId(auth.getPrincipal());
        UUID proprioId = userId == null ? null : vistoriadorService.idPorUsuario(userId).orElse(null);
        if (proprioId == null)
            throw new AccessDeniedException("Usuario autenticado nao e um vistoriador");
        if (!proprioId.equals(idPath) && !idPath.equals(userId))
            throw new AccessDeniedException("Vistoriador so acessa o proprio extrato");
        return proprioId;
    }

    // Mesma logica de extracao de principal usada em SolicitacaoController.currentUserId():
    // em producao o JwtAuthFilter sempre poe um Long (o claim "sub"), mas testes montam o
    // SecurityContext via @WithMockUser, cujo principal e um UserDetails com o id como
    // username. Sem este fallback todo teste de escopo "vistoriador so ve o proprio" falha
    // com 403 mesmo passando o id certo.
    private UUID currentUserId(Object principal) {
        if (principal instanceof UUID l) return l;
        if (principal instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        if (principal instanceof UserDetails ud) {
            try {
                return UUID.fromString(ud.getUsername());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }
}
