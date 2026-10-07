package br.com.vistoria.core.config;

import br.com.vistoria.core.shared.idempotency.IdempotencyService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registra o {@link IdempotencyFilter} na cadeia de servlet filters, ordem 2 —
 * depois da cadeia do Spring Security (-100) e do {@link TenantFilter} (@Order 1).
 * Via FilterRegistrationBean (e nao @Component) para os slices @WebMvcTest nao
 * tentarem instanciar o filtro sem o IdempotencyService no contexto.
 */
@Configuration
public class IdempotencyConfig {

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(IdempotencyService idempotency) {
        FilterRegistrationBean<IdempotencyFilter> reg = new FilterRegistrationBean<>(new IdempotencyFilter(idempotency));
        reg.addUrlPatterns("/v1/*");
        reg.setOrder(2);
        reg.setName("idempotencyFilter");
        return reg;
    }
}
