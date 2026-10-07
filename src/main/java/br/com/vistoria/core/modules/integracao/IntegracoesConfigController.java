package br.com.vistoria.core.modules.integracao;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Diz ao painel de integrações quais provedores estão realmente ligados, para que o frontend
 * decida qual aba de pagamento/assinatura renderizar (inspecta-messenger por nome). Não instancia nenhum
 * gateway/broker — o inspecta-api só reporta os nomes; quem cobra é o inspecta-worker e quem assina é o
 * inspecta-messenger. Os valores vêm das mesmas envs/secrets que configuram cada integração:
 * {@code PAYMENT_PROVIDER}, {@code SIGNATURE_BROKER} e {@code PLENO_MODE}.
 */
@RestController
@Tag(name = "Integrações", description = "Provedores ativos de pagamento/assinatura/Pleno")
public class IntegracoesConfigController {

    @Value("${inspecta.payment.provider:mock}")
    private String payment;

    @Value("${inspecta.signature.broker:mock}")
    private String signature;

    @Value("${inspecta.pleno.mode:mock}")
    private String pleno;

    /** Nome do provedor ativo de cada integração — "mock" quando não configurado. */
    public record IntegracoesConfig(String payment, String signature, String pleno) {}

    @GetMapping("/v1/integracoes/config")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE')")
    @Operation(summary = "Provedores de integração ativos (para o painel decidir qual aba renderizar)")
    @SecurityRequirement(name = "bearerAuth")
    public IntegracoesConfig config() {
        return new IntegracoesConfig(norm(payment), norm(signature), norm(pleno));
    }

    private static String norm(String v) {
        return (v == null || v.isBlank()) ? "mock" : v.trim().toLowerCase();
    }
}
