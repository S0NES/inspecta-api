package br.com.vistoria.core.modules.laudo.dto;

import br.com.vistoria.core.modules.laudo.Laudo;

import java.time.Instant;
import java.util.UUID;

public record LaudoResponse(
        UUID id,
        UUID solicitacaoServicoId,
        int versao,
        String status,
        String origem,
        Instant criadoEm,
        Instant assinadoEm,
        Long arquivoTamanho,
        String arquivoMime,
        int fotosCount
) {
    public static LaudoResponse from(Laudo l, Long arquivoTamanho, String arquivoMime, int fotosCount) {
        return new LaudoResponse(l.getId(), l.getSolicitacaoServicoId(), l.getVersao(),
                l.getStatus().name(), l.getOrigem() != null ? l.getOrigem().name() : null,
                l.getCriadoEm(), l.getAssinadoEm(),
                arquivoTamanho, arquivoMime, fotosCount);
    }
}
