package br.com.vistoria.core.auth;

import br.com.vistoria.core.modules.imobiliaria.ImobiliariaRepository;
import br.com.vistoria.core.shared.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final TokenRepository tokens;
    private final ImobiliariaRepository imobiliarias;

    private static String mascara(String ultimos4) {
        return "••••" + (ultimos4 == null ? "" : ultimos4);
    }

    public record CreatedKey(UUID id, String plaintext, String mascara, UUID imobiliariaId, Instant expiraEm) {}

    /**
     * Cria uma API key vinculada a uma IMOBILIÁRIA. O plaintext (`prefix.secret`)
     * volta uma única vez; depois só a máscara (`••••` + 4 últimos do secret).
     * A idempotência é do {@code IdempotencyFilter} (header Idempotency-Key).
     */
    @Transactional
    public CreatedKey create(UUID imobiliariaId, String[] escopos, Instant expiraEm) {
        if (imobiliariaId == null)
            throw new IllegalArgumentException("imobiliaria_id obrigatorio");
        if (!imobiliarias.existsById(imobiliariaId))
            throw new NotFoundException("Imobiliaria " + imobiliariaId + " nao encontrada");
        if (expiraEm == null || expiraEm.isBefore(Instant.now()))
            throw new IllegalArgumentException("expira_em obrigatorio e futuro");
        String prefix = TokenHasher.newPrefix();
        String secret = TokenHasher.newToken();
        String ultimos4 = secret.substring(secret.length() - 4);
        TokenEntity t = new TokenEntity();
        t.setImobiliariaId(imobiliariaId);
        t.setTipo(TokenEntity.Tipo.api_key);
        t.setPrefix(prefix);
        t.setHash(TokenHasher.sha256(secret));
        t.setUltimos4(ultimos4);
        t.setEscopos(escopos == null ? new String[0] : escopos);
        t.setExpiraEm(expiraEm);
        tokens.save(t);
        return new CreatedKey(t.getId(), prefix + "." + secret, mascara(ultimos4), imobiliariaId, expiraEm);
    }

    @Transactional(readOnly = true)
    public Optional<TokenEntity> validate(String rawKey) {
        int dot = rawKey.indexOf('.');
        if (dot < 1) return Optional.empty();
        String prefix = rawKey.substring(0, dot);
        String secret = rawKey.substring(dot + 1);
        return tokens.findByPrefixAndTipoAndRevogadoFalse(prefix, TokenEntity.Tipo.api_key)
                .filter(t -> t.getExpiraEm().isAfter(Instant.now()))
                .filter(t -> t.getHash().equals(TokenHasher.sha256(secret)));
    }

    /** Metadados de uma API key para a UI (nunca inclui hash nem plaintext — só a máscara). */
    public record ApiKeyInfo(UUID id, String mascara, UUID imobiliariaId, String[] escopos, Instant criadoEm,
                             Instant expiraEm, boolean revogado, boolean expirada) {}

    private ApiKeyInfo toInfo(TokenEntity t, Instant agora) {
        return new ApiKeyInfo(t.getId(), mascara(t.getUltimos4()), t.getImobiliariaId(), t.getEscopos(),
                t.getCriadoEm(), t.getExpiraEm(), t.isRevogado(), t.getExpiraEm().isBefore(agora));
    }

    @Transactional(readOnly = true)
    public List<ApiKeyInfo> list() {
        Instant agora = Instant.now();
        return tokens.findByTipoOrderByCriadoEmDesc(TokenEntity.Tipo.api_key).stream()
                .map(t -> toInfo(t, agora)).toList();
    }

    @Transactional(readOnly = true)
    public List<ApiKeyInfo> listByImobiliaria(UUID imobiliariaId) {
        Instant agora = Instant.now();
        return tokens.findByTipoAndImobiliariaIdOrderByCriadoEmDesc(TokenEntity.Tipo.api_key, imobiliariaId)
                .stream().map(t -> toInfo(t, agora)).toList();
    }

    @Transactional
    public void revoke(UUID id) {
        tokens.findById(id).ifPresent(t -> t.setRevogado(true));
    }

    @Transactional(readOnly = true)
    public List<TokenEntity> expiringWithin(java.time.Duration window) {
        Instant limit = Instant.now().plus(window);
        return tokens.findByTipoAndRevogadoFalse(TokenEntity.Tipo.api_key).stream()
                .filter(t -> t.getExpiraEm().isBefore(limit) && t.getExpiraEm().isAfter(Instant.now()))
                .toList();
    }
}
