package br.com.vistoria.core.modules.disponibilidade;

import br.com.vistoria.core.config.TenantContext;
import br.com.vistoria.core.modules.disponibilidade.DisponibilidadeRepositories.*;
import br.com.vistoria.core.modules.disponibilidade.dto.VistoriadorResponse;
import br.com.vistoria.core.modules.integracao.IntegracaoSyncService;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.ServicoRepository;
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
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/vistoriadores")
@RequiredArgsConstructor
@Tag(name = "Disponibilidade", description = "Gerenciamento de vistoriadores, disponibilidade e exceções (T2.3 - tenant-scoped)")
public class DisponibilidadeController {

    private final VistoriadorRepository vistoriadores;
    private final DisponibilidadeRepository disponibilidades;
    private final ExcecaoRepository excecoes;
    private final ServicoRepository servicos;
    private final IntegracaoSyncService integracaoSync;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA')")
    @Operation(summary = "Listar vistoriadores")
    @SecurityRequirement(name = "bearerAuth")
    public List<VistoriadorResponse> list() {
        return vistoriadores.findAll().stream().map(VistoriadorResponse::from).toList();
    }

    public record VistoriadorPatch(Boolean ativo, UUID[] cidades, java.math.BigDecimal distanciaMaxKm,
                                   Double latBase, Double lngBase) {}

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Atualizar vistoriador (permissão, praças, raio)")
    @SecurityRequirement(name = "bearerAuth")
    public VistoriadorResponse patch(@PathVariable UUID id, @org.springframework.web.bind.annotation.RequestBody VistoriadorPatch b) {
        Vistoriador v = vistoriadores.findById(id)
                .orElseThrow(() -> new br.com.vistoria.core.shared.exception.NotFoundException("Vistoriador " + id));
        if (b.ativo() != null) v.setAtivo(b.ativo());
        if (b.cidades() != null) v.setCidades(b.cidades());
        if (b.distanciaMaxKm() != null) v.setDistanciaMaxKm(b.distanciaMaxKm());
        if (b.latBase() != null) v.setLatBase(b.latBase());
        if (b.lngBase() != null) v.setLngBase(b.lngBase());
        return VistoriadorResponse.from(vistoriadores.save(v));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Criar novo vistoriador", description = "Cria um novo perfil de vistoriador no sistema. Apenas ADMIN pode criar vistoriadores.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Vistoriador criado com sucesso", content = @Content(schema = @Schema(implementation = VistoriadorResponse.class))),
            @ApiResponse(responseCode = "400", description = "Dados de entrada inválidos"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão para criar vistoriadores")
    })
    @SecurityRequirement(name = "bearerAuth")
    public VistoriadorResponse create(
            @RequestBody(description = "Dados do vistoriador") @org.springframework.web.bind.annotation.RequestBody Vistoriador v) {
        Vistoriador salvo = vistoriadores.save(v);
        // cadastro externo (usu_codigo em plenoId) -> vincula; senao -> espelhamento assincrono.
        if (v.getPlenoId() != null && !v.getPlenoId().isBlank()) {
            integracaoSync.vincularExterno("vistoriador", salvo.getId(), v.getPlenoId().trim());
        } else {
            integracaoSync.enfileirar("vistoriador", salvo.getId());
        }
        return VistoriadorResponse.from(salvo);
    }

    /**
     * ADMIN/SUPORTE acessam a agenda de qualquer vistoriador; um VISTORIADOR só a
     * própria. As tabelas `disponibilidades`/`disponibilidade_excecoes` não têm RLS
     * (escopo por vistoriador, não por tenant), então a checagem é aqui — sem ela,
     * `{id}` no path seria a identidade e um vistoriador editaria a agenda de outro.
     */
    private void exigeAcessoVistoriador(UUID vistoriadorId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean adminOuSuporte = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_SUPORTE"));
        if (adminOuSuporte) return;
        UUID userId = currentUserId();
        boolean dono = userId != null && vistoriadores.findById(vistoriadorId)
                .map(v -> userId.equals(v.getUsuarioId()))
                .orElse(false);
        if (!dono) throw new AccessDeniedException("Vistoriador só acessa a própria disponibilidade");
    }

    private UUID currentUserId() {
        Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (p instanceof UUID l) return l;
        if (p instanceof String s) {
            try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
        }
        if (p instanceof UserDetails ud) {
            try { return UUID.fromString(ud.getUsername()); } catch (IllegalArgumentException e) { return null; }
        }
        return null;
    }

    @GetMapping("/{id}/disponibilidade")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','VISTORIADOR')")
    @Operation(summary = "Grade de disponibilidade semanal de um vistoriador")
    @SecurityRequirement(name = "bearerAuth")
    public List<Disponibilidade> slots(@PathVariable UUID id) {
        exigeAcessoVistoriador(id);
        return disponibilidades.findByVistoriadorIdOrderByDiaSemanaAscHoraInicioAsc(id);
    }

    @DeleteMapping("/{id}/disponibilidade/{slotId}")
    @PreAuthorize("hasAnyRole('ADMIN','VISTORIADOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remover slot de disponibilidade")
    @SecurityRequirement(name = "bearerAuth")
    public void removeSlot(@PathVariable UUID id, @PathVariable UUID slotId) {
        exigeAcessoVistoriador(id);
        Disponibilidade s = disponibilidades.findById(slotId)
                .filter(x -> x.getVistoriadorId().equals(id))
                .orElseThrow(() -> new br.com.vistoria.core.shared.exception.NotFoundException("Slot " + slotId));
        disponibilidades.delete(s);
    }

    @GetMapping("/{id}/excecoes")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','VISTORIADOR')")
    @Operation(summary = "Exceções (folgas/bloqueios) de um vistoriador")
    @SecurityRequirement(name = "bearerAuth")
    public List<DisponibilidadeExcecao> excecoesList(@PathVariable UUID id) {
        exigeAcessoVistoriador(id);
        return excecoes.findByVistoriadorIdOrderByDataInicioAsc(id);
    }

    @DeleteMapping("/{id}/excecoes/{excecaoId}")
    @PreAuthorize("hasAnyRole('ADMIN','VISTORIADOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remover exceção de disponibilidade")
    @SecurityRequirement(name = "bearerAuth")
    public void removeExcecao(@PathVariable UUID id, @PathVariable UUID excecaoId) {
        exigeAcessoVistoriador(id);
        DisponibilidadeExcecao e = excecoes.findById(excecaoId)
                .filter(x -> x.getVistoriadorId().equals(id))
                .orElseThrow(() -> new br.com.vistoria.core.shared.exception.NotFoundException("Excecao " + excecaoId));
        excecoes.delete(e);
    }

    @PostMapping("/{id}/disponibilidade")
    @PreAuthorize("hasAnyRole('ADMIN','VISTORIADOR')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Adicionar slot de disponibilidade", description = "Adiciona um horário de disponibilidade para um vistoriador. VISTORIADOR pode adicionar sua própria disponibilidade; ADMIN pode adicionar para qualquer vistoriador.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Slot adicionado com sucesso", content = @Content(schema = @Schema(implementation = Disponibilidade.class))),
            @ApiResponse(responseCode = "400", description = "hora_fim deve ser depois de hora_inicio ou dados inválidos"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão ou tenta adicionar para outro vistoriador"),
            @ApiResponse(responseCode = "404", description = "Vistoriador não encontrado")
    })
    @SecurityRequirement(name = "bearerAuth")
    public Disponibilidade addSlot(
            @PathVariable UUID id,
            @RequestBody(description = "Dados do slot de disponibilidade") @org.springframework.web.bind.annotation.RequestBody Disponibilidade d) {
        exigeAcessoVistoriador(id);
        d.setVistoriadorId(id);
        if (!d.getHoraFim().isAfter(d.getHoraInicio()))
            throw new IllegalArgumentException("hora_fim deve ser depois de hora_inicio");
        return disponibilidades.save(d);
    }

    @PostMapping("/{id}/excecoes")
    @PreAuthorize("hasAnyRole('ADMIN','VISTORIADOR')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Adicionar exceção de disponibilidade", description = "Adiciona uma exceção (dia/período indisponível) para um vistoriador. VISTORIADOR pode adicionar suas próprias exceções; ADMIN pode adicionar para qualquer vistoriador.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Exceção adicionada com sucesso", content = @Content(schema = @Schema(implementation = DisponibilidadeExcecao.class))),
            @ApiResponse(responseCode = "400", description = "Dados de entrada inválidos"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão ou tenta adicionar para outro vistoriador"),
            @ApiResponse(responseCode = "404", description = "Vistoriador não encontrado")
    })
    @SecurityRequirement(name = "bearerAuth")
    public DisponibilidadeExcecao addExcecao(
            @PathVariable UUID id,
            @RequestBody(description = "Dados da exceção de disponibilidade") @org.springframework.web.bind.annotation.RequestBody DisponibilidadeExcecao e) {
        exigeAcessoVistoriador(id);
        e.setVistoriadorId(id);
        return excecoes.save(e);
    }

    @GetMapping("/disponibilidade/geral")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA','IMOB_OPERACAO')")
    @Operation(summary = "Listar disponibilidade geral com tenant-scoping (T2.3)", description = "Lista slots de disponibilidade de todos os vistoriadores com paginação. IMOB_GERENCIA/IMOB_OPERACAO veem apenas vistoriadores de sua imobiliária (tenant-scoped, T2.3); ADMIN/SUPORTE veem todos. Suporta paginação via query params 'page', 'size', 'sort'. Retorna Page<Disponibilidade> com contagem total.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista paginada de disponibilidades retornada com sucesso", content = @Content(schema = @Schema(implementation = Page.class))),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão para listar")
    })
    @SecurityRequirement(name = "bearerAuth")
    public Page<Disponibilidade> geral(Pageable p) {
        // T2.3(b): tenant-scoped pagination for imob_gerencia/imob_operacao, applied at the DB
        // level via findByVistoriadorIdIn (correct page counts/ordering, unlike a post-pagination filter).
        // V25: usuário vinculado a N imobiliárias vê os vistoriadores de todas elas.
        List<UUID> tenants = TenantContext.getAll();
        if (!tenants.isEmpty()) {
            var vistoriadorIds = servicos.findDistinctVistoriadorIdsByImobiliariaIn(tenants);
            return disponibilidades.findByVistoriadorIdIn(vistoriadorIds, p);
        }
        return disponibilidades.findAll(p);
    }
}
