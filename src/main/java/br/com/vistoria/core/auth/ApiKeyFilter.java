package br.com.vistoria.core.auth;

import br.com.vistoria.core.config.TenantContext;
import br.com.vistoria.core.modules.usuario.Usuario;
import br.com.vistoria.core.modules.usuario.UsuarioImobiliariaRepository;
import br.com.vistoria.core.modules.usuario.UsuarioRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    private final ApiKeyService apiKeyService;
    private final UsuarioRepository usuarioRepository;
    private final UsuarioImobiliariaRepository usuarioImobiliariaRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String apiKey = req.getHeader(HEADER);
        try {
            if (apiKey != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                var tokenOpt = apiKeyService.validate(apiKey);
                if (tokenOpt.isPresent()) {
                    var t = tokenOpt.get();
                    if (t.getImobiliariaId() != null) {
                        // Chave vinculada a uma imobiliária: autentica como o próprio tenant,
                        // sem exigir um Usuario. O principal identifica a chave pelo prefixo.
                        SecurityContextHolder.getContext().setAuthentication(
                                new UsernamePasswordAuthenticationToken("api-key:" + t.getPrefix(), null, scopes(t)));
                        TenantContext.set(List.of(t.getImobiliariaId()));
                    } else {
                        // Chave legada (vinculada a um usuário). Fail closed: usuário órfão/inativo
                        // não autentica.
                        Usuario usuario = usuarioRepository.findById(t.getUsuarioId()).orElse(null);
                        if (usuario != null && usuario.getStatus() == Usuario.Status.ativo) {
                            SecurityContextHolder.getContext().setAuthentication(
                                    new UsernamePasswordAuthenticationToken("api-key:" + t.getPrefix(), null, scopes(t)));
                            var ids = usuarioImobiliariaRepository.findImobiliariaIdsByUsuarioId(usuario.getId());
                            if (ids.isEmpty() && usuario.getImobiliariaId() != null) {
                                ids = List.of(usuario.getImobiliariaId());
                            }
                            TenantContext.set(ids);
                        }
                    }
                }
            }
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }

    private static List<SimpleGrantedAuthority> scopes(TokenEntity t) {
        return Arrays.stream(t.getEscopos())
                .map(e -> new SimpleGrantedAuthority("ROLE_SCOPE_" + e.toUpperCase()))
                .toList();
    }
}
