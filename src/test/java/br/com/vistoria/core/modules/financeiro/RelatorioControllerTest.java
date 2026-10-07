package br.com.vistoria.core.modules.financeiro;

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

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RelatorioController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("RelatorioController - Financial reporting")
class RelatorioControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private ApiKeyFilter apiKeyFilter;

    @MockitoBean
    private RelatorioService service;

    private static final UUID IMOB_100 = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID IMOB_200 = UUID.fromString("00000000-0000-0000-0000-000000000200");

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/relatorios/imobiliaria returns report for ADMIN")
    void relatorio_adminUser_returnsReport() throws Exception {
        var relatorio = new RelatorioService.RelatorioImobiliaria(
                IMOB_100, "2025-01", 1, BigDecimal.valueOf(1000), BigDecimal.valueOf(500), false, BigDecimal.valueOf(200)
        );

        when(service.imobiliaria(IMOB_100, "2025-01")).thenReturn(relatorio);

        mvc.perform(get("/v1/relatorios/imobiliaria?mes=2025-01&imobiliariaId=" + IMOB_100))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imobiliariaId").value(IMOB_100.toString()))
                .andExpect(jsonPath("$.mes").value("2025-01"));
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("GET /v1/relatorios/imobiliaria allows SUPORTE")
    void relatorio_suporteUser_returnsReport() throws Exception {
        var relatorio = new RelatorioService.RelatorioImobiliaria(
                IMOB_200, "2025-01", 1, BigDecimal.valueOf(500), BigDecimal.valueOf(250), false, BigDecimal.valueOf(100)
        );

        when(service.imobiliaria(IMOB_200, "2025-01")).thenReturn(relatorio);

        mvc.perform(get("/v1/relatorios/imobiliaria?mes=2025-01&imobiliariaId=" + IMOB_200))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("GET /v1/relatorios/imobiliaria denies CLIENTE")
    void relatorio_clienteUser_returnsForbidden() throws Exception {
        mvc.perform(get("/v1/relatorios/imobiliaria?mes=2025-01&imobiliariaId=" + IMOB_100))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/relatorios/imobiliaria without imobiliariaId returns 400")
    void relatorio_missingImobiliariaId_returnsBadRequest() throws Exception {
        when(service.imobiliaria(null, "2025-01"))
                .thenThrow(new IllegalArgumentException("imobiliariaId obrigatorio"));

        mvc.perform(get("/v1/relatorios/imobiliaria?mes=2025-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /v1/relatorios/imobiliaria requires authentication")
    void relatorio_unauthenticated_returnsUnauthorized() throws Exception {
        mvc.perform(get("/v1/relatorios/imobiliaria?mes=2025-01&imobiliariaId=" + IMOB_100))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/relatorios/vistorias lists inspections for ADMIN without agency")
    void vistorias_adminUser_returnsPage() throws Exception {
        var page = new VistoriasPage(java.util.List.of(), 0, 0, 0, java.math.BigDecimal.ZERO);
        when(service.vistorias(eq("2026-09"), eq(null), eq(null), any()))
                .thenReturn(page);

        mvc.perform(get("/v1/relatorios/vistorias?mes=2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalFaturado").value(0));
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("GET /v1/relatorios/vistorias denies CLIENTE")
    void vistorias_clienteUser_returnsForbidden() throws Exception {
        mvc.perform(get("/v1/relatorios/vistorias?mes=2026-09"))
                .andExpect(status().isForbidden());
    }
}
