package br.com.vistoria.core.modules.biletagemcliente;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BiletagemClienteController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("BiletagemClienteController")
class BiletagemClienteControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;

    @MockBean private BiletagemClienteService service;
    @MockBean private JwtAuthFilter jwtAuthFilter;
    @MockBean private ApiKeyFilter apiKeyFilter;

    @Test
    @DisplayName("GET /v1/config/biletagem-cliente exige autenticacao")
    void get_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/v1/config/biletagem-cliente")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("GET /v1/config/biletagem-cliente exige ADMIN/SUPORTE — 403 para CLIENTE")
    void get_cliente_forbidden() throws Exception {
        mvc.perform(get("/v1/config/biletagem-cliente")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/config/biletagem-cliente devolve a config atual")
    void get_admin_returnsConfig() throws Exception {
        when(service.get()).thenReturn(new BiletagemClienteService.BiletagemClienteView(
                BiletagemCliente.Modo.faixa, List.of(), List.of(), List.of()));

        mvc.perform(get("/v1/config/biletagem-cliente"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modo").value("faixa"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /v1/config/biletagem-cliente salva o modo percentual com as linhas enviadas")
    void put_admin_salvaPercentual() throws Exception {
        when(service.salvar(eq(BiletagemCliente.Modo.percentual), any(), any()))
                .thenReturn(new BiletagemClienteService.BiletagemClienteView(
                        BiletagemCliente.Modo.percentual, List.of(), List.of(), List.of()));

        String body = """
                {"modo":"percentual","percentuais":[
                    {"b2cServiceId":"00000000-b2c2-0000-0000-000000000001","percentual":10,"minimo":50},
                    {"b2cServiceId":"00000000-b2c2-0000-0000-000000000002","percentual":8,"minimo":null}
                ]}
                """;

        mvc.perform(put("/v1/config/biletagem-cliente").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modo").value("percentual"));

        verify(service).salvar(eq(BiletagemCliente.Modo.percentual), any(), any());
    }
}
