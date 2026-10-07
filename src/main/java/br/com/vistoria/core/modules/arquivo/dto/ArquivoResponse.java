package br.com.vistoria.core.modules.arquivo.dto;

import br.com.vistoria.core.modules.arquivo.Arquivo;

import java.time.Instant;
import java.util.UUID;

public record ArquivoResponse(
        UUID id,
        String mime,
        Long tamanho,
        Instant criadoEm
) {
    public static ArquivoResponse from(Arquivo a) {
        return new ArquivoResponse(a.getId(), a.getMime(), a.getTamanho(), a.getCriadoEm());
    }
}
