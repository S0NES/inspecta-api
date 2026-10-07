package br.com.vistoria.core.modules.usuario.dto;

import br.com.vistoria.core.modules.usuario.Usuario;

import java.time.Instant;
import java.util.UUID;

public record UsuarioResponse(
        UUID id,
        String nome,
        String email,
        Usuario.Perfil perfil,
        Usuario.Status status,
        UUID imobiliariaId,
        Instant criadoEm,
        Usuario.Tema tema,
        boolean senhaDefinida
) {
    public static UsuarioResponse from(Usuario u) {
        boolean senhaDefinida = u.getSenhaHash() != null && !u.getSenhaHash().isBlank();
        return new UsuarioResponse(u.getId(), u.getNome(), u.getEmail(), u.getPerfil(),
                u.getStatus(), u.getImobiliariaId(), u.getCriadoEm(),
                u.getTema() != null ? u.getTema() : Usuario.Tema.light,
                senhaDefinida);
    }
}
