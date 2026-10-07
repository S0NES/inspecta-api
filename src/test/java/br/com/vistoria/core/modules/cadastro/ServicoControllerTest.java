package br.com.vistoria.core.modules.cadastro;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import br.com.vistoria.core.modules.cadastro.ServicoService.ServicoView;
import br.com.vistoria.core.modules.cadastro.ServicoService.TipoRef;
import br.com.vistoria.core.shared.exception.NotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ServicoController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("ServicoController - Serviço guarda-chuva (V3), admin/suporte only")
class ServicoControllerTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;
    @MockitoBean
    private ApiKeyFilter apiKeyFilter;
    @MockitoBean
    private ServicoService servicos;

    private static final UUID SERVICO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TIPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID TIPO_ID_99 = UUID.fromString("00000000-0000-0000-0000-000000000099");

    private ServicoView sample() {
        return new ServicoView(SERVICO_ID, "Elegance", true, List.of(new TipoRef(TIPO_ID, "Vistoria de entrada", null)));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/servicos lists for ADMIN")
    void list_admin_ok() throws Exception {
        when(servicos.listar()).thenReturn(List.of(sample()));
        mvc.perform(get("/v1/servicos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nome").value("Elegance"))
                .andExpect(jsonPath("$[0].tipos[0].id").value(TIPO_ID.toString()));
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("GET /v1/servicos allows SUPORTE")
    void list_suporte_ok() throws Exception {
        when(servicos.listar()).thenReturn(List.of());
        mvc.perform(get("/v1/servicos")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "IMOB_GERENCIA")
    @DisplayName("GET /v1/servicos denies IMOB_GERENCIA")
    void list_imob_forbidden() throws Exception {
        mvc.perform(get("/v1/servicos")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("POST /v1/servicos denies CLIENTE")
    void post_cliente_forbidden() throws Exception {
        mvc.perform(post("/v1/servicos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"X\",\"tipoServicoIds\":[\"00000000-0000-0000-0000-000000000001\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "VISTORIADOR")
    @DisplayName("PATCH /v1/servicos/{id} denies VISTORIADOR")
    void patch_vistoriador_forbidden() throws Exception {
        mvc.perform(patch("/v1/servicos/" + SERVICO_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ativo\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "IMOB_OPERACAO")
    @DisplayName("DELETE /v1/servicos/{id} denies IMOB_OPERACAO")
    void delete_imobOperacao_forbidden() throws Exception {
        mvc.perform(delete("/v1/servicos/" + SERVICO_ID)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/servicos creates for ADMIN -> 201")
    void post_admin_created() throws Exception {
        when(servicos.criar(any(), any())).thenReturn(sample());
        mvc.perform(post("/v1/servicos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Elegance\",\"tipoServicoIds\":[\"00000000-0000-0000-0000-000000000010\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(SERVICO_ID.toString()));
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("PATCH /v1/servicos/{id} updates for SUPORTE -> 200")
    void patch_suporte_ok() throws Exception {
        when(servicos.atualizar(any(), any(), any(), any())).thenReturn(sample());
        mvc.perform(patch("/v1/servicos/" + SERVICO_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Elegance\",\"ativo\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Elegance"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("DELETE /v1/servicos/{id} -> 204 for ADMIN")
    void delete_admin_noContent() throws Exception {
        mvc.perform(delete("/v1/servicos/" + SERVICO_ID)).andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /v1/servicos/{servicoId}/tipos/{tipoServicoId}/pleno-codigo updates for ADMIN -> 200")
    void patchPlenoCodigo_admin_ok() throws Exception {
        when(servicos.listar()).thenReturn(List.of(sample()));
        mvc.perform(patch("/v1/servicos/" + SERVICO_ID + "/tipos/" + TIPO_ID + "/pleno-codigo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":12}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SERVICO_ID.toString()));
        org.mockito.Mockito.verify(servicos).atualizarCodigoPleno(SERVICO_ID, TIPO_ID, 12);
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("PATCH /v1/servicos/{servicoId}/tipos/{tipoServicoId}/pleno-codigo updates for SUPORTE -> 200")
    void patchPlenoCodigo_suporte_ok() throws Exception {
        when(servicos.listar()).thenReturn(List.of(sample()));
        mvc.perform(patch("/v1/servicos/" + SERVICO_ID + "/tipos/" + TIPO_ID + "/pleno-codigo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SERVICO_ID.toString()));
        org.mockito.Mockito.verify(servicos).atualizarCodigoPleno(SERVICO_ID, TIPO_ID, null);
    }

    @Test
    @WithMockUser(roles = "VISTORIADOR")
    @DisplayName("PATCH /v1/servicos/{servicoId}/tipos/{tipoServicoId}/pleno-codigo denies VISTORIADOR")
    void patchPlenoCodigo_vistoriador_forbidden() throws Exception {
        mvc.perform(patch("/v1/servicos/" + SERVICO_ID + "/tipos/" + TIPO_ID + "/pleno-codigo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":12}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /v1/servicos/{servicoId}/tipos/{tipoServicoId}/pleno-codigo -> 404 quando o vinculo nao existe")
    void patchPlenoCodigo_naoEncontrado() throws Exception {
        doThrow(new NotFoundException("Vinculo entre servico 1 e tipo de servico 99 nao encontrado"))
                .when(servicos).atualizarCodigoPleno(any(), any(), any());
        mvc.perform(patch("/v1/servicos/" + SERVICO_ID + "/tipos/" + TIPO_ID_99 + "/pleno-codigo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":12}"))
                .andExpect(status().isNotFound());
    }
}
