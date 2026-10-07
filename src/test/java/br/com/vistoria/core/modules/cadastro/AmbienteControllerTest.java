package br.com.vistoria.core.modules.cadastro;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TenantContext;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import br.com.vistoria.core.modules.arquivo.UploadService;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.ImovelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AmbienteController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("AmbienteController - Ambientes e Fotos de um imóvel")
class AmbienteControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private ApiKeyFilter apiKeyFilter;

    @MockitoBean
    private AmbienteService ambienteService;

    @MockitoBean
    private AmbienteRepository ambientes;

    @MockitoBean
    private FotoRepository fotos;

    @MockitoBean
    private ImovelRepository imoveis;

    @MockitoBean
    private UploadService uploadService;

    private static final UUID IMOVEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID IMOBILIARIA_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID OUTRA_IMOBILIARIA_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final UUID AMBIENTE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID FOTO_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ARQUIVO_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    private Imovel imovel(UUID imobiliariaId) {
        Imovel i = new Imovel();
        i.setId(IMOVEL_ID);
        i.setImobiliariaId(imobiliariaId);
        return i;
    }

    private Ambiente ambiente() {
        Ambiente a = new Ambiente();
        a.setId(AMBIENTE_ID);
        a.setImovelId(IMOVEL_ID);
        a.setNome("Sala");
        return a;
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/imoveis/{id}/ambientes lista ambientes para ADMIN")
    void listarAmbientes_admin_ok() throws Exception {
        when(imoveis.findById(IMOVEL_ID)).thenReturn(Optional.of(imovel(IMOBILIARIA_ID)));
        when(ambienteService.listarPorImovel(IMOVEL_ID)).thenReturn(List.of(ambiente()));

        mvc.perform(get("/v1/imoveis/" + IMOVEL_ID + "/ambientes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nome").value("Sala"));
    }

    @Test
    @WithMockUser(roles = "VISTORIADOR")
    @DisplayName("GET /v1/imoveis/{id}/ambientes nega VISTORIADOR")
    void listarAmbientes_vistoriador_forbidden() throws Exception {
        mvc.perform(get("/v1/imoveis/" + IMOVEL_ID + "/ambientes"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/imoveis/{id}/ambientes de outro tenant retorna 403 (cross-tenant isolation)")
    void listarAmbientes_outroTenant_forbidden() throws Exception {
        when(imoveis.findById(IMOVEL_ID)).thenReturn(Optional.of(imovel(IMOBILIARIA_ID)));
        TenantContext.set(OUTRA_IMOBILIARIA_ID);

        mvc.perform(get("/v1/imoveis/" + IMOVEL_ID + "/ambientes"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/imoveis/{id}/ambientes do proprio tenant (V25 multi-imobiliaria) e permitido")
    void listarAmbientes_tenantEntreOsVinculados_ok() throws Exception {
        when(imoveis.findById(IMOVEL_ID)).thenReturn(Optional.of(imovel(IMOBILIARIA_ID)));
        when(ambienteService.listarPorImovel(IMOVEL_ID)).thenReturn(List.of(ambiente()));
        TenantContext.set(List.of(OUTRA_IMOBILIARIA_ID, IMOBILIARIA_ID));

        mvc.perform(get("/v1/imoveis/" + IMOVEL_ID + "/ambientes"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/imoveis/{id}/ambientes cria ambiente para ADMIN")
    void criarAmbiente_admin_created() throws Exception {
        when(imoveis.findById(IMOVEL_ID)).thenReturn(Optional.of(imovel(IMOBILIARIA_ID)));
        when(ambienteService.criar(any(Ambiente.class))).thenReturn(ambiente());

        mvc.perform(post("/v1/imoveis/" + IMOVEL_ID + "/ambientes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Sala\",\"tipo\":\"sala\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nome").value("Sala"));
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("POST /v1/imoveis/{id}/ambientes nega SUPORTE (somente admin/imob)")
    void criarAmbiente_suporte_forbidden() throws Exception {
        mvc.perform(post("/v1/imoveis/" + IMOVEL_ID + "/ambientes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Sala\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/imoveis/{id}/ambientes de outro tenant retorna 403")
    void criarAmbiente_outroTenant_forbidden() throws Exception {
        when(imoveis.findById(IMOVEL_ID)).thenReturn(Optional.of(imovel(IMOBILIARIA_ID)));
        TenantContext.set(OUTRA_IMOBILIARIA_ID);

        mvc.perform(post("/v1/imoveis/" + IMOVEL_ID + "/ambientes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Sala\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("DELETE /v1/ambientes/{id} remove para ADMIN")
    void removerAmbiente_admin_noContent() throws Exception {
        when(ambientes.findById(AMBIENTE_ID)).thenReturn(Optional.of(ambiente()));
        when(imoveis.findById(IMOVEL_ID)).thenReturn(Optional.of(imovel(IMOBILIARIA_ID)));

        mvc.perform(delete("/v1/ambientes/" + AMBIENTE_ID))
                .andExpect(status().isNoContent());

        verify(ambienteService).remover(AMBIENTE_ID);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("DELETE /v1/ambientes/{id} de outro tenant retorna 403")
    void removerAmbiente_outroTenant_forbidden() throws Exception {
        when(ambientes.findById(AMBIENTE_ID)).thenReturn(Optional.of(ambiente()));
        when(imoveis.findById(IMOVEL_ID)).thenReturn(Optional.of(imovel(IMOBILIARIA_ID)));
        TenantContext.set(OUTRA_IMOBILIARIA_ID);

        mvc.perform(delete("/v1/ambientes/" + AMBIENTE_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("DELETE /v1/ambientes/{id} inexistente retorna 404")
    void removerAmbiente_naoEncontrado_404() throws Exception {
        when(ambientes.findById(AMBIENTE_ID)).thenReturn(Optional.empty());

        mvc.perform(delete("/v1/ambientes/" + AMBIENTE_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/ambientes/{id}/fotos lista fotos para ADMIN")
    void listarFotos_admin_ok() throws Exception {
        when(ambientes.findById(AMBIENTE_ID)).thenReturn(Optional.of(ambiente()));
        when(imoveis.findById(IMOVEL_ID)).thenReturn(Optional.of(imovel(IMOBILIARIA_ID)));
        Foto foto = new Foto();
        foto.setId(FOTO_ID);
        foto.setAmbienteId(AMBIENTE_ID);
        foto.setArquivoId(ARQUIVO_ID);
        when(ambienteService.listarFotos(AMBIENTE_ID)).thenReturn(List.of(foto));

        mvc.perform(get("/v1/ambientes/" + AMBIENTE_ID + "/fotos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].arquivoId").value(ARQUIVO_ID.toString()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/ambientes/{id}/fotos envia o arquivo pelo mesmo pipeline do upload e vincula ao ambiente")
    void adicionarFoto_admin_created() throws Exception {
        when(ambientes.findById(AMBIENTE_ID)).thenReturn(Optional.of(ambiente()));
        when(uploadService.upload(eq("image/png"), any(byte[].class), eq("imovel"), eq(IMOVEL_ID)))
                .thenReturn(new UploadService.UploadResult(ARQUIVO_ID));
        Foto salva = new Foto();
        salva.setId(FOTO_ID);
        salva.setAmbienteId(AMBIENTE_ID);
        salva.setArquivoId(ARQUIVO_ID);
        when(ambienteService.adicionarFoto(any(Foto.class))).thenReturn(salva);

        MockMultipartFile file = new MockMultipartFile("file", "foto.png", "image/png", "conteudo".getBytes());

        mvc.perform(multipart("/v1/ambientes/" + AMBIENTE_ID + "/fotos").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.arquivoId").value(ARQUIVO_ID.toString()));
    }

    @Test
    @DisplayName("POST /v1/ambientes/{id}/fotos requer autenticação")
    void adicionarFoto_unauthenticated_returnsUnauthorized() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "foto.png", "image/png", "conteudo".getBytes());

        mvc.perform(multipart("/v1/ambientes/" + AMBIENTE_ID + "/fotos").file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("DELETE /v1/fotos/{id} remove para ADMIN")
    void removerFoto_admin_noContent() throws Exception {
        Foto foto = new Foto();
        foto.setId(FOTO_ID);
        foto.setAmbienteId(AMBIENTE_ID);
        foto.setArquivoId(ARQUIVO_ID);
        when(fotos.findById(FOTO_ID)).thenReturn(Optional.of(foto));
        when(ambientes.findById(AMBIENTE_ID)).thenReturn(Optional.of(ambiente()));
        when(imoveis.findById(IMOVEL_ID)).thenReturn(Optional.of(imovel(IMOBILIARIA_ID)));

        mvc.perform(delete("/v1/fotos/" + FOTO_ID))
                .andExpect(status().isNoContent());

        verify(ambienteService).removerFoto(FOTO_ID);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("DELETE /v1/fotos/{id} de outro tenant retorna 403")
    void removerFoto_outroTenant_forbidden() throws Exception {
        Foto foto = new Foto();
        foto.setId(FOTO_ID);
        foto.setAmbienteId(AMBIENTE_ID);
        foto.setArquivoId(ARQUIVO_ID);
        when(fotos.findById(FOTO_ID)).thenReturn(Optional.of(foto));
        when(ambientes.findById(AMBIENTE_ID)).thenReturn(Optional.of(ambiente()));
        when(imoveis.findById(IMOVEL_ID)).thenReturn(Optional.of(imovel(IMOBILIARIA_ID)));
        TenantContext.set(OUTRA_IMOBILIARIA_ID);

        mvc.perform(delete("/v1/fotos/" + FOTO_ID))
                .andExpect(status().isForbidden());
    }
}
