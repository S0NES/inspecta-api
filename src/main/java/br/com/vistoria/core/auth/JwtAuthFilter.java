package br.com.vistoria.core.auth;

import br.com.vistoria.core.config.TenantContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String token = extractToken(req);
        try {
            if (token != null
                    && SecurityContextHolder.getContext().getAuthentication() == null) {
                try {
                    Claims claims = jwtService.parse(token);
                    UUID userId = UUID.fromString(claims.getSubject());
                    String perfil = claims.get("perfil", String.class);
                    String imobId = claims.get("imobiliaria_id", String.class);
                    String imobIds = claims.get("imobiliaria_ids", String.class);

                    var auth = new UsernamePasswordAuthenticationToken(
                            userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + perfil.toUpperCase())));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    TenantContext.set(parseTenants(imobIds, imobId));
                } catch (io.jsonwebtoken.JwtException | IllegalArgumentException | NullPointerException e) {
                    log.debug("JWT invalido/expirado: {}", e.getMessage());
                }
            }
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Lista de tenants do token. Prefere o claim novo {@code imobiliaria_ids} (CSV);
     * cai no escalar {@code imobiliaria_id} para tokens antigos. Vazio = vê tudo.
     */
    static List<UUID> parseTenants(String csv, String escalar) {
        if (csv != null && !csv.isBlank()) {
            return java.util.Arrays.stream(csv.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .map(UUID::fromString).distinct().toList();
        }
        return escalar != null && !escalar.isBlank() ? List.of(UUID.fromString(escalar)) : List.of();
    }

    private String extractToken(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        if (req.getCookies() != null) {
            for (var cookie : req.getCookies()) {
                if ("inspecta_access".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
