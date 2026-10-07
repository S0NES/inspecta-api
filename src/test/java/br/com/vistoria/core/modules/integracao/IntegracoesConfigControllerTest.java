package br.com.vistoria.core.modules.integracao;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IntegracoesConfigController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@TestPropertySource(properties = {
        "inspecta.payment.provider=AbacatePay",
        "inspecta.signature.broker=autentique",
        "inspecta.pleno.mode=http",
})
@DisplayName("IntegracoesConfigController - provedores de integração ativos")
class IntegracoesConfigControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private ApiKeyFilter apiKeyFilter;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/integracoes/config devolve os provedores normalizados para ADMIN")
    void config_admin_returnsProviders() throws Exception {
        mvc.perform(get("/v1/integracoes/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment").value("abacatepay"))
                .andExpect(jsonPath("$.signature").value("autentique"))
                .andExpect(jsonPath("$.pleno").value("http"));
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("GET /v1/integracoes/config permite SUPORTE")
    void config_suporte_allowed() throws Exception {
        mvc.perform(get("/v1/integracoes/config"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("GET /v1/integracoes/config nega CLIENTE")
    void config_cliente_forbidden() throws Exception {
        mvc.perform(get("/v1/integracoes/config"))
                .andExpect(status().isForbidden());
    }
}
