package br.com.vistoria.core.modules.pdftemplate;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CRUD dos templates de PDF (hoje só {@code chave=laudo}). Só ADMIN/SUPORTE.
 *
 * <p>Toda gravação passa por {@code PdfTemplateService.salvar}, que valida o template
 * (FreeMarker + campos do catálogo + segurança) antes de persistir — um template inválido
 * volta 422 com a lista de erros, nunca chega a gerar um laudo quebrado.</p>
 */
@RestController
@RequestMapping("/v1/pdf-templates")
@RequiredArgsConstructor
@Tag(name = "Templates de PDF", description = "CRUD dos templates de documento (laudo) por imobiliária ou global")
public class PdfTemplateController {

    private final PdfTemplateService service;

    public record PdfTemplateResponse(UUID id, String chave, UUID imobiliariaId,
                                      int versao, String nome, String html, String css,
                                      boolean ativo, Instant atualizadoEm) {
        static PdfTemplateResponse from(PdfTemplate t) {
            return new PdfTemplateResponse(t.getId(), t.getChave(), t.getImobiliariaId(),
                    t.getVersao(), t.getNome(), t.getHtml(), t.getCss(), t.isAtivo(), t.getAtualizadoEm());
        }
    }

    public record SalvarRequest(String chave, String nome, String html, String css, UUID imobiliariaId) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Listar templates de PDF ativos")
    public List<PdfTemplateResponse> listar() {
        return service.listarAtivos().stream().map(PdfTemplateResponse::from).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Obter um template de PDF por id")
    public PdfTemplateResponse get(@PathVariable UUID id) {
        return PdfTemplateResponse.from(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Gravar um template de PDF (sempre cria uma nova versão; valida antes de persistir)")
    public PdfTemplateResponse salvar(@RequestBody SalvarRequest body) {
        PdfTemplate t = new PdfTemplate();
        t.setChave(body.chave());
        t.setNome(body.nome());
        t.setHtml(body.html());
        t.setCss(body.css());
        t.setImobiliariaId(body.imobiliariaId());
        return PdfTemplateResponse.from(service.salvar(t));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Desativar uma versão de template (a imobiliária volta ao fallback)")
    public void desativar(@PathVariable UUID id) {
        service.desativar(id);
    }
}
