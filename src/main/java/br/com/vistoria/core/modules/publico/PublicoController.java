package br.com.vistoria.core.modules.publico;

import br.com.vistoria.core.auth.TokenHasher;
import br.com.vistoria.core.modules.arquivo.Arquivo;
import br.com.vistoria.core.modules.arquivo.ArquivoService;
import br.com.vistoria.core.modules.laudo.Laudo;
import br.com.vistoria.core.modules.laudo.LaudoService;
import br.com.vistoria.core.shared.exception.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Acesso Público", description = "Links públicos para laudo e imagens com validação de token (T2.10)")
public class PublicoController {

    private final AcessoPublicoRepository acessos;
    private final LaudoService laudoService;
    private final ArquivoService arquivoService;

    @PostMapping("/v1/laudos/{id}/link-publico")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA','VISTORIADOR')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Criar link público para laudo ou imagens", description = "Gera um link público com token para compartilhar laudos ou imagens. Link expira em 90 dias. Tipo padrão é 'laudo'.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Link público criado com sucesso", content = @Content(schema = @Schema(implementation = Map.class))),
            @ApiResponse(responseCode = "400", description = "Tipo de acesso inválido"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão para criar links públicos"),
            @ApiResponse(responseCode = "404", description = "Recurso não encontrado")
    })
    @SecurityRequirement(name = "bearerAuth")
    public Map<String, String> criarLink(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "laudo") AcessoPublico.Tipo tipo) {
        String raw = TokenHasher.newToken();
        AcessoPublico a = new AcessoPublico();
        a.setTokenHash(TokenHasher.sha256(raw));
        a.setTipo(tipo);
        a.setRecursoId(id);
        a.setExpiraEm(Instant.now().plus(Duration.ofDays(90)));
        acessos.save(a);
        return Map.of("url", "/v1/public/" + caminhoPublico(tipo) + "/" + raw);
    }

    // "laudo" ainda precisa do "s" para virar o segmento de path "laudos" (@GetMapping
    // "/v1/public/laudos/{token}"), mas "imagens" ja esta no plural: concatenar um "s" aqui
    // gerava "/v1/public/imagenss/{token}", que nunca batia com @GetMapping
    // "/v1/public/imagens/{token}" e quebrava todo link publico de tipo=imagens gerado.
    private String caminhoPublico(AcessoPublico.Tipo tipo) {
        return switch (tipo) {
            case laudo -> "laudos";
            case imagens -> "imagens";
        };
    }

    @GetMapping("/v1/public/laudos/{token}")
    @Operation(summary = "Acessar laudo via link público", description = "Retorna um laudo usando um token de acesso público. Token deve ser válido, não revogado e não expirado. Sem autenticação JWT necessária.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Laudo retornado com sucesso", content = @Content(schema = @Schema(implementation = Laudo.class))),
            @ApiResponse(responseCode = "404", description = "Token inválido, expirado ou revogado")
    })
    public Laudo laudoPublico(@PathVariable String token) {
        AcessoPublico a = validar(token, AcessoPublico.Tipo.laudo);
        return laudoService.get(a.getRecursoId());
    }

    /** DTO da galeria: nunca o {@link Arquivo} bruto, que carrega os bytes no campo {@code conteudo}. */
    public record ImagemPublica(UUID id, String mime, Long tamanho) {}

    @GetMapping("/v1/public/imagens/{token}")
    @Operation(summary = "Listar fotos do laudo via link público (T2.10)", description = "Retorna a lista de fotos (id/mime/tamanho) do laudo associado ao token. Token deve ser válido, não revogado e não expirado. Sem autenticação JWT necessária.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista de fotos retornada com sucesso", content = @Content(schema = @Schema(implementation = ImagemPublica.class))),
            @ApiResponse(responseCode = "404", description = "Token inválido, expirado ou revogado")
    })
    public List<ImagemPublica> imagensPublicas(@PathVariable String token) {
        AcessoPublico a = validar(token, AcessoPublico.Tipo.imagens);
        return arquivoService.listarImagensPorDono("laudo", a.getRecursoId()).stream()
                .map(ar -> new ImagemPublica(ar.getId(), ar.getMime(), ar.getTamanho()))
                .toList();
    }

    @GetMapping(value = "/v1/public/imagens/{token}/pagina", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "Galeria de fotos do laudo (página HTML pública, T2.10)", description = "Página HTML pública com todas as fotos do laudo — é o destino do QR code embutido no PDF do laudo (ADR-001: o renderizador de PDF so aceita data: URI, entao o QR aponta pra cá em vez de embutir cada foto).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Página HTML retornada com sucesso"),
            @ApiResponse(responseCode = "404", description = "Token inválido, expirado ou revogado")
    })
    public String galeriaPublica(@PathVariable String token) {
        AcessoPublico a = validar(token, AcessoPublico.Tipo.imagens);
        List<Arquivo> imagens = arquivoService.listarImagensPorDono("laudo", a.getRecursoId());
        return paginaGaleria(token, imagens);
    }

    @GetMapping("/v1/public/imagens/{token}/{arquivoId}")
    @Operation(summary = "Bytes de uma foto do laudo (T2.10)", description = "Retorna os bytes de uma foto do laudo do token, com o Content-Type correto. So serve arquivos que realmente pertencem ao laudo do token (owner_type=laudo, owner_id=recurso_id) e que sao imagem — nunca outro dono, nem o PDF do proprio laudo (mesmo owner_id, mime diferente).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bytes da imagem retornados com sucesso"),
            @ApiResponse(responseCode = "404", description = "Token inválido/expirado/revogado, ou a imagem não pertence a este laudo")
    })
    public ResponseEntity<byte[]> conteudoImagemPublica(@PathVariable String token, @PathVariable UUID arquivoId) {
        AcessoPublico a = validar(token, AcessoPublico.Tipo.imagens);
        Arquivo arquivo = arquivoService.get(arquivoId);
        boolean pertenceAoLaudo = "laudo".equals(arquivo.getOwnerType())
                && a.getRecursoId().equals(arquivo.getOwnerId())
                && arquivo.getMime() != null && arquivo.getMime().startsWith("image/");
        if (!pertenceAoLaudo) {
            throw new NotFoundException("Imagem nao encontrada");
        }
        byte[] bytes = arquivoService.lerConteudo(arquivoId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(arquivo.getMime())).body(bytes);
    }

    private static String paginaGaleria(String token, List<Arquivo> imagens) {
        StringBuilder imgs = new StringBuilder();
        for (Arquivo a : imagens) {
            imgs.append("<img src=\"/v1/public/imagens/").append(token).append('/').append(a.getId())
                    .append("\" alt=\"foto da vistoria\"/>\n");
        }
        return """
                <!DOCTYPE html><html lang="pt-BR"><head><meta charset="utf-8">
                <title>Fotos da vistoria</title>
                <style>
                  body { font-family: Arial, sans-serif; background: #f5f5f5; margin: 0; padding: 24px; }
                  h1 { font-size: 18px; color: #222; }
                  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 12px; }
                  .grid img { width: 100%%; border-radius: 6px; display: block; }
                  .vazio { color: #666; }
                </style></head><body>
                <h1>Fotos da vistoria</h1>
                <div class="grid">
                %s
                </div>
                %s
                </body></html>
                """.formatted(imgs, imagens.isEmpty() ? "<p class=\"vazio\">Nenhuma foto disponivel.</p>" : "");
    }

    @DeleteMapping("/v1/links-publicos/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revogar link público", description = "Revoga um link público, impedindo acesso futuro. Apenas ADMIN e SUPORTE podem revogar.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Link revogado com sucesso"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão para revogar links")
    })
    @SecurityRequirement(name = "bearerAuth")
    public void revogar(@PathVariable UUID id) {
        acessos.findById(id).ifPresent(a -> { a.setRevogado(true); acessos.save(a); });
    }

    private AcessoPublico validar(String rawToken, AcessoPublico.Tipo tipo) {
        return acessos.findByTokenHashAndRevogadoFalse(TokenHasher.sha256(rawToken))
                .filter(a -> a.getTipo() == tipo && a.getExpiraEm().isAfter(Instant.now()))
                .orElseThrow(() -> new NotFoundException("Recurso nao encontrado"));
    }
}
