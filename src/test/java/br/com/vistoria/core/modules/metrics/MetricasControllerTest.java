package br.com.vistoria.core.modules.metrics;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MetricasController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("MetricasController - leitura D-1 dos paineis")
class MetricasControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private ApiKeyFilter apiKeyFilter;

    @MockitoBean
    private MetricasQueryService queryService;

    private static final String INICIO = "2026-07-01";
    private static final String FIM = "2026-07-31";

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/metricas/envios 200 para ADMIN")
    void envios_admin_200() throws Exception {
        when(queryService.envios(any(), any(), isNull(), isNull()))
                .thenReturn(new MetricasQueryService.EnviosResposta(
                        LocalDate.parse(INICIO), LocalDate.parse(FIM), List.of(), null));

        mvc.perform(get("/v1/metricas/envios").param("inicio", INICIO).param("fim", FIM))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("GET /v1/metricas/solicitacoes 200 para SUPORTE")
    void solicitacoes_suporte_200() throws Exception {
        when(queryService.solicitacoes(any(), any()))
                .thenReturn(new MetricasQueryService.SolicitacoesResposta(
                        LocalDate.parse(INICIO), LocalDate.parse(FIM), List.of()));

        mvc.perform(get("/v1/metricas/solicitacoes").param("inicio", INICIO).param("fim", FIM))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("GET /v1/metricas/envios 403 para CLIENTE")
    void envios_cliente_403() throws Exception {
        mvc.perform(get("/v1/metricas/envios").param("inicio", INICIO).param("fim", FIM))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("GET /v1/metricas/solicitacoes 403 para CLIENTE")
    void solicitacoes_cliente_403() throws Exception {
        mvc.perform(get("/v1/metricas/solicitacoes").param("inicio", INICIO).param("fim", FIM))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/metricas/envios 400 quando o service rejeita a janela")
    void envios_janelaInvalida_400() throws Exception {
        when(queryService.envios(any(), any(), isNull(), isNull()))
                .thenThrow(new IllegalArgumentException("inicio deve ser menor ou igual a fim"));

        mvc.perform(get("/v1/metricas/envios").param("inicio", FIM).param("fim", INICIO))
                .andExpect(status().isBadRequest());
    }
}
