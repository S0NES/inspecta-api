package br.com.vistoria.core.modules.imobiliaria;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import br.com.vistoria.core.modules.contrato.ContratoResumoService;
import br.com.vistoria.core.modules.contrato.ContratoResumoService.ContratoResumo;
import br.com.vistoria.core.modules.imobiliaria.dto.CreateImobiliariaRequest;
import br.com.vistoria.core.modules.imobiliaria.dto.ImobiliariaResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ImobiliariaController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("ImobiliariaController - Real estate agency management")
class ImobiliariaControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private ApiKeyFilter apiKeyFilter;

    @MockitoBean
    private ImobiliariaService service;

    @MockitoBean
    private ContratoResumoService contratoResumo;

    @Autowired
    private ObjectMapper mapper;

    private static final UUID ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ID_5 = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID ID_7 = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final UUID ID_50 = UUID.fromString("00000000-0000-0000-0000-000000000050");
    private static final UUID ID_100 = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID TEMPLATE_3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/imobiliarias creates real estate agency for ADMIN")
    void create_adminUser_createsAgency() throws Exception {
        var request = new CreateImobiliariaRequest("Acme Imóveis", "acme@example.com", "11.222.333/0001-81", null, null, "11987654321");
        var response = new ImobiliariaResponse(ID_1, "Acme Imóveis", "acme@example.com", "11.222.333/0001-81", true);

        when(service.create(any(CreateImobiliariaRequest.class))).thenReturn(response);

        mvc.perform(post("/v1/imobiliarias")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ID_1.toString()))
                .andExpect(jsonPath("$.razaoSocial").value("Acme Imóveis"));
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("POST /v1/imobiliarias denies non-ADMIN users")
    void create_nonAdminUser_returnsForbidden() throws Exception {
        var request = new CreateImobiliariaRequest("New Agency", "new@example.com", "98.765.432/0001-90", null, null, "11999999999");

        mvc.perform(post("/v1/imobiliarias")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/imobiliarias/{id} returns agency for ADMIN")
    void getById_adminUser_returnsAgency() throws Exception {
        var response = new ImobiliariaResponse(ID_100, "Example Agency", "example@example.com", "11.222.333/0001-44", true);

        when(service.findById(ID_100)).thenReturn(response);
        when(contratoResumo.resumoOuNull(ID_100)).thenReturn(new ContratoResumo(
                ID_7, "percentual", new java.math.BigDecimal("10.00"), new java.math.BigDecimal("50.00"),
                java.util.List.of(), java.util.List.of(), java.util.List.of()));

        mvc.perform(get("/v1/imobiliarias/" + ID_100))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID_100.toString()))
                .andExpect(jsonPath("$.razaoSocial").value("Example Agency"))
                .andExpect(jsonPath("$.contrato.modo").value("percentual"))
                .andExpect(jsonPath("$.contrato.percentualMinimo").value(50.00));
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("GET /v1/imobiliarias/{id} allows SUPORTE")
    void getById_suporteUser_returnsAgency() throws Exception {
        var response = new ImobiliariaResponse(ID_50, "Support Visible Agency", "support@example.com", "22.333.444/0001-55", true);

        when(service.findById(ID_50)).thenReturn(response);

        mvc.perform(get("/v1/imobiliarias/" + ID_50))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("GET /v1/imobiliarias/{id} denies CLIENTE")
    void getById_clienteUser_returnsForbidden() throws Exception {
        mvc.perform(get("/v1/imobiliarias/" + ID_100))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/imobiliarias with invalid data returns 422 with field errors")
    void create_invalidData_returnsBadRequest() throws Exception {
        // Falha em @Valid no corpo: passa pelo ApiExceptionHandler.validation(), que mapeia
        // MethodArgumentNotValidException para 422 com a lista de erros por campo (nao 400,
        // que fica reservado para IllegalArgumentException levantada em regra de negocio).
        var request = new CreateImobiliariaRequest("", null, null, null, null, null);

        mvc.perform(post("/v1/imobiliarias")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/imobiliarias returns all agencies for ADMIN")
    void list_adminUser_returnsAll() throws Exception {
        var a = new ImobiliariaResponse(ID_1, "Agency A", null, "11.111.111/0001-11", true);
        var b = new ImobiliariaResponse(ID_2, "Agency B", null, "22.222.222/0001-22", true);
        when(service.list(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(a, b)));

        mvc.perform(get("/v1/imobiliarias").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    @WithMockUser(roles = "IMOB_GERENCIA")
    @DisplayName("GET /v1/imobiliarias returns only own agency for IMOB_GERENCIA (tenant-scoped)")
    void list_imobGerenciaUser_returnsOnlyOwn() throws Exception {
        var own = new ImobiliariaResponse(ID_5, "My Agency", null, "33.333.333/0001-33", true);
        when(service.list(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(own)));

        mvc.perform(get("/v1/imobiliarias").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(ID_5.toString()));
    }

    @Test
    @WithMockUser(roles = "VISTORIADOR")
    @DisplayName("GET /v1/imobiliarias denies wrong role (VISTORIADOR)")
    void list_wrongRole_returnsForbidden() throws Exception {
        mvc.perform(get("/v1/imobiliarias").param("page", "0").param("size", "20"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /v1/imobiliarias requires authentication")
    void list_unauthenticated_returnsUnauthorized() throws Exception {
        mvc.perform(get("/v1/imobiliarias").param("page", "0").param("size", "20"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /v1/imobiliarias requires authentication")
    void create_unauthenticated_returnsUnauthorized() throws Exception {
        var request = new CreateImobiliariaRequest("New Agency", "new@example.com", "33.444.555/0001-66", null, null, "11999999999");

        mvc.perform(post("/v1/imobiliarias")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("PUT /v1/imobiliarias/{id}/pdf-template allows SUPORTE")
    void definirPdfTemplate_suporte_ok() throws Exception {
        when(service.definirPdfTemplate(any(), any()))
                .thenReturn(new ImobiliariaResponse(ID_7, "Agency", null, "11.111.111/0001-11", true));

        mvc.perform(put("/v1/imobiliarias/" + ID_7 + "/pdf-template")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pdfTemplateId\":\"" + TEMPLATE_3 + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "IMOB_GERENCIA")
    @DisplayName("PUT /v1/imobiliarias/{id}/pdf-template denies IMOB_GERENCIA")
    void definirPdfTemplate_wrongRole_returnsForbidden() throws Exception {
        mvc.perform(put("/v1/imobiliarias/" + ID_7 + "/pdf-template")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pdfTemplateId\":\"" + TEMPLATE_3 + "\"}"))
                .andExpect(status().isForbidden());
    }

    private static final UUID CIDADE_20 = UUID.fromString("00000000-0000-0000-0000-000000000020");

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/imobiliarias devolve o endereço próprio da imobiliária")
    void create_comEndereco_devolveCamposDeEndereco() throws Exception {
        var request = new CreateImobiliariaRequest("Acme Imóveis", "Acme", "11.222.333/0001-81", null,
                "fin@acme.com", "11987654321", "01310-100", "Av. Paulista", "1000", "conj 51", CIDADE_20, null);
        var response = new ImobiliariaResponse(ID_1, "Acme Imóveis", "Acme", "11.222.333/0001-81", true,
                null, "fin@acme.com", "11987654321", "01310-100", "Av. Paulista", "1000", "conj 51", CIDADE_20, null);

        when(service.create(any(CreateImobiliariaRequest.class))).thenReturn(response);

        mvc.perform(post("/v1/imobiliarias")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cep").value("01310-100"))
                .andExpect(jsonPath("$.endereco").value("Av. Paulista"))
                .andExpect(jsonPath("$.numero").value("1000"))
                .andExpect(jsonPath("$.complemento").value("conj 51"))
                .andExpect(jsonPath("$.cidadeId").value(CIDADE_20.toString()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/imobiliarias/{id} devolve o endereço próprio da imobiliária")
    void getById_devolveCamposDeEndereco() throws Exception {
        var response = new ImobiliariaResponse(ID_100, "Example Agency", "Example", "11.222.333/0001-44", true,
                null, "ex@example.com", "1130000000", "04567-000", "Rua Verbo Divino", "1488", null, CIDADE_20, null);

        when(service.findById(ID_100)).thenReturn(response);
        when(contratoResumo.resumoOuNull(ID_100)).thenReturn(null);

        mvc.perform(get("/v1/imobiliarias/" + ID_100))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cep").value("04567-000"))
                .andExpect(jsonPath("$.endereco").value("Rua Verbo Divino"))
                .andExpect(jsonPath("$.numero").value("1488"))
                .andExpect(jsonPath("$.cidadeId").value(CIDADE_20.toString()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /v1/imobiliarias/{id} repassa o endereço ao service e devolve os campos")
    void patch_comEndereco_repassaAoService() throws Exception {
        var response = new ImobiliariaResponse(ID_7, "Agency", "Agency", "11.111.111/0001-11", true,
                null, null, null, "20040-002", "Rua da Assembleia", "10", "sala 2", CIDADE_20, null);
        when(service.update(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(response);

        mvc.perform(patch("/v1/imobiliarias/" + ID_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cep\":\"20040-002\",\"endereco\":\"Rua da Assembleia\",\"numero\":\"10\","
                        + "\"complemento\":\"sala 2\",\"cidadeId\":\"" + CIDADE_20 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cep").value("20040-002"))
                .andExpect(jsonPath("$.endereco").value("Rua da Assembleia"))
                .andExpect(jsonPath("$.cidadeId").value(CIDADE_20.toString()));

        org.mockito.ArgumentCaptor<UUID> cidadeCap = org.mockito.ArgumentCaptor.forClass(UUID.class);
        org.mockito.Mockito.verify(service).update(org.mockito.ArgumentMatchers.eq(ID_7),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("20040-002"), org.mockito.ArgumentMatchers.eq("Rua da Assembleia"),
                org.mockito.ArgumentMatchers.eq("10"), org.mockito.ArgumentMatchers.eq("sala 2"),
                cidadeCap.capture(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull());
        org.junit.jupiter.api.Assertions.assertEquals(CIDADE_20, cidadeCap.getValue());
    }
}
