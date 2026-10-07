package br.com.vistoria.core.modules.clienteb2c.dto;

import br.com.vistoria.core.modules.clienteb2c.ClienteB2c;
import br.com.vistoria.core.modules.usuario.Usuario;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ClienteB2cResponse(
        UUID id,
        UUID usuarioId,
        String nome,
        String email,
        String cpf,
        String telefone,
        UUID cidadeId,
        List<UUID> clientServices,
        Usuario.Status status,
        long quantidadeVistorias,
        Instant criadoEm
) {
    public static ClienteB2cResponse from(ClienteB2c c, Usuario.Status status, long quantidadeVistorias) {
        return new ClienteB2cResponse(c.getId(), c.getUsuarioId(), c.getNome(), c.getEmail(), c.getCpf(), c.getTelefone(),
                c.getCidadeId(), c.getClientServices(), status, quantidadeVistorias, c.getCriadoEm());
    }
}
