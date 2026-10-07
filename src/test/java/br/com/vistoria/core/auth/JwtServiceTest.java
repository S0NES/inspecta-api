package br.com.vistoria.core.auth;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    @Test
    void issueAndParseAccessTokenAddsExpectedClaims() {
        JwtService service = new JwtService("super-secret-key-that-is-long-enough", Duration.ofMinutes(5));
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000042");
        UUID imobiliariaId = UUID.fromString("00000000-0000-0000-0000-000000000007");

        String token = service.issueAccessToken(userId, "ADMIN", imobiliariaId);
        Claims claims = service.parse(token);

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("perfil")).isEqualTo("ADMIN");
        assertThat(claims.get("imobiliaria_id")).isEqualTo(imobiliariaId.toString());
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
    }

    @Test
    void issueAccessTokenTreatsMissingImobiliariaAsEmpty() {
        JwtService service = new JwtService("super-secret-key-that-is-long-enough", Duration.ofMinutes(5));
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000011");

        String token = service.issueAccessToken(userId, "USER", null);
        Claims claims = service.parse(token);

        assertThat(claims.get("imobiliaria_id")).isEqualTo("");
    }
}
