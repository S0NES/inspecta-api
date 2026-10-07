package br.com.vistoria.core.modules.metrics;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Métricas de envio", description = "Webhook único de entregabilidade — provedores reportam status de mensagens de saída")
public class MetricasWebhookController {

    private final MetricasWebhookService service;

    @PostMapping("/v1/webhooks/metricas/{broker}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Webhook de status de entregabilidade",
            description = "Recebe notificações de status de um provedor (broker) de envio. Autenticado por API key "
                    + "com escopo `metricas` (enforçado no SecurityConfig, não via @PreAuthorize). O interpretador "
                    + "do broker converte o payload no canônico e publica no RabbitMQ; o inspecta-worker persiste.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Payload aceito para processamento assíncrono"),
            @ApiResponse(responseCode = "400", description = "Payload JSON inválido"),
            @ApiResponse(responseCode = "401", description = "API key ausente ou inválida"),
            @ApiResponse(responseCode = "403", description = "API key sem escopo `metricas`"),
            @ApiResponse(responseCode = "404", description = "Broker desconhecido (sem interpretador registrado)")
    })
    public void receber(@PathVariable String broker,
                        @RequestBody String payload,
                        @RequestHeader Map<String, String> headers) {
        service.receber(broker, payload, headers);
    }
}
