package br.com.vistoria.core.modules.cadastro;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.*;
import br.com.vistoria.core.shared.exception.NotFoundException;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CadastroController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("CadastroController - Reference data management")
class CadastroControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private ApiKeyFilter apiKeyFilter;

    @MockitoBean
    private CidadeRepository cidades;

    @MockitoBean
    private ImovelRepository imoveis;

    @MockitoBean
    private ClienteRepository clientes;

    @MockitoBean
    private IntervenientePapelRepository intervenientePapeis;

    @MockitoBean
    private TipoServicoRepository tiposServico;

    @MockitoBean
    private CadastroService cadastroService;

    @Autowired
    private ObjectMapper mapper;

    private static final UUID ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ID_999 = UUID.fromString("00000000-0000-0000-0000-000000000999");

    @Test
    @DisplayName("GET /v1/cidades lists cities without auth (public)")
    void listCidades_publicEndpoint_returnsList() throws Exception {
        var cidade = new Cidade();
        cidade.setId(ID_1);
        cidade.setNome("São Paulo");

        var page = new PageImpl<>(List.of(cidade), PageRequest.of(0, 20), 1);
        when(cidades.findAll(any(Pageable.class))).thenReturn(page);

        mvc.perform(get("/v1/cidades?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].nome").value("São Paulo"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/cidades creates city for ADMIN")
    void createCidade_adminUser_createsCity() throws Exception {
        var cidade = new Cidade();
        cidade.setId(ID_1);
        cidade.setNome("Rio de Janeiro");

        when(cidades.save(any(Cidade.class))).thenReturn(cidade);

        mvc.perform(post("/v1/cidades")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(cidade)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ID_1.toString()))
                .andExpect(jsonPath("$.nome").value("Rio de Janeiro"));
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("POST /v1/cidades denies non-ADMIN users")
    void createCidade_nonAdminUser_returnsForbidden() throws Exception {
        var cidade = new Cidade();
        cidade.setNome("Brasília");

        mvc.perform(post("/v1/cidades")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(cidade)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/imoveis lists properties for authorized roles")
    void listImoveis_adminUser_returnsList() throws Exception {
        var imovel = new Imovel();
        imovel.setId(ID_1);
        imovel.setEndereco("Rua A, 123");

        var page = new PageImpl<>(List.of(imovel), PageRequest.of(0, 20), 1);
        when(imoveis.findAll(any(Pageable.class))).thenReturn(page);

        mvc.perform(get("/v1/imoveis?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].endereco").value("Rua A, 123"));
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("GET /v1/imoveis denies CLIENTE role")
    void listImoveis_clienteUser_returnsForbidden() throws Exception {
        mvc.perform(get("/v1/imoveis?page=0&size=20"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /v1/imoveis/{id} returns property without auth (public)")
    void getImovel_publicEndpoint_returnsProperty() throws Exception {
        var imovel = new Imovel();
        imovel.setId(ID_1);
        imovel.setEndereco("Rua B, 456");

        when(imoveis.findById(ID_1)).thenReturn(Optional.of(imovel));

        mvc.perform(get("/v1/imoveis/" + ID_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endereco").value("Rua B, 456"));
    }

    @Test
    @DisplayName("GET /v1/imoveis/{id} returns 404 when not found")
    void getImovel_nonexistentId_returns404() throws Exception {
        when(imoveis.findById(ID_999)).thenReturn(Optional.empty());

        mvc.perform(get("/v1/imoveis/" + ID_999))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "IMOB_OPERACAO")
    @DisplayName("POST /v1/imoveis round-trips numero/complemento/cib/observacoes")
    void createImovel_withEnderecoDetalhado_roundTripsNewFields() throws Exception {
        var saved = new Imovel();
        saved.setId(ID_1);
        saved.setEndereco("Rua das Flores");
        saved.setNumero("123");
        saved.setComplemento("Apto 45");
        saved.setCib("CIB-000999");
        saved.setObservacoes("Portão dos fundos emperra.");

        when(cadastroService.criarImovel(any(Imovel.class))).thenReturn(saved);

        mvc.perform(post("/v1/imoveis")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "endereco": "Rua das Flores",
                          "numero": "123",
                          "complemento": "Apto 45",
                          "cib": "CIB-000999",
                          "observacoes": "Portão dos fundos emperra.",
                          "metragem": 85.5
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numero").value("123"))
                .andExpect(jsonPath("$.complemento").value("Apto 45"))
                .andExpect(jsonPath("$.cib").value("CIB-000999"))
                .andExpect(jsonPath("$.observacoes").value("Portão dos fundos emperra."));

        var captor = org.mockito.ArgumentCaptor.forClass(Imovel.class);
        verify(cadastroService).criarImovel(captor.capture());
        assertThat(captor.getValue().getNumero()).isEqualTo("123");
        assertThat(captor.getValue().getComplemento()).isEqualTo("Apto 45");
        assertThat(captor.getValue().getCib()).isEqualTo("CIB-000999");
        assertThat(captor.getValue().getObservacoes()).isEqualTo("Portão dos fundos emperra.");
    }

    @Test
    @WithMockUser(roles = "IMOB_OPERACAO")
    @DisplayName("POST /v1/imoveis without numero/complemento/cib/observacoes still succeeds (optional fields)")
    void createImovel_withoutEnderecoDetalhado_stillSucceeds() throws Exception {
        var saved = new Imovel();
        saved.setId(ID_1);
        saved.setEndereco("Rua Rural s/n");

        when(cadastroService.criarImovel(any(Imovel.class))).thenReturn(saved);

        mvc.perform(post("/v1/imoveis")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "endereco": "Rua Rural s/n",
                          "metragem": 200.0
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numero").doesNotExist())
                .andExpect(jsonPath("$.complemento").doesNotExist())
                .andExpect(jsonPath("$.cib").doesNotExist())
                .andExpect(jsonPath("$.observacoes").doesNotExist());

        var captor = org.mockito.ArgumentCaptor.forClass(Imovel.class);
        verify(cadastroService).criarImovel(captor.capture());
        assertThat(captor.getValue().getNumero()).isNull();
        assertThat(captor.getValue().getComplemento()).isNull();
        assertThat(captor.getValue().getCib()).isNull();
        assertThat(captor.getValue().getObservacoes()).isNull();
    }

    @Test
    @WithMockUser(roles = "IMOB_OPERACAO")
    @DisplayName("POST /v1/imoveis round-trips quartos e cobertura")
    void createImovel_withQuartosECobertura_roundTrips() throws Exception {
        var saved = new Imovel();
        saved.setId(ID_1);
        saved.setEndereco("Rua das Flores");
        saved.setQuartos(3);
        saved.setCobertura(true);

        when(cadastroService.criarImovel(any(Imovel.class))).thenReturn(saved);

        mvc.perform(post("/v1/imoveis")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "endereco": "Rua das Flores",
                          "metragem": 85.5,
                          "quartos": 3,
                          "cobertura": true
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quartos").value(3))
                .andExpect(jsonPath("$.cobertura").value(true));

        var captor = org.mockito.ArgumentCaptor.forClass(Imovel.class);
        verify(cadastroService).criarImovel(captor.capture());
        assertThat(captor.getValue().getQuartos()).isEqualTo(3);
        assertThat(captor.getValue().isCobertura()).isTrue();
    }

    @Test
    @WithMockUser(roles = "IMOB_OPERACAO")
    @DisplayName("PATCH /v1/imoveis/{id} updates quartos e cobertura")
    void patchImovel_updatesQuartosECobertura() throws Exception {
        var existing = new Imovel();
        existing.setId(ID_1);
        existing.setEndereco("Rua Antiga");
        existing.setQuartos(1);
        existing.setCobertura(false);

        when(imoveis.findById(ID_1)).thenReturn(Optional.of(existing));
        when(imoveis.save(any(Imovel.class))).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(patch("/v1/imoveis/" + ID_1)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "quartos": 4,
                          "cobertura": true
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quartos").value(4))
                .andExpect(jsonPath("$.cobertura").value(true));
    }

    @Test
    @WithMockUser(roles = "IMOB_OPERACAO")
    @DisplayName("PATCH /v1/imoveis/{id} updates numero/complemento/cib/observacoes")
    void patchImovel_updatesEnderecoDetalhado() throws Exception {
        var existing = new Imovel();
        existing.setId(ID_1);
        existing.setEndereco("Rua Antiga");

        when(imoveis.findById(ID_1)).thenReturn(Optional.of(existing));
        when(imoveis.save(any(Imovel.class))).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(patch("/v1/imoveis/" + ID_1)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "numero": "77",
                          "complemento": "Fundos",
                          "cib": "CIB-777",
                          "observacoes": "Cão bravo no quintal."
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numero").value("77"))
                .andExpect(jsonPath("$.complemento").value("Fundos"))
                .andExpect(jsonPath("$.cib").value("CIB-777"))
                .andExpect(jsonPath("$.observacoes").value("Cão bravo no quintal."));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /v1/clientes creates client for ADMIN")
    void createCliente_adminUser_createsClient() throws Exception {
        var cliente = new Cliente();
        cliente.setId(ID_1);
        cliente.setNome("Acme Corp");

        when(clientes.save(any(Cliente.class))).thenReturn(cliente);

        mvc.perform(post("/v1/clientes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(cliente)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nome").value("Acme Corp"));
    }

    @Test
    @DisplayName("GET /v1/interveniente-papeis lista a lookup ativa (endpoint público de leitura)")
    void listIntervenientePapeis() throws Exception {
        var p = new IntervenientePapel();
        p.setSlug("locador");
        p.setNome("Locador");
        when(intervenientePapeis.findByAtivoTrueOrderBySlug()).thenReturn(List.of(p));

        mvc.perform(get("/v1/interveniente-papeis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("locador"));
    }

    @Test
    @DisplayName("GET /v1/tipos-servico lists service types without auth (public)")
    void listTipos_publicEndpoint_returnsList() throws Exception {
        var tipo = new TipoServico();
        tipo.setId(ID_1);
        tipo.setNome("Vistoria de entrada");

        var page = new PageImpl<>(List.of(tipo), PageRequest.of(0, 20), 1);
        when(tiposServico.findAll(any(Pageable.class))).thenReturn(page);

        mvc.perform(get("/v1/tipos-servico?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].nome").value("Vistoria de entrada"));
    }
}
