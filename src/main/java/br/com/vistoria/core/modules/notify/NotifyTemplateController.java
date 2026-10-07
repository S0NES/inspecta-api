package br.com.vistoria.core.modules.notify;

import br.com.vistoria.core.modules.usuario.Usuario;
import br.com.vistoria.core.modules.usuario.UsuarioRepository;
import br.com.vistoria.core.shared.outbox.OutboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CRUD dos templates de mensagem + envio em massa. Só ADMIN/SUPORTE.
 *
 * <p>Os templates são versionados: {@code POST /{uuid}/versao} publica uma nova versão sem
 * apagar a anterior. {@code POST /{uuid}/enviar} enfileira um evento {@code notify.requested.bulk}
 * por usuário — o {@code eventId} é determinístico ({@code bulk-<uuid>-<usuarioId>}), então
 * reenviar o mesmo lote não duplica e-mail (guard de idempotência do inspecta-messenger).</p>
 */
@RestController
@RequestMapping("/v1/notify/templates")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Templates de mensagem", description = "CRUD de templates de e-mail/SMS/push e envio em massa")
public class NotifyTemplateController {

    private final NotifyTemplateService service;
    private final UsuarioRepository usuarios;
    private final OutboxService outbox;

    public record MessageTemplateResponse(UUID uuid, int versao, String canal, String nome,
                                          String assunto, String corpo, boolean ativo,
                                          Instant atualizadoEm) {
        static MessageTemplateResponse from(MessageTemplate t) {
            return new MessageTemplateResponse(t.getUuid(), t.getVersao(), t.getCanal(), t.getNome(),
                    t.getAssunto(), t.getCorpo(), t.isAtivo(), t.getAtualizadoEm());
        }
    }

    public record CriarRequest(String canal, String nome, String assunto, String corpo) {}
    public record NovaVersaoRequest(String nome, String assunto, String corpo) {}
    /** Pelo menos um de {@code usuarioIds}/{@code emails}. `emails` é o caminho da tela do template. */
    public record EnviarRequest(List<UUID> usuarioIds, List<String> emails) {}

    /** Validação de formato leve — o gateway de e-mail é a autoridade final sobre entregabilidade. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    public record UtilidadeResponse(String utilidade, String rotulo, List<String> variaveis,
                                    UUID templateId, String templateNome) {}
    public record DefinirUtilidadeRequest(UUID templateId) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Listar templates (uma linha por template, filtro opcional por canal)")
    public List<MessageTemplateResponse> listar(@RequestParam(required = false) String canal) {
        return service.listar(canal).stream().map(MessageTemplateResponse::from).toList();
    }

    @GetMapping("/{uuid}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Obter um template (maior versão ativa)")
    public MessageTemplateResponse get(@PathVariable UUID uuid) {
        return MessageTemplateResponse.from(service.porUuid(uuid));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Criar um template novo (versão 1)")
    public MessageTemplateResponse criar(@RequestBody CriarRequest body) {
        return MessageTemplateResponse.from(
                service.criar(body.canal(), body.nome(), body.assunto(), body.corpo()));
    }

    @PostMapping("/{uuid}/versao")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Publicar uma nova versão de um template existente")
    public MessageTemplateResponse novaVersao(@PathVariable UUID uuid, @RequestBody NovaVersaoRequest body) {
        return MessageTemplateResponse.from(
                service.novaVersao(uuid, body.nome(), body.assunto(), body.corpo()));
    }

    @DeleteMapping("/{uuid}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Desativar um template (todas as versões)")
    public void desativar(@PathVariable UUID uuid) {
        service.desativar(uuid);
    }

    @PostMapping("/{uuid}/enviar")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Enfileirar o template para uma lista de e-mails (idempotente por destinatário)")
    // @Transactional obrigatorio: OutboxService.publish e @Transactional(MANDATORY) e sem uma
    // transacao ativa no controller estourava IllegalTransactionStateException -> 500
    // (RELATORIO.md achado #4). Mesmo padrao de AtribuicaoController.
    @Transactional
    public Map<String, Object> enviar(@PathVariable UUID uuid, @RequestBody EnviarRequest body) {
        MessageTemplate tpl = service.porUuid(uuid); // 404 se não existir/estiver inativo

        // email normalizado -> nome. LinkedHashMap dedup: o mesmo endereço no lote (via lista
        // colada e via usuário) só gera uma entrega. O eventId determinístico por e-mail cobre
        // o reenvio do mesmo lote (guard de idempotência do inspecta-messenger).
        LinkedHashMap<String, String> destinatarios = new LinkedHashMap<>();

        if (body.usuarioIds() != null && !body.usuarioIds().isEmpty()) {
            for (Usuario u : usuarios.findAllById(body.usuarioIds())) {
                if (u.getEmail() == null || u.getEmail().isBlank()) continue;
                destinatarios.putIfAbsent(u.getEmail().trim().toLowerCase(),
                        u.getNome() == null ? "" : u.getNome());
            }
        }
        if (body.emails() != null) {
            for (String raw : body.emails()) {
                String email = raw == null ? "" : raw.trim().toLowerCase();
                if (EMAIL.matcher(email).matches()) {
                    destinatarios.putIfAbsent(email, "");
                }
            }
        }

        if (destinatarios.isEmpty()) {
            throw new IllegalArgumentException("Informe ao menos um e-mail de destino valido");
        }

        destinatarios.forEach((email, nome) -> outbox.publish("notify.requested.bulk", Map.of(
                "eventId", "bulk-" + uuid + "-" + email,
                "canal", "email",
                "tipoComunicacao", "campanha",
                "templateId", uuid.toString(),
                "destinatario", email,
                "variaveis", Map.of("nome", nome))));

        log.info("[msg-template] envio em massa uuid={} template='{}' enfileirados={}",
                uuid, tpl.getNome(), destinatarios.size());
        return Map.of("enfileirados", destinatarios.size());
    }

    @GetMapping("/utilidades")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Listar as utilidades transacionais e o template vinculado a cada uma")
    public List<UtilidadeResponse> utilidades() {
        return service.listarUtilidades().stream()
                .map(v -> new UtilidadeResponse(v.utilidade(), v.rotulo(), v.variaveis(),
                        v.templateId(), v.templateNome()))
                .toList();
    }

    @PutMapping("/utilidades/{utilidade}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Definir qual template de e-mail atende uma utilidade (somente ADMIN)")
    public void definirUtilidade(@PathVariable String utilidade, @RequestBody DefinirUtilidadeRequest body) {
        service.definirUtilidade(utilidade, body.templateId(), currentUserId());
    }

    private UUID currentUserId() {
        Object p = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        return p instanceof UUID l ? l : null;
    }
}
