package br.com.vistoria.core.modules.solicitacao;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import br.com.vistoria.core.modules.clienteb2c.ClienteB2c;
import br.com.vistoria.core.modules.disponibilidade.DisponibilidadeRepositories.VistoriadorRepository;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.ServicoRepository;
import br.com.vistoria.core.modules.usuario.UsuarioRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SolicitacaoB2cController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("SolicitacaoB2cController")
class SolicitacaoB2cControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;

    @MockBean private SolicitacaoB2cService service;
    @MockBean private ServicoRepository servicos;
    @MockBean private VistoriadorRepository vistoriadores;
    @MockBean private UsuarioRepository usuarios;
    @MockBean private JwtAuthFilter jwtAuthFilter;
    @MockBean private ApiKeyFilter apiKeyFilter;

    private static UUID id(long n) { return new UUID(0L, n); }

    private void autenticarComoCliente(UUID usuarioId) {
        TestSecurityContextHolder.setAuthentication(new UsernamePasswordAuthenticationToken(
                usuarioId, null, List.of(new SimpleGrantedAuthority("ROLE_CLIENTE"))));
    }

    private Solicitacao solicitacao() {
        Solicitacao s = new Solicitacao();
        s.setId(id(20));
        s.setClienteB2cId(id(100));
        return s;
    }

    private SolicitacaoServico servico() {
        SolicitacaoServico ss = new SolicitacaoServico();
        ss.setId(id(30));
        ss.setSolicitacaoId(id(20));
        ss.setStatus(SolicitacaoServico.Status.atribuida);
        ss.setPrecoSnapshot(new BigDecimal("189.00"));
        ss.setDataAgendada(Instant.now());
        return ss;
    }

    private String rascunhoBody() {
        return """
                {"imovel":{"cidadeId":"%s","endereco":"Praca da Se","metragem":50},
                 "b2cServiceId":"00000000-b2c2-0000-0000-000000000001","dataDesejada":"2026-10-01T13:00:00Z"}
                """.formatted(id(3));
    }

    @Test
    @DisplayName("POST /v1/solicitacoes/rascunho-b2c exige autenticacao")
    void criar_unauthenticated_returns401() throws Exception {
        mvc.perform(post("/v1/solicitacoes/rascunho-b2c")
                        .contentType(MediaType.APPLICATION_JSON).content(rascunhoBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/solicitacoes/rascunho-b2c exige role CLIENTE — 403 para ADMIN")
    void criar_admin_forbidden() throws Exception {
        mvc.perform(post("/v1/solicitacoes/rascunho-b2c")
                        .contentType(MediaType.APPLICATION_JSON).content(rascunhoBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /v1/solicitacoes/rascunho-b2c: happy path devolve 201 com o servico reservado")
    void criar_happyPath_created() throws Exception {
        autenticarComoCliente(id(1));
        when(service.criarRascunhoB2c(org.mockito.ArgumentMatchers.eq(id(1)), any())).thenReturn(solicitacao());
        when(servicos.findBySolicitacaoId(id(20))).thenReturn(List.of(servico()));

        mvc.perform(post("/v1/solicitacoes/rascunho-b2c")
                        .contentType(MediaType.APPLICATION_JSON).content(rascunhoBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.solicitacaoId").value(id(20).toString()))
                .andExpect(jsonPath("$.status").value("atribuida"))
                .andExpect(jsonPath("$.precoSnapshot").value(189.00));
    }

    @Test
    @DisplayName("POST /v1/solicitacoes/rascunho-b2c: sem vistoriador -> 409 SEM_VISTORIADOR")
    void criar_semVistoriador_returns409() throws Exception {
        autenticarComoCliente(id(1));
        when(service.criarRascunhoB2c(org.mockito.ArgumentMatchers.eq(id(1)), any()))
                .thenThrow(new SemVistoriadorException(List.of(id(200))));

        mvc.perform(post("/v1/solicitacoes/rascunho-b2c")
                        .contentType(MediaType.APPLICATION_JSON).content(rascunhoBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("SEM_VISTORIADOR"));
    }

    @Test
    @DisplayName("POST /v1/solicitacoes/{id}/finalizar: sem interveniente -> 400")
    void finalizar_semInterveniente_returns400() throws Exception {
        autenticarComoCliente(id(1));
        when(service.finalizarRascunhoB2c(id(1), id(20)))
                .thenThrow(new IllegalArgumentException("ao_menos_um_interveniente_obrigatorio"));

        mvc.perform(post("/v1/solicitacoes/{id}/finalizar", id(20)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("ao_menos_um_interveniente_obrigatorio"));
    }

    @Test
    @DisplayName("POST /v1/solicitacoes/{id}/finalizar: happy path devolve 200")
    void finalizar_happyPath_ok() throws Exception {
        autenticarComoCliente(id(1));
        when(service.finalizarRascunhoB2c(id(1), id(20))).thenReturn(solicitacao());
        when(servicos.findBySolicitacaoId(id(20))).thenReturn(List.of(servico()));

        mvc.perform(post("/v1/solicitacoes/{id}/finalizar", id(20)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.solicitacaoId").value(id(20).toString()));
    }

    @Test
    @DisplayName("PATCH /v1/solicitacoes/{id}/intervenientes: happy path devolve 200")
    void substituirIntervenientes_ok() throws Exception {
        autenticarComoCliente(id(1));

        mvc.perform(patch("/v1/solicitacoes/{id}/intervenientes", id(20))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intervenientes\":[{\"papelSlug\":\"locador\",\"nome\":\"Joao da Silva\",\"cpf\":\"52998224725\",\"email\":\"joao@example.com\"}]}"))
                .andExpect(status().isOk());
    }
}
