package br.com.vistoria.core.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class JwtService {

    private final SecretKey key;
    private final Duration accessTtl;

    public JwtService(@Value("${inspecta.jwt.secret}") String secret,
                      @Value("${inspecta.jwt.access-ttl:PT15M}") Duration accessTtl) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = accessTtl;
    }

    /**
     * TTL configurado do access token. O controller usa isto para o `Max-Age` do cookie e para
     * o `expiresInSeconds` da resposta, de modo que cookie, corpo e assinatura do JWT expirem
     * juntos — antes havia um 900 fixo que ignorava esta configuração.
     */
    public long accessTtlSeconds() {
        return accessTtl.toSeconds();
    }

    public String issueAccessToken(UUID userId, String perfil, UUID imobiliariaId) {
        return issueAccessToken(userId, perfil, imobiliariaId, List.of());
    }

    /**
     * @param imobiliariaId  imobiliária primária/ativa (claim {@code imobiliaria_id}, compat)
     * @param imobiliariaIds todos os vínculos do usuário (claim {@code imobiliaria_ids}, CSV) —
     *                       a RLS multi-tenant (V26) filtra por qualquer um deles
     */
    public String issueAccessToken(UUID userId, String perfil, UUID imobiliariaId, List<UUID> imobiliariaIds) {
        Instant now = Instant.now();
        String ids = (imobiliariaIds == null ? List.<UUID>of() : imobiliariaIds).stream()
                .filter(java.util.Objects::nonNull).map(String::valueOf).distinct()
                .collect(Collectors.joining(","));
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claims(Map.of(
                        "perfil", perfil,
                        // UUID nao tem um sentinel numerico seguro tipo o antigo 0L; string vazia
                        // representa "sem imobiliaria primaria".
                        "imobiliaria_id", imobiliariaId == null ? "" : imobiliariaId.toString(),
                        "imobiliaria_ids", ids))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }
}
