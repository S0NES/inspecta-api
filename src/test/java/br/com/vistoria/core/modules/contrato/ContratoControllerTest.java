package br.com.vistoria.core.modules.contrato;

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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ContratoController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("ContratoController - Contract Services (T1.8)")
class ContratoControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ContratoRepositories.ContratoRepository contratos;

    @MockBean
    private ContratoRepositories.FaixaRepository faixas;

    @MockBean
    private ContratoRepositories.ServicoRepository servicos;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @MockBean
    private ApiKeyFilter apiKeyFilter;

    @Autowired
    private ObjectMapper mapper;

    private static final UUID CONTRATO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TIPO_SERVICO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SERVICO_ID_99 = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final UUID SERVICO_ID_50 = UUID.fromString("00000000-0000-0000-0000-000000000050");

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/contratos/{id}/servicos creates new contrato_servicos entry")
    void postContratoServicos_createsNewEntry() throws Exception {
        var result = new ContratoServico();
        result.setId(SERVICO_ID_99);
        result.setContratoId(CONTRATO_ID);
        result.setTipoServicoId(TIPO_SERVICO_ID);
        result.setAtivo(true);
        when(servicos.findFirstByContratoIdAndTipoServicoId(CONTRATO_ID, TIPO_SERVICO_ID)).thenReturn(java.util.Optional.empty());
        when(servicos.save(any(ContratoServico.class))).thenReturn(result);

        ContratoServico servicoToCreate = new ContratoServico();
        servicoToCreate.setTipoServicoId(TIPO_SERVICO_ID);
        servicoToCreate.setAtivo(true);

        mvc.perform(post("/v1/contratos/" + CONTRATO_ID + "/servicos")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(servicoToCreate)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(SERVICO_ID_99.toString()))
                .andExpect(jsonPath("$.contratoId").value(CONTRATO_ID.toString()))
                .andExpect(jsonPath("$.tipoServicoId").value(TIPO_SERVICO_ID.toString()))
                .andExpect(jsonPath("$.ativo").value(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/contratos/{id}/servicos updates existing entry if already exists")
    void postContratoServicos_updatesExistingEntry() throws Exception {
        var result = new ContratoServico();
        result.setId(SERVICO_ID_50);
        result.setContratoId(CONTRATO_ID);
        result.setTipoServicoId(TIPO_SERVICO_ID);
        result.setAtivo(false);
        var existing = new ContratoServico();
        existing.setId(SERVICO_ID_50);
        existing.setContratoId(CONTRATO_ID);
        existing.setTipoServicoId(TIPO_SERVICO_ID);
        when(servicos.findFirstByContratoIdAndTipoServicoId(CONTRATO_ID, TIPO_SERVICO_ID)).thenReturn(java.util.Optional.of(existing));
        when(servicos.save(any(ContratoServico.class))).thenReturn(result);

        ContratoServico update = new ContratoServico();
        update.setTipoServicoId(TIPO_SERVICO_ID);
        update.setAtivo(false);

        mvc.perform(post("/v1/contratos/" + CONTRATO_ID + "/servicos")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(update)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(SERVICO_ID_50.toString()))
                .andExpect(jsonPath("$.contratoId").value(CONTRATO_ID.toString()))
                .andExpect(jsonPath("$.tipoServicoId").value(TIPO_SERVICO_ID.toString()))
                .andExpect(jsonPath("$.ativo").value(false));
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("POST /v1/contratos/{id}/servicos requires ADMIN role")
    void postContratoServicos_requiresAdminRole() throws Exception {
        ContratoServico servicoToCreate = new ContratoServico();
        servicoToCreate.setTipoServicoId(TIPO_SERVICO_ID);
        servicoToCreate.setAtivo(true);

        mvc.perform(post("/v1/contratos/" + CONTRATO_ID + "/servicos")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(servicoToCreate)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/contratos lists contracts (ADMIN/SUPORTE/IMOB_GERENCIA only)")
    void getContratos_admin_listAllContratos() throws Exception {
        var pageContent = new PageImpl<Contrato>(List.of(), Pageable.unpaged(), 0);
        when(contratos.findAll(any(Pageable.class))).thenReturn(pageContent);

        mvc.perform(get("/v1/contratos")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("GET /v1/contratos requires ADMIN/SUPORTE/IMOB_GERENCIA")
    void getContratos_clienteUser_returnsForbidden() throws Exception {
        mvc.perform(get("/v1/contratos")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isForbidden());
    }
}
