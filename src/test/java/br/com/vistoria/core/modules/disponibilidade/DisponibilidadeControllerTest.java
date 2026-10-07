package br.com.vistoria.core.modules.disponibilidade;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import br.com.vistoria.core.modules.disponibilidade.DisponibilidadeRepositories.*;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.ServicoRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DisponibilidadeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("DisponibilidadeController - Surveyor availability management")
class DisponibilidadeControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private ApiKeyFilter apiKeyFilter;

    @MockitoBean
    private VistoriadorRepository vistoriadores;

    @MockitoBean
    private DisponibilidadeRepository disponibilidades;

    @MockitoBean
    private ExcecaoRepository excecoes;

    @MockitoBean
    private ServicoRepository servicos;

    @MockitoBean
    private br.com.vistoria.core.modules.integracao.IntegracaoSyncService integracaoSync;

    @Autowired
    private ObjectMapper mapper;

    private static final String USER_42_STR = "00000000-0000-0000-0000-00000000002a";
    private static final UUID USER_42 = UUID.fromString(USER_42_STR);
    private static final UUID USER_100 = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ID_5 = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID ID_9 = UUID.fromString("00000000-0000-0000-0000-000000000009");

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/vistoriadores creates surveyor for ADMIN")
    void create_adminUser_createsSurveyor() throws Exception {
        var vistoriador = new Vistoriador();
        vistoriador.setId(ID_1);
        vistoriador.setUsuarioId(ID_1);

        when(vistoriadores.save(any(Vistoriador.class))).thenReturn(vistoriador);

        mvc.perform(post("/v1/vistoriadores")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(vistoriador)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.usuarioId").value(ID_1.toString()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/vistoriadores never exposes dadosPagamento (security regression guard)")
    void create_adminUser_neverLeaksDadosPagamento() throws Exception {
        var vistoriador = new Vistoriador();
        vistoriador.setId(ID_1);
        vistoriador.setUsuarioId(ID_1);
        vistoriador.setDadosPagamento("{\"chavePix\":\"secret-bank-detail@example.com\"}");

        when(vistoriadores.save(any(Vistoriador.class))).thenReturn(vistoriador);

        mvc.perform(post("/v1/vistoriadores")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(vistoriador)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dadosPagamento").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("dadosPagamento"))));
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("POST /v1/vistoriadores denies non-ADMIN users")
    void create_nonAdminUser_returnsForbidden() throws Exception {
        var vistoriador = new Vistoriador();
        vistoriador.setUsuarioId(ID_1);

        mvc.perform(post("/v1/vistoriadores")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(vistoriador)))
                .andExpect(status().isForbidden());
    }

    /** Vistoriador cujo usuario_id == principal de teste; usado nos casos VISTORIADOR abaixo. */
    private void mockVistoriadorDoLogin(UUID vistoriadorId, UUID usuarioId) {
        var v = new Vistoriador();
        v.setId(vistoriadorId);
        v.setUsuarioId(usuarioId);
        when(vistoriadores.findById(vistoriadorId)).thenReturn(java.util.Optional.of(v));
    }

    @Test
    @WithMockUser(username = USER_42_STR, roles = "VISTORIADOR")
    @DisplayName("POST /v1/vistoriadores/{id}/disponibilidade adds availability slot (próprio)")
    void addSlot_vistoriadorUser_addsSlot() throws Exception {
        mockVistoriadorDoLogin(ID_1, USER_42);
        var disponibilidade = new Disponibilidade();
        disponibilidade.setId(ID_1);
        disponibilidade.setVistoriadorId(ID_1);
        disponibilidade.setHoraInicio(LocalTime.of(9, 0));
        disponibilidade.setHoraFim(LocalTime.of(10, 0));

        when(disponibilidades.save(any(Disponibilidade.class))).thenReturn(disponibilidade);

        mvc.perform(post("/v1/vistoriadores/" + ID_1 + "/disponibilidade")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(disponibilidade)))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(username = USER_42_STR, roles = "VISTORIADOR")
    @DisplayName("POST /v1/vistoriadores/{id}/disponibilidade with invalid times returns 400")
    void addSlot_invalidTimes_returnsBadRequest() throws Exception {
        mockVistoriadorDoLogin(ID_1, USER_42);
        var disponibilidade = new Disponibilidade();
        disponibilidade.setVistoriadorId(ID_1);
        disponibilidade.setHoraInicio(LocalTime.of(10, 0));
        disponibilidade.setHoraFim(LocalTime.of(9, 0)); // fim antes de inicio

        mvc.perform(post("/v1/vistoriadores/" + ID_1 + "/disponibilidade")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(disponibilidade)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = USER_42_STR, roles = "VISTORIADOR")
    @DisplayName("VISTORIADOR não mexe na disponibilidade de outro vistoriador (403)")
    void addSlot_outroVistoriador_forbidden() throws Exception {
        // vistoriador 9 pertence ao usuário 100, não ao 42 logado
        var outro = new Vistoriador();
        outro.setId(ID_9);
        outro.setUsuarioId(USER_100);
        when(vistoriadores.findById(ID_9)).thenReturn(java.util.Optional.of(outro));
        var d = new Disponibilidade();
        d.setVistoriadorId(ID_9);
        d.setHoraInicio(LocalTime.of(9, 0));
        d.setHoraFim(LocalTime.of(10, 0));

        mvc.perform(post("/v1/vistoriadores/" + ID_9 + "/disponibilidade")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(d)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = USER_42_STR, roles = "VISTORIADOR")
    @DisplayName("VISTORIADOR não deleta slot de outro vistoriador (403)")
    void removeSlot_outroVistoriador_forbidden() throws Exception {
        var outro = new Vistoriador();
        outro.setId(ID_9);
        outro.setUsuarioId(USER_100);
        when(vistoriadores.findById(ID_9)).thenReturn(java.util.Optional.of(outro));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                "/v1/vistoriadores/" + ID_9 + "/disponibilidade/" + ID_5))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/vistoriadores/{id}/excecoes adds exception")
    void addExcecao_adminUser_addsException() throws Exception {
        var excecao = new DisponibilidadeExcecao();
        excecao.setId(ID_1);
        excecao.setVistoriadorId(ID_1);
        excecao.setTipo(DisponibilidadeExcecao.Tipo.ferias);
        excecao.setDataInicio(LocalDate.of(2025, 12, 25));
        excecao.setDataFim(LocalDate.of(2025, 12, 26));

        when(excecoes.save(any(DisponibilidadeExcecao.class))).thenReturn(excecao);

        mvc.perform(post("/v1/vistoriadores/" + ID_1 + "/excecoes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(excecao)))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/vistoriadores/disponibilidade/geral lists availability")
    void geral_adminUser_returnsAvailability() throws Exception {
        var disponibilidade = new Disponibilidade();
        disponibilidade.setId(ID_1);
        disponibilidade.setVistoriadorId(ID_1);

        var page = new PageImpl<>(List.of(disponibilidade), PageRequest.of(0, 20), 1);
        when(disponibilidades.findAll(any(Pageable.class))).thenReturn(page);

        mvc.perform(get("/v1/vistoriadores/disponibilidade/geral?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("GET /v1/vistoriadores/disponibilidade/geral denies unauthorized")
    void geral_clienteUser_returnsForbidden() throws Exception {
        mvc.perform(get("/v1/vistoriadores/disponibilidade/geral?page=0&size=20"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/vistoriadores/{id}/disponibilidade lista a grade semanal")
    void slots_adminUser_returnsWeeklyGrid() throws Exception {
        var slot = new Disponibilidade();
        slot.setId(ID_1);
        slot.setVistoriadorId(ID_5);
        slot.setDiaSemana(1);
        slot.setHoraInicio(LocalTime.of(8, 0));
        slot.setHoraFim(LocalTime.of(12, 0));
        when(disponibilidades.findByVistoriadorIdOrderByDiaSemanaAscHoraInicioAsc(ID_5))
                .thenReturn(List.of(slot));

        mvc.perform(get("/v1/vistoriadores/" + ID_5 + "/disponibilidade"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].diaSemana").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/vistoriadores/{id}/excecoes lista folgas/bloqueios")
    void excecoesList_adminUser_returnsExceptions() throws Exception {
        var e = new DisponibilidadeExcecao();
        e.setId(ID_2);
        e.setVistoriadorId(ID_5);
        e.setTipo(DisponibilidadeExcecao.Tipo.ferias);
        e.setDataInicio(LocalDate.of(2026, 12, 20));
        e.setDataFim(LocalDate.of(2026, 12, 31));
        when(excecoes.findByVistoriadorIdOrderByDataInicioAsc(ID_5)).thenReturn(List.of(e));

        mvc.perform(get("/v1/vistoriadores/" + ID_5 + "/excecoes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tipo").value("ferias"));
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("GET /v1/vistoriadores/{id}/disponibilidade nega CLIENTE")
    void slots_clienteUser_forbidden() throws Exception {
        mvc.perform(get("/v1/vistoriadores/" + ID_5 + "/disponibilidade"))
                .andExpect(status().isForbidden());
    }
}
