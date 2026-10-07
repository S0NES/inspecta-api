package br.com.vistoria.core.modules.integracao;

import br.com.vistoria.core.gateway.HttpGateway;
import br.com.vistoria.core.gateway.HttpGatewayRequest;
import br.com.vistoria.core.gateway.HttpGatewayResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component("http")
@RequiredArgsConstructor
@Slf4j
public class PlenoClientHttp implements PlenoClient {

    private static final String PESSOA = "pessoa";
    private static final String IMOVEL = "imovel";
    private static final String SOLICITACAO = "solicitacao";

    private final PlenoTokenStore tokenStore;
    private final PlenoAuthService authService;
    private final HttpGateway httpGateway;
    private final IntegracaoTokenCacheRepository cache;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${inspecta.pleno.base-url:}") private String baseUrl;
    @Value("${inspecta.pleno.cache-ttl-seconds:900}") private long cacheTtlSeconds;
    // Leitura on-demand roda em thread de request do inspecta-api (arquitetura.md regra 1): nao pode
    // ficar presa nos 30s default do RealHttpGateway (esse default e pro lado async do
    // inspecta-worker). timeout-seconds sobrescreve por chamada via HttpGatewayRequest.comTimeout,
    // que efetivamente aborta o HttpClient.send(...) - diferente do slow-call-duration-threshold
    // do circuit breaker, que so classifica a chamada como lenta pras stats, sem cancelar nada.
    @Value("${inspecta.pleno.timeout-seconds:3}") private long timeoutSeconds;

    @Override
    @CircuitBreaker(name = "pleno", fallbackMethod = "pessoaFallback")
    public Resultado pessoa(String plenoId) { return chamar("pessoa", PESSOA, plenoId); }

    @Override
    @CircuitBreaker(name = "pleno", fallbackMethod = "imovelFallback")
    public Resultado imovel(String plenoId) { return chamar("imovel", IMOVEL, plenoId); }

    @Override
    @CircuitBreaker(name = "pleno", fallbackMethod = "solicitacaoFallback")
    public Resultado solicitacao(String plenoId) { return chamar("solicitacao", SOLICITACAO, plenoId); }

    private Resultado pessoaFallback(String plenoId, Throwable t) { return stale("pessoa", plenoId, t); }
    private Resultado imovelFallback(String plenoId, Throwable t) { return stale("imovel", plenoId, t); }
    private Resultado solicitacaoFallback(String plenoId, Throwable t) { return stale("solicitacao", plenoId, t); }

    private Resultado chamar(String tipo, String recursoPath, String plenoId) {
        if (baseUrl.isBlank()) throw new IllegalStateException("inspecta.pleno.base-url nao configurado");

        String token = tokenStore.get();
        if (token == null) {
            authService.renovar();
            token = tokenStore.get();
        }
        if (token == null) throw new IllegalStateException("Token Pleno indisponivel (auth de integrador falhou ou credenciais nao configuradas)");

        try {
            HttpGatewayResponse res = httpGateway.execute(HttpGatewayRequest.get(
                    URI.create(baseUrl + "/api/vistoria/" + recursoPath + "/" + plenoId + "/show"),
                    Map.of("Authorization", "Bearer " + token)).comTimeout(Duration.ofSeconds(timeoutSeconds)));
            if (!res.sucesso()) throw new RuntimeException("Pleno " + tipo + " -> HTTP " + res.status());

            JsonNode body = mapper.readTree(res.body().isBlank() ? "{}" : res.body());
            if (!body.path("success").asBoolean(false)) {
                throw new RuntimeException("Pleno " + tipo + " -> " + body.path("error").path("message").asText("erro desconhecido"));
            }
            String dataJson = body.path("data").toString();
            cache(tipo, plenoId, dataJson);
            return new Resultado(tipo, plenoId, dataJson, "pleno");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Pleno " + tipo + " indisponivel", e);
        }
    }

    private Resultado stale(String tipo, String plenoId, Throwable t) {
        log.warn("[pleno] fallback tipo={} id={} causa={}", tipo, plenoId, t.toString());
        String cached = cache.findById(cacheKey(tipo, plenoId))
                .filter(c -> Instant.now().isBefore(c.getExpiraEm()))
                .map(IntegracaoTokenCache::getValor)
                .orElse(null);
        if (cached != null) return new Resultado(tipo, plenoId, cached, "cache");
        return new Resultado(tipo, plenoId, null, "indisponivel");
    }

    private void cache(String tipo, String plenoId, String json) {
        IntegracaoTokenCache entry = new IntegracaoTokenCache();
        entry.setChave(cacheKey(tipo, plenoId));
        entry.setValor(json);
        entry.setExpiraEm(Instant.now().plusSeconds(cacheTtlSeconds));
        cache.save(entry);
    }

    private String cacheKey(String tipo, String plenoId) { return "pleno:cache:" + tipo + ":" + plenoId; }
}
