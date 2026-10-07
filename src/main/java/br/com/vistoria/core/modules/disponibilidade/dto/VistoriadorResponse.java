package br.com.vistoria.core.modules.disponibilidade.dto;

import br.com.vistoria.core.modules.disponibilidade.Vistoriador;

import java.math.BigDecimal;
import java.util.UUID;

public record VistoriadorResponse(
        UUID id,
        UUID usuarioId,
        UUID[] cidades,
        Double latBase,
        Double lngBase,
        BigDecimal distanciaMaxKm,
        String plenoId,
        boolean ativo
) {
    public static VistoriadorResponse from(Vistoriador v) {
        return new VistoriadorResponse(v.getId(), v.getUsuarioId(), v.getCidades(), v.getLatBase(),
                v.getLngBase(), v.getDistanciaMaxKm(), v.getPlenoId(), v.isAtivo());
    }
}
