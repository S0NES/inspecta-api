package br.com.vistoria.core.modules.clienteb2c;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ClienteB2cController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("ClienteB2cController")
class ClienteB2cControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;

    @MockBean private ClienteB2cService service;
    @MockBean private ClienteB2cRepository clientesB2c;
    @MockBean private JwtAuthFilter jwtAuthFilter;
    @MockBean private ApiKeyFilter apiKeyFilter;

    private static UUID id(long n) { return new UUID(0L, n); }

    private static final UUID SERVICO_ENTRADA = UUID.fromString("00000000-b2c2-0000-0000-000000000001");

    private ClienteB2c cliente() {
        ClienteB2c c = new ClienteB2c();
        c.setId(id(1));
        c.setUsuarioId(id(2));
        c.setNome("Maria da Silva");
        c.setEmail("maria@example.com");
        c.setCpf("52998224725");
        c.setTelefone("11987654321");
        c.setCidadeId(id(3));
        c.setClientServices(List.of(SERVICO_ENTRADA));
        c.setCriadoEm(Instant.now());
        return c;
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/clientes-b2c cria e devolve 201 para ADMIN")
    void criar_admin_created() throws Exception {
        when(service.criarComConvite(any())).thenReturn(cliente());

        String body = """
                {"nome":"Maria da Silva","email":"maria@example.com","cpf":"52998224725",
                 "telefone":"11987654321","cidadeId":"%s","clientServices":["00000000-b2c2-0000-0000-000000000001"]}
                """.formatted(id(3));

        mvc.perform(post("/v1/clientes-b2c").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("maria@example.com"));
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("POST /v1/clientes-b2c exige ADMIN/SUPORTE — 403 para CLIENTE")
    void criar_cliente_forbidden() throws Exception {
        String body = """
                {"nome":"Maria da Silva","email":"maria@example.com","cpf":"52998224725",
                 "telefone":"11987654321","cidadeId":"%s","clientServices":["00000000-b2c2-0000-0000-000000000001"]}
                """.formatted(id(3));

        mvc.perform(post("/v1/clientes-b2c").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /v1/clientes-b2c/me exige autenticacao — 401 sem token")
    void me_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/v1/clientes-b2c/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /v1/clientes-b2c/me devolve o proprio cadastro do usuario autenticado")
    void me_authenticated_returnsOwnRecord() throws Exception {
        when(clientesB2c.findByUsuarioId(id(2))).thenReturn(Optional.of(cliente()));
        TestSecurityContextHolder.setAuthentication(new UsernamePasswordAuthenticationToken(
                id(2), null, List.of(new SimpleGrantedAuthority("ROLE_CLIENTE"))));

        mvc.perform(get("/v1/clientes-b2c/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cidadeId").value(id(3).toString()))
                .andExpect(jsonPath("$.clientServices[0]").value("00000000-b2c2-0000-0000-000000000001"));
    }

    @Test
    @DisplayName("GET /v1/clientes-b2c/me devolve 404 se o usuario nao tem cadastro B2C")
    void me_semCadastro_returns404() throws Exception {
        when(clientesB2c.findByUsuarioId(id(9))).thenReturn(Optional.empty());
        TestSecurityContextHolder.setAuthentication(new UsernamePasswordAuthenticationToken(
                id(9), null, List.of(new SimpleGrantedAuthority("ROLE_CLIENTE"))));

        mvc.perform(get("/v1/clientes-b2c/me")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/clientes-b2c/{id}/inativar devolve 204")
    void inativar_admin_noContent() throws Exception {
        mvc.perform(post("/v1/clientes-b2c/{id}/inativar", id(1)))
                .andExpect(status().isNoContent());
    }
}
