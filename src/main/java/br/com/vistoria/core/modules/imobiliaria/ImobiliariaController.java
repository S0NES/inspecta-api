package br.com.vistoria.core.modules.imobiliaria;

import br.com.vistoria.core.modules.contrato.ContratoResumoService;
import br.com.vistoria.core.modules.contrato.ContratoResumoService.ContratoResumo;
import br.com.vistoria.core.modules.imobiliaria.dto.CreateImobiliariaRequest;
import br.com.vistoria.core.modules.imobiliaria.dto.ImobiliariaResponse;
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
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/imobiliarias")
@RequiredArgsConstructor
@Tag(name = "Imobiliárias", description = "Gerenciamento de imobiliárias como tenants (T2.3 - multi-tenant via RLS)")
public class ImobiliariaController {

    private final ImobiliariaService service;
    private final ContratoResumoService contratoResumo;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Criar nova imobiliária", description = "Cria um novo tenant/imobiliária. Apenas ADMIN pode criar.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Imobiliária criada", content = @Content(schema = @Schema(implementation = ImobiliariaResponse.class))),
            @ApiResponse(responseCode = "400", description = "Dados inválidos"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão")
    })
    @SecurityRequirement(name = "bearerAuth")
    public ImobiliariaResponse create(@Valid @org.springframework.web.bind.annotation.RequestBody CreateImobiliariaRequest body) {
        return service.create(body);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA')")
    @Operation(summary = "Listar imobiliárias", description = "Lista imobiliárias com paginação. IMOB_GERENCIA vê apenas sua própria imobiliária (tenant-scoped).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista de imobiliárias retornada com sucesso"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão para listar")
    })
    @SecurityRequirement(name = "bearerAuth")
    public Page<ImobiliariaResponse> list(Pageable pageable) {
        return service.list(pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA')")
    @Operation(summary = "Obter imobiliária por ID", description = "Retorna dados de uma imobiliária. IMOB_GERENCIA vê apenas sua própria.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Imobiliária retornada", content = @Content(schema = @Schema(implementation = ImobiliariaResponse.class))),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão"),
            @ApiResponse(responseCode = "404", description = "Imobiliária não encontrada")
    })
    @SecurityRequirement(name = "bearerAuth")
    public ImobiliariaDetalheResponse getById(@PathVariable UUID id) {
        ImobiliariaResponse i = service.findById(id);
        return ImobiliariaDetalheResponse.of(i, contratoResumo.resumoOuNull(id));
    }

    /**
     * V3: detalhe da imobiliária com um resumo do contrato ativo embutido em {@code contrato}
     * ({@code null} quando ainda não há contrato). Mantém no root os mesmos campos de
     * {@link ImobiliariaResponse} para não quebrar clientes existentes.
     */
    public record ImobiliariaDetalheResponse(UUID id, String razaoSocial, String nomeFantasia, String cnpj,
                                             boolean ativa, String pix, String emailFinanceiro, String telefone,
                                             String cep, String endereco, String numero, String complemento,
                                             UUID cidadeId, UUID pdfTemplateId, ContratoResumo contrato) {
        public static ImobiliariaDetalheResponse of(ImobiliariaResponse r, ContratoResumo contrato) {
            return new ImobiliariaDetalheResponse(r.id(), r.razaoSocial(), r.nomeFantasia(), r.cnpj(), r.ativa(),
                    r.pix(), r.emailFinanceiro(), r.telefone(), r.cep(), r.endereco(), r.numero(), r.complemento(),
                    r.cidadeId(), r.pdfTemplateId(), contrato);
        }
    }

    /**
     * {@code pdfTemplateId}: ausente/null = não mexe; UUID nulo ({@code 00000000-0000-0000-0000-000000000000})
     * = limpa o vínculo (volta à resolução por chave); qualquer outro UUID = vincula essa linha
     * de {@code pdf_templates} ao laudo.
     *
     * <p>Endereço próprio da imobiliária ({@code cep}/{@code endereco}/{@code numero}/{@code complemento}/
     * {@code cidadeId}): campo ausente/null = não mexe. O endereço NÃO vai para a Pleno.</p>
     */
    public record UpdateImobiliariaRequest(String razaoSocial, String nomeFantasia, String pix,
                                           String emailFinanceiro, String telefone,
                                           String cep, String endereco, String numero, String complemento,
                                           UUID cidadeId, Boolean ativa,
                                           UUID pdfTemplateId) {}

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Atualizar imobiliária (parcial)")
    @SecurityRequirement(name = "bearerAuth")
    public ImobiliariaResponse patch(@PathVariable UUID id,
                                     @org.springframework.web.bind.annotation.RequestBody UpdateImobiliariaRequest body) {
        return service.update(id, body.razaoSocial(), body.nomeFantasia(), body.pix(),
                body.emailFinanceiro(), body.telefone(), body.cep(), body.endereco(), body.numero(),
                body.complemento(), body.cidadeId(), body.ativa(), body.pdfTemplateId());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Excluir imobiliária",
            description = "Remove a imobiliária. Publica jobs.payment.customer-delete para o inspecta-worker "
                    + "apagar o customer no provider de pagamento quando houver external_payment_id.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Imobiliária excluída"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuário não possui permissão"),
            @ApiResponse(responseCode = "404", description = "Imobiliária não encontrada")
    })
    @SecurityRequirement(name = "bearerAuth")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    /** {@code pdfTemplateId} nulo limpa o vínculo; qualquer UUID vincula. */
    public record DefinirPdfTemplateRequest(UUID pdfTemplateId) {}

    @PutMapping("/{id}/pdf-template")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Definir/limpar o template de laudo de uma imobiliária (ADMIN ou SUPORTE)")
    @SecurityRequirement(name = "bearerAuth")
    public ImobiliariaResponse definirPdfTemplate(@PathVariable UUID id,
                                                  @org.springframework.web.bind.annotation.RequestBody DefinirPdfTemplateRequest body) {
        return service.definirPdfTemplate(id, body.pdfTemplateId());
    }
}
