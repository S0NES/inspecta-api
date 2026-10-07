package br.com.vistoria.core.config;

import br.com.vistoria.core.shared.idempotency.IdempotencyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * Idempotencia HTTP nas rotas mutantes /v1/* (T6.18 / arquitetura.md § Espelhamento Pleno).
 *
 * Sem o header {@code Idempotency-Key} nada muda — <b>exceto</b> {@code POST /v1/solicitacoes},
 * que exige a chave (400 se ausente): sem ela, duplo-clique cria duas solicitações, dois
 * {@code ASSIGNMENT} e dois {@code ExtratoItem}. Com o header, a mesma chave +
 * mesmo corpo devolve a resposta armazenada sem reexecutar; mesma chave + corpo
 * diferente -> 422; requisicao gemea ainda processando -> 409.
 *
 * Roda depois da cadeia do Spring Security (ordem -100) e do {@link TenantFilter}
 * (@Order 1), entao {@code TenantContext.get()} ja esta preenchido. Registrado via
 * {@link IdempotencyConfig} (FilterRegistrationBean, ordem 2) — nao e @Component,
 * para nao ser instanciado pelos slices @WebMvcTest.
 */
@RequiredArgsConstructor
@Slf4j
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Duration TTL = Duration.ofHours(24);
    private static final String HEADER = "Idempotency-Key";

    private final IdempotencyService idempotency;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String chave = req.getHeader(HEADER);
        if (exigeChave(req) && (chave == null || chave.isBlank())) {
            problem(res, 400, "Idempotency-Key obrigatorio nesta rota");
            return;
        }
        if (chave == null || chave.isBlank()
                || !MUTATING.contains(req.getMethod())
                || !req.getRequestURI().startsWith("/v1/")) {
            chain.doFilter(req, res);
            return;
        }

        byte[] body = StreamUtils.copyToByteArray(req.getInputStream());
        // Escalar de propósito: a imobiliária primária só compõe o namespace do hash
        // (impede colisão de chave entre tenants). É estável por token; a lista inteira
        // (V25) não agrega valor aqui e complicaria o replay. Ver pendencias.md § 6.
        java.util.UUID tenantId = TenantContext.get();
        String hash = IdempotencyService.requestHash(req.getMethod(), req.getRequestURI(), tenantId, body);

        IdempotencyService.Lookup hit = idempotency.lookup(chave, hash);
        switch (hit.match()) {
            case HIT -> { replay(res, hit); return; }
            case CONFLICT -> { problem(res, 422, "Idempotency-Key reutilizada com um corpo diferente"); return; }
            case CLAIMED_IN_PROGRESS -> { problem(res, 409, "Requisicao com esta Idempotency-Key ainda em processamento"); return; }
            case MISS -> { /* segue abaixo */ }
        }

        boolean claimed = idempotency.reivindicar(chave, req.getMethod(), req.getRequestURI(), hash, tenantId, TTL);
        if (!claimed) {
            IdempotencyService.Lookup recheck = idempotency.lookup(chave, hash);
            switch (recheck.match()) {
                case HIT -> replay(res, recheck);
                case CONFLICT -> problem(res, 422, "Idempotency-Key reutilizada com um corpo diferente");
                default -> problem(res, 409, "Requisicao com esta Idempotency-Key ainda em processamento");
            }
            return;
        }

        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(req, body);
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(res);
        int status = 500;
        try {
            chain.doFilter(cached, wrapped);
            status = wrapped.getStatus();
        } finally {
            byte[] out = wrapped.getContentAsByteArray();
            try {
                if (status >= 200 && status < 300) {
                    idempotency.concluir(chave, status, new String(out, StandardCharsets.UTF_8));
                } else {
                    idempotency.liberar(chave);
                }
            } catch (RuntimeException e) {
                log.warn("[idempotency] falha ao persistir resultado da chave {}: {}", chave, e.toString());
            }
            wrapped.copyBodyToResponse();
        }
    }

    /** POST /v1/solicitacoes cria ASSIGNMENT + ExtratoItem: sem chave, duplo-clique duplica cobrança. */
    private static boolean exigeChave(HttpServletRequest req) {
        return "POST".equals(req.getMethod()) && "/v1/solicitacoes".equals(req.getRequestURI());
    }

    private void replay(HttpServletResponse res, IdempotencyService.Lookup stored) throws IOException {
        res.setStatus(stored.statusHttp());
        res.setHeader("Idempotency-Replayed", "true");
        if (stored.responseBody() != null && !stored.responseBody().isEmpty()) {
            res.setContentType("application/json");
            res.getWriter().write(stored.responseBody());
        }
    }

    private void problem(HttpServletResponse res, int status, String detail) throws IOException {
        String title = switch (status) {
            case 409 -> "Conflict";
            case 400 -> "Bad Request";
            default -> "Unprocessable Entity";
        };
        res.setStatus(status);
        res.setContentType("application/problem+json");
        res.getWriter().write(
                "{\"type\":\"about:blank\",\"title\":\"" + title
                        + "\",\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
