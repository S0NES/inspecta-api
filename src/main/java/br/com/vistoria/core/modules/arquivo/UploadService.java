package br.com.vistoria.core.modules.arquivo;

import br.com.vistoria.core.config.TenantContext;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.ImovelRepository;
import br.com.vistoria.core.modules.cadastro.Imovel;
import br.com.vistoria.core.modules.disponibilidade.DisponibilidadeRepositories.VistoriadorRepository;
import br.com.vistoria.core.modules.disponibilidade.Vistoriador;
import br.com.vistoria.core.modules.laudo.Laudo;
import br.com.vistoria.core.modules.laudo.LaudoRepository;
import br.com.vistoria.core.modules.solicitacao.Solicitacao;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoServico;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.SolicitacaoRepository;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.ServicoRepository;
import br.com.vistoria.core.modules.arquivo.dto.ArquivoResponse;
import br.com.vistoria.core.shared.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UploadService {

    private static final Set<String> MIMES = Set.of("image/jpeg", "image/png", "image/webp", "application/pdf");
    private static final long MAX_BYTES = 25L * 1024 * 1024;

    /**
     * arquivos is deliberately excluded from RLS (polymorphic owner) — this allowlist +
     * checkOwnership() is the PERMANENT compensating application-layer control. Do not remove
     * when RLS coverage is otherwise completed.
     */
    private static final Set<String> OWNER_TYPES = Set.of("imovel", "laudo", "laudo-assinado", "comprovante-pagamento", "solicitacao");

    private final ArquivoService arquivoService;
    private final ImovelRepository imoveis;
    private final LaudoRepository laudos;
    private final ServicoRepository servicos;
    private final SolicitacaoRepository solicitacoes;
    private final VistoriadorRepository vistoriadores;

    public record UploadResult(UUID arquivoId) {}

    @Transactional
    public UploadResult upload(String mime, byte[] bytes, String ownerType, UUID ownerId) {
        if (!MIMES.contains(mime)) throw new IllegalArgumentException("Tipo nao permitido: " + mime);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Arquivo excede 25MB");
        checkOwnership(ownerType, ownerId);

        String key = ownerType + "/" + ownerId + "/" + UUID.randomUUID();
        Arquivo a = arquivoService.registrar(key, mime, (long) bytes.length, ownerType, ownerId, false);
        arquivoService.salvarConteudo(a.getId(), bytes);
        return new UploadResult(a.getId());
    }

    @Transactional(readOnly = true)
    public List<ArquivoResponse> listar(String ownerType, UUID ownerId) {
        checkOwnership(ownerType, ownerId);
        return arquivoService.listarImagensPorDono(ownerType, ownerId).stream()
                .map(ArquivoResponse::from)
                .toList();
    }

    private void checkOwnership(String ownerType, UUID ownerId) {
        if (!OWNER_TYPES.contains(ownerType))
            throw new IllegalArgumentException("ownerType invalido: " + ownerType);
        switch (ownerType) {
            case "imovel" -> checkTenant(imoveis.findById(ownerId)
                    .map(Imovel::getImobiliariaId)
                    .orElseThrow(() -> new NotFoundException("Imovel " + ownerId)));
            case "laudo", "laudo-assinado" -> checkTenant(imobiliariaIdDoLaudo(ownerId));
            case "comprovante-pagamento" -> checkVistoriador(ownerId);
            case "solicitacao" -> checkTenant(imobiliariaIdDaSolicitacao(ownerId));
            default -> throw new IllegalArgumentException("ownerType invalido: " + ownerType);
        }
    }

    private UUID imobiliariaIdDoLaudo(UUID laudoId) {
        Laudo l = laudos.findById(laudoId).orElseThrow(() -> new NotFoundException("Laudo " + laudoId));
        SolicitacaoServico ss = servicos.findById(l.getSolicitacaoServicoId())
                .orElseThrow(() -> new NotFoundException("SolicitacaoServico " + l.getSolicitacaoServicoId()));
        Solicitacao s = solicitacoes.findById(ss.getSolicitacaoId())
                .orElseThrow(() -> new NotFoundException("Solicitacao " + ss.getSolicitacaoId()));
        return s.getImobiliariaId();
    }

    private UUID imobiliariaIdDaSolicitacao(UUID solicitacaoId) {
        return solicitacoes.findById(solicitacaoId)
                .orElseThrow(() -> new NotFoundException("Solicitacao " + solicitacaoId))
                .getImobiliariaId();
    }

    private void checkTenant(UUID recursoImobiliariaId) {
        List<UUID> tenants = TenantContext.getAll();
        // lista vazia: escopo global (admin/suporte/B2C), regra 9 — não restringe aqui.
        // Usuário vinculado a N imobiliárias (V25) pode operar em qualquer uma delas.
        if (!tenants.isEmpty() && !tenants.contains(recursoImobiliariaId))
            throw new AccessDeniedException("Recurso pertence a outro tenant");
    }

    private void checkVistoriador(UUID vistoriadorId) {
        Vistoriador v = vistoriadores.findById(vistoriadorId)
                .orElseThrow(() -> new NotFoundException("Vistoriador " + vistoriadorId));
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        // Admin/suporte API-key principal is "api-key:<prefix>" (String), not a Usuario id — allowed through.
        if (principal instanceof UUID userId && !v.getUsuarioId().equals(userId))
            throw new AccessDeniedException("Comprovante nao pertence ao vistoriador autenticado");
    }
}
