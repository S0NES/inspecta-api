package br.com.vistoria.core.modules.metrics;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import br.com.vistoria.core.shared.exception.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MetricasWebhookController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("MetricasWebhookController - webhook de entregabilidade")
class MetricasWebhookControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private ApiKeyFilter apiKeyFilter;

    @MockitoBean
    private MetricasWebhookService service;

    @Test
    @DisplayName("POST /v1/webhooks/metricas/{broker} com payload valido retorna 202 e delega ao service")
    void webhook_payloadValido_retorna202() throws Exception {
        doNothing().when(service).receber(eq("resend"), anyString(), anyMap());

        String payload = "{\"type\":\"email.delivered\",\"data\":{\"email_id\":\"abc\"}}";
        mvc.perform(post("/v1/webhooks/metricas/resend")
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isAccepted());

        verify(service).receber(eq("resend"), eq(payload), anyMap());
    }

    @Test
    @DisplayName("POST /v1/webhooks/metricas/{broker} para broker desconhecido retorna 404")
    void webhook_brokerDesconhecido_retorna404() throws Exception {
        doThrow(new NotFoundException("Broker desconhecido: xpto"))
                .when(service).receber(eq("xpto"), anyString(), anyMap());

        mvc.perform(post("/v1/webhooks/metricas/xpto")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /v1/webhooks/metricas/{broker} com JSON invalido retorna 400")
    void webhook_jsonInvalido_retorna400() throws Exception {
        doThrow(new IllegalArgumentException("Payload JSON invalido"))
                .when(service).receber(eq("resend"), anyString(), anyMap());

        mvc.perform(post("/v1/webhooks/metricas/resend")
                        .contentType("application/json")
                        .content("not json"))
                .andExpect(status().isBadRequest());
    }
}
