package br.com.vistoria.core.modules.arquivo;

import br.com.vistoria.core.modules.arquivo.dto.ArquivoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Uploads", description = "Direct file upload to Postgres with validation of ownerType (T1.6)")
public class UploadController {

    private final UploadService service;

    @PostMapping("/v1/uploads")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload file directly to Postgres (T1.6)", description = "Uploads a file directly to Postgres storage. ownerType accepts only permitted values (imovel, laudo, laudo-assinado, comprovante-pagamento). Requires authentication.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "File uploaded successfully", content = @Content(schema = @Schema(implementation = UploadService.UploadResult.class))),
            @ApiResponse(responseCode = "400", description = "ownerType invalid, file size exceeds limit, or MIME type not permitted"),
            @ApiResponse(responseCode = "401", description = "User not authenticated"),
            @ApiResponse(responseCode = "403", description = "User is not the owner of the resource (BOLA fix)"),
            @ApiResponse(responseCode = "404", description = "Resource owner not found")
    })
    @SecurityRequirement(name = "bearerAuth")
    public UploadService.UploadResult upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("ownerType") String ownerType,
            @RequestParam("ownerId") UUID ownerId) throws IOException {
        return service.upload(file.getContentType(), file.getBytes(), ownerType, ownerId);
    }

    @GetMapping("/v1/uploads")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Listar imagens de um dono", description = "Lista as imagens (mime image/*) ja enviadas para um ownerType/ownerId, na ordem de upload. Sujeito ao mesmo controle de posse (tenant/BOLA) do upload (T1.6).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista de arquivos retornada com sucesso", content = @Content(schema = @Schema(implementation = ArquivoResponse.class))),
            @ApiResponse(responseCode = "400", description = "ownerType invalido"),
            @ApiResponse(responseCode = "401", description = "Usuario nao autenticado"),
            @ApiResponse(responseCode = "403", description = "Usuario nao possui acesso ao recurso (BOLA fix)"),
            @ApiResponse(responseCode = "404", description = "Dono do recurso nao encontrado")
    })
    @SecurityRequirement(name = "bearerAuth")
    public List<ArquivoResponse> listar(
            @RequestParam("ownerType") String ownerType,
            @RequestParam("ownerId") UUID ownerId) {
        return service.listar(ownerType, ownerId);
    }
}
