package br.com.vistoria.core.modules.cadastro;

import br.com.vistoria.core.config.TenantContext;
import br.com.vistoria.core.modules.arquivo.UploadService;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.ImovelRepository;
import br.com.vistoria.core.shared.exception.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Ambientes (cômodos) de um imóvel e as fotos anexadas a cada ambiente.
 *
 * <p>{@code ambientes}/{@code fotos} não têm coluna {@code imobiliaria_id} própria (não há RLS
 * direta por tenant nessas tabelas), então a posse é sempre resolvida subindo até o
 * {@code Imovel} — o mesmo controle de tenant que {@code UploadService} aplica para
 * {@code ownerType=imovel}. O upload da foto em si reaproveita esse mesmo pipeline
 * (validação de mime/tamanho + posse) via {@link UploadService#upload}.</p>
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "Ambientes e Fotos", description = "Cômodos de um imóvel e as fotos anexadas a cada ambiente")
public class AmbienteController {

    private final AmbienteService ambienteService;
    private final AmbienteRepository ambientes;
    private final FotoRepository fotos;
    private final ImovelRepository imoveis;
    private final UploadService uploadService;

    @GetMapping("/imoveis/{imovelId}/ambientes")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA','IMOB_OPERACAO')")
    @Operation(summary = "Listar ambientes de um imóvel")
    @SecurityRequirement(name = "bearerAuth")
    public List<Ambiente> listarAmbientes(@PathVariable UUID imovelId) {
        checkTenantDoImovel(imovelId);
        return ambienteService.listarPorImovel(imovelId);
    }

    @PostMapping("/imoveis/{imovelId}/ambientes")
    @PreAuthorize("hasAnyRole('ADMIN','IMOB_GERENCIA','IMOB_OPERACAO')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Criar ambiente (cômodo) em um imóvel")
    @SecurityRequirement(name = "bearerAuth")
    public Ambiente criarAmbiente(@PathVariable UUID imovelId,
                                  @org.springframework.web.bind.annotation.RequestBody Ambiente body) {
        checkTenantDoImovel(imovelId);
        body.setImovelId(imovelId);
        return ambienteService.criar(body);
    }

    @DeleteMapping("/ambientes/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','IMOB_GERENCIA','IMOB_OPERACAO')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remover ambiente (fotos vinculadas caem junto via ON DELETE CASCADE)")
    @SecurityRequirement(name = "bearerAuth")
    public void removerAmbiente(@PathVariable UUID id) {
        Ambiente a = ambientes.findById(id).orElseThrow(() -> new NotFoundException("Ambiente " + id));
        checkTenantDoImovel(a.getImovelId());
        ambienteService.remover(id);
    }

    @GetMapping("/ambientes/{ambienteId}/fotos")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA','IMOB_OPERACAO')")
    @Operation(summary = "Listar fotos de um ambiente")
    @SecurityRequirement(name = "bearerAuth")
    public List<Foto> listarFotos(@PathVariable UUID ambienteId) {
        checkTenantDoAmbiente(ambienteId);
        return ambienteService.listarFotos(ambienteId);
    }

    @PostMapping("/ambientes/{ambienteId}/fotos")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Adicionar foto a um ambiente",
            description = "Recebe o arquivo direto (mesma validação de mime/tamanho e controle de posse do "
                    + "POST /v1/uploads com ownerType=imovel) e cria o vínculo ambiente -> arquivo.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Foto adicionada com sucesso"),
            @ApiResponse(responseCode = "400", description = "Tipo de arquivo não permitido ou tamanho excedido"),
            @ApiResponse(responseCode = "401", description = "Usuário não autenticado"),
            @ApiResponse(responseCode = "403", description = "Ambiente pertence a outro tenant"),
            @ApiResponse(responseCode = "404", description = "Ambiente não encontrado")
    })
    @SecurityRequirement(name = "bearerAuth")
    public Foto adicionarFoto(@PathVariable UUID ambienteId,
                              @RequestParam("file") MultipartFile file) throws IOException {
        Ambiente a = ambientes.findById(ambienteId).orElseThrow(() -> new NotFoundException("Ambiente " + ambienteId));
        UploadService.UploadResult uploaded = uploadService.upload(
                file.getContentType(), file.getBytes(), "imovel", a.getImovelId());

        Foto foto = new Foto();
        foto.setAmbienteId(ambienteId);
        foto.setArquivoId(uploaded.arquivoId());
        return ambienteService.adicionarFoto(foto);
    }

    @DeleteMapping("/fotos/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','IMOB_GERENCIA','IMOB_OPERACAO')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remover foto de um ambiente")
    @SecurityRequirement(name = "bearerAuth")
    public void removerFoto(@PathVariable UUID id) {
        Foto foto = fotos.findById(id).orElseThrow(() -> new NotFoundException("Foto " + id));
        checkTenantDoAmbiente(foto.getAmbienteId());
        ambienteService.removerFoto(id);
    }

    private void checkTenantDoAmbiente(UUID ambienteId) {
        Ambiente a = ambientes.findById(ambienteId).orElseThrow(() -> new NotFoundException("Ambiente " + ambienteId));
        checkTenantDoImovel(a.getImovelId());
    }

    /** Mesma regra de {@code UploadService.checkTenant}: lista vazia = escopo global (admin/suporte). */
    private void checkTenantDoImovel(UUID imovelId) {
        UUID imobiliariaId = imoveis.findById(imovelId)
                .map(Imovel::getImobiliariaId)
                .orElseThrow(() -> new NotFoundException("Imovel " + imovelId));
        List<UUID> tenants = TenantContext.getAll();
        if (!tenants.isEmpty() && !tenants.contains(imobiliariaId)) {
            throw new AccessDeniedException("Recurso pertence a outro tenant");
        }
    }
}
