package br.com.vistoria.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

// Ativo em todo profile (não só test/dev): desde que o inspecta-web passou a ser servido pelo
// Cloudflare Pages, ele deixou de ser same-origin com o inspecta-api também em prod — precisa
// de CORS de verdade lá, não só em dev. Origem exata (nunca "*"), nunca em faixa livre:
// inspecta.cors.allowed-origins é fail-fast em prod (application-prod.yml), com default
// de localhost:5173 só entrando quando a chave nem existe no ambiente (test/dev).
@Configuration
public class CorsConfig {

    @Value("${inspecta.cors.allowed-origins:http://localhost:5173}")
    private List<String> allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        allowedOrigins.forEach(config::addAllowedOrigin);
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.setAllowCredentials(true); // obrigatório: o auth é por cookie httpOnly
        config.setMaxAge(3600L);

        // Webhook de métricas: a autenticação é por API key no header X-API-Key, nunca por
        // cookie. Sem credencial de navegador em jogo, a origem pode ser curinga (o provedor
        // chama de onde quiser) e allowCredentials fica false — combinação que o CORS proíbe
        // com "*". Registrado ANTES de "/**": o UrlBasedCorsConfigurationSource devolve a
        // primeira entrada que casa, na ordem de inserção. Os demais webhooks de terceiros
        // (autentique, pagamento, brokers) entram pelo inspecta-messenger, não pelo inspecta-api.
        var webhookConfig = new CorsConfiguration();
        webhookConfig.addAllowedOriginPattern("*");
        webhookConfig.addAllowedMethod("*");
        webhookConfig.addAllowedHeader("*");
        webhookConfig.setAllowCredentials(false);
        webhookConfig.setMaxAge(3600L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/v1/webhooks/metricas/**", webhookConfig);
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
