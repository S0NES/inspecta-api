package br.com.vistoria.core.modules.imobiliaria;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import br.com.vistoria.core.modules.contrato.Contrato;
import br.com.vistoria.core.modules.contrato.ContratoResumoService;
import br.com.vistoria.core.modules.contrato.ContratoResumoService.ContratoResumo;
import br.com.vistoria.core.modules.contrato.ContratoRepositories.ContratoRepository;
import br.com.vistoria.core.modules.contrato.ContratoRepositories.ContratoServicoGrupoRepository;
import br.com.vistoria.core.modules.contrato.ContratoRepositories.FaixaRepository;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.ServicoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ImobiliariaContratoController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("ImobiliariaContratoController - contrato as sub-resource (V3)")
class ImobiliariaContratoControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;
    @MockitoBean
    private ApiKeyFilter apiKeyFilter;
    @MockitoBean
    private ContratoRepository contratos;
    @MockitoBean
    private ContratoServicoGrupoRepository grupos;
    @MockitoBean
    private FaixaRepository faixas;
    @MockitoBean
    private ServicoRepository servicos;
    @MockitoBean
    private ContratoResumoService resumoService;

    private static final UUID IMOB_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONTRATO_7 = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final UUID SERVICO_3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private Contrato ativo() {
        Contrato c = new Contrato();
        c.setId(CONTRATO_7);
        c.setImobiliariaId(IMOB_1);
        c.setModo(Contrato.Modo.percentual);
        c.setStatus("ativo");
        return c;
    }

    private ContratoResumo resumo() {
        return new ContratoResumo(CONTRATO_7, "percentual", new BigDecimal("10.00"), new BigDecimal("50.00"),
                List.of(), List.of(), List.of());
    }

    @Test
    @WithMockUser(roles = "IMOB_GERENCIA")
    @DisplayName("GET /contrato returns resumo")
    void get_ok() throws Exception {
        when(resumoService.resumoOuNull(IMOB_1)).thenReturn(resumo());
        mvc.perform(get("/v1/imobiliarias/" + IMOB_1 + "/contrato"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modo").value("percentual"));
    }

    @Test
    @WithMockUser(roles = "IMOB_GERENCIA")
    @DisplayName("GET /contrato returns 404 when no active contract")
    void get_none_404() throws Exception {
        when(resumoService.resumoOuNull(IMOB_1)).thenReturn(null);
        mvc.perform(get("/v1/imobiliarias/" + IMOB_1 + "/contrato")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /contrato upserts for ADMIN")
    void put_admin_ok() throws Exception {
        when(contratos.findFirstByImobiliariaIdAndStatus(IMOB_1, "ativo")).thenReturn(Optional.of(ativo()));
        when(contratos.save(any(Contrato.class))).thenAnswer(i -> i.getArgument(0));
        when(resumoService.resumoOuNull(IMOB_1)).thenReturn(resumo());

        mvc.perform(put("/v1/imobiliarias/" + IMOB_1 + "/contrato")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modo\":\"percentual\",\"percentual\":10,\"percentualMinimo\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modo").value("percentual"));
    }

    @Test
    @WithMockUser(roles = "IMOB_GERENCIA")
    @DisplayName("PUT /contrato denies IMOB_GERENCIA (write = admin/suporte)")
    void put_imob_forbidden() throws Exception {
        mvc.perform(put("/v1/imobiliarias/" + IMOB_1 + "/contrato")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modo\":\"faixa\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("PUT /contrato/grupos replaces grupos for SUPORTE")
    void putGrupos_suporte_ok() throws Exception {
        when(contratos.findFirstByImobiliariaIdAndStatus(IMOB_1, "ativo")).thenReturn(Optional.of(ativo()));
        when(grupos.findByContratoId(CONTRATO_7)).thenReturn(List.of());
        mvc.perform(put("/v1/imobiliarias/" + IMOB_1 + "/contrato/grupos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"servicoId\":\"" + SERVICO_3 + "\",\"percentual\":12}]"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("GET /contrato/grupos denies CLIENTE")
    void getGrupos_cliente_forbidden() throws Exception {
        mvc.perform(get("/v1/imobiliarias/" + IMOB_1 + "/contrato/grupos")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /contrato/faixas returns faixas rows + faixaReferencia catalog")
    void getFaixas_ok() throws Exception {
        when(contratos.findFirstByImobiliariaIdAndStatus(IMOB_1, "ativo")).thenReturn(Optional.of(ativo()));
        when(resumoService.faixasDoContrato(CONTRATO_7)).thenReturn(List.of(
                new ContratoResumoService.FaixaView(SERVICO_3, (short) 1, new BigDecimal("100.00"),
                        new BigDecimal("120.00"), null, null)));
        when(resumoService.catalogoFaixas()).thenReturn(List.of(
                new ContratoResumoService.FaixaReferenciaView((short) 1, (short) 1, new BigDecimal("0.00"),
                        new BigDecimal("44.99"), "Até 45m²")));
        mvc.perform(get("/v1/imobiliarias/" + IMOB_1 + "/contrato/faixas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.faixas[0].servicoId").value(SERVICO_3.toString()))
                .andExpect(jsonPath("$.faixas[0].faixaId").value(1))
                .andExpect(jsonPath("$.faixas[0].valorSemMobilia").value(100.00))
                .andExpect(jsonPath("$.faixas[0].valorComMobilia").value(120.00))
                .andExpect(jsonPath("$.faixaReferencia[0].id").value(1))
                .andExpect(jsonPath("$.faixaReferencia[0].rotulo").value("Até 45m²"));
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("PUT /contrato/faixas replace-all keyed by (servicoId, faixaId)")
    void putFaixas_replaceAll_ok() throws Exception {
        when(contratos.findFirstByImobiliariaIdAndStatus(IMOB_1, "ativo")).thenReturn(Optional.of(ativo()));
        when(faixas.findByContratoIdOrderByTipoServicoIdAscMetragemMinAsc(CONTRATO_7)).thenReturn(List.of());
        when(servicos.existsById(SERVICO_3)).thenReturn(true);
        when(faixas.save(any())).thenAnswer(i -> i.getArgument(0));
        when(resumoService.faixasDoContrato(CONTRATO_7)).thenReturn(List.of());
        when(resumoService.catalogoFaixas()).thenReturn(List.of());

        mvc.perform(put("/v1/imobiliarias/" + IMOB_1 + "/contrato/faixas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"servicoId\":\"" + SERVICO_3 + "\",\"faixaId\":1,\"valorSemMobilia\":100,\"valorComMobilia\":120},"
                                + "{\"servicoId\":\"" + SERVICO_3 + "\",\"faixaId\":5,\"valorSemMobilia\":300,\"valorComMobilia\":350,"
                                + "\"paStepMetragem\":75,\"paStepValor\":20}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.faixaReferencia").isArray());
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("PUT /contrato/faixas rejects duplicate (servicoId, faixaId) in body")
    void putFaixas_duplicate_rejected() throws Exception {
        when(contratos.findFirstByImobiliariaIdAndStatus(IMOB_1, "ativo")).thenReturn(Optional.of(ativo()));
        when(faixas.findByContratoIdOrderByTipoServicoIdAscMetragemMinAsc(CONTRATO_7)).thenReturn(List.of());
        when(servicos.existsById(SERVICO_3)).thenReturn(true);

        mvc.perform(put("/v1/imobiliarias/" + IMOB_1 + "/contrato/faixas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"servicoId\":\"" + SERVICO_3 + "\",\"faixaId\":1,\"valorSemMobilia\":100,\"valorComMobilia\":120},"
                                + "{\"servicoId\":\"" + SERVICO_3 + "\",\"faixaId\":1,\"valorSemMobilia\":200,\"valorComMobilia\":220}]"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("PUT /contrato/faixas rejects faixaId outside 1..5")
    void putFaixas_badFaixaId_rejected() throws Exception {
        when(contratos.findFirstByImobiliariaIdAndStatus(IMOB_1, "ativo")).thenReturn(Optional.of(ativo()));
        when(faixas.findByContratoIdOrderByTipoServicoIdAscMetragemMinAsc(CONTRATO_7)).thenReturn(List.of());
        when(servicos.existsById(SERVICO_3)).thenReturn(true);

        mvc.perform(put("/v1/imobiliarias/" + IMOB_1 + "/contrato/faixas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"servicoId\":\"" + SERVICO_3 + "\",\"faixaId\":9,\"valorSemMobilia\":100,\"valorComMobilia\":120}]"))
                .andExpect(status().isBadRequest());
    }
}
