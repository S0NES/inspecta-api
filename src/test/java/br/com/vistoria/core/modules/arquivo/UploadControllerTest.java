package br.com.vistoria.core.modules.arquivo;

import br.com.vistoria.core.AbstractIntegrationTest;
import br.com.vistoria.core.config.TenantContext;
import br.com.vistoria.core.modules.arquivo.ArquivoRepository;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.ImovelRepository;
import br.com.vistoria.core.modules.cadastro.Imovel;
import br.com.vistoria.core.modules.laudo.Laudo;
import br.com.vistoria.core.modules.laudo.LaudoRepository;
import br.com.vistoria.core.modules.solicitacao.Solicitacao;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoServico;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.SolicitacaoRepository;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.ServicoRepository;
import br.com.vistoria.core.modules.usuario.Usuario;
import br.com.vistoria.core.modules.usuario.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("UploadController - Direct upload to Postgres (T1.6 BOLA Fix)")
class UploadControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UsuarioRepository usuarios;

    @Autowired
    private ImovelRepository imoveis;

    @Autowired
    private LaudoRepository laudos;

    @Autowired
    private SolicitacaoRepository solicitacoes;

    @Autowired
    private ServicoRepository servicos;

    @Autowired
    private ArquivoRepository arquivos;

    private static final java.util.UUID TENANT_1 = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final java.util.UUID TENANT_OTHER = java.util.UUID.fromString("00000000-0000-0000-0000-000000000999");

    private Usuario testUser;
    private Imovel testImovel;

    @BeforeEach
    void setUp() {
        testUser = new Usuario();
        testUser.setEmail("uploader@example.com");
        testUser.setNome("Test Uploader");
        testUser.setPerfil(Usuario.Perfil.admin);
        testUser.setStatus(Usuario.Status.ativo);
        testUser.setSenhaHash("hash");
        testUser.setImobiliariaId(TENANT_1);
        usuarios.save(testUser);

        testImovel = new Imovel();
        testImovel.setImobiliariaId(TENANT_1);
        testImovel.setEndereco("Test Address");
        imoveis.save(testImovel);

        TenantContext.set(TENANT_1);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(testUser.getId(), null, java.util.List.of()));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("upload with valid imovel owner stores file and returns arquivo ID")
    void upload_validImovelOwner_storesFileAndReturnsId() throws Exception {
        byte[] fileContent = "test image content".getBytes();

        mvc.perform(multipart("/v1/uploads")
                .file("file", fileContent)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .param("ownerType", "imovel")
                .param("ownerId", testImovel.getId().toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.arquivoId").isString());
    }

    @Test
    @DisplayName("upload rejects unsupported MIME type (e.g., text/plain)")
    void upload_unsupportedMime_returnsBadRequest() throws Exception {
        byte[] fileContent = "test content".getBytes();

        mvc.perform(multipart("/v1/uploads")
                .file("file", fileContent)
                .contentType(MediaType.TEXT_PLAIN)
                .param("ownerType", "imovel")
                .param("ownerId", testImovel.getId().toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("upload rejects unknown ownerType (T1.6 allowlist)")
    void upload_unknownOwnerType_returnsBadRequest() throws Exception {
        byte[] fileContent = "test image content".getBytes();

        mvc.perform(multipart("/v1/uploads")
                .file("file", fileContent)
                .contentType(MediaType.IMAGE_PNG)
                .param("ownerType", "invalid-type")
                .param("ownerId", testImovel.getId().toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("upload rejects cross-tenant imovel (BOLA fix)")
    void upload_crossTenantImovel_returnsForbidden() throws Exception {
        // Create imovel in different tenant
        Imovel otherTenantImovel = new Imovel();
        otherTenantImovel.setImobiliariaId(TENANT_OTHER);
        otherTenantImovel.setEndereco("Other Tenant Address");
        imoveis.save(otherTenantImovel);

        byte[] fileContent = "test image content".getBytes();

        mvc.perform(multipart("/v1/uploads")
                .file("file", fileContent)
                .contentType(MediaType.IMAGE_PNG)
                .param("ownerType", "imovel")
                .param("ownerId", otherTenantImovel.getId().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("upload allows same-tenant laudo (T1.6 ownership check)")
    void upload_sameTenantLaudo_succeeds() throws Exception {
        // Create solicitacao -> servico -> laudo chain
        Solicitacao s = new Solicitacao();
        s.setImobiliariaId(TENANT_1);
        solicitacoes.save(s);

        SolicitacaoServico ss = new SolicitacaoServico();
        ss.setSolicitacaoId(s.getId());
        servicos.save(ss);

        Laudo laudo = new Laudo();
        laudo.setSolicitacaoServicoId(ss.getId());
        laudo.setVersao(1);
        laudos.save(laudo);

        byte[] fileContent = "test pdf content".getBytes();

        mvc.perform(multipart("/v1/uploads")
                .file("file", fileContent)
                .contentType(MediaType.APPLICATION_PDF)
                .param("ownerType", "laudo")
                .param("ownerId", laudo.getId().toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.arquivoId").isString());
    }

    @Test
    @DisplayName("upload rejects cross-tenant laudo (BOLA fix)")
    void upload_crossTenantLaudo_returnsForbidden() throws Exception {
        // Create solicitacao in different tenant
        Solicitacao s = new Solicitacao();
        s.setImobiliariaId(TENANT_OTHER);
        solicitacoes.save(s);

        SolicitacaoServico ss = new SolicitacaoServico();
        ss.setSolicitacaoId(s.getId());
        servicos.save(ss);

        Laudo laudo = new Laudo();
        laudo.setSolicitacaoServicoId(ss.getId());
        laudo.setVersao(1);
        laudos.save(laudo);

        byte[] fileContent = "test pdf content".getBytes();

        mvc.perform(multipart("/v1/uploads")
                .file("file", fileContent)
                .contentType(MediaType.APPLICATION_PDF)
                .param("ownerType", "laudo")
                .param("ownerId", laudo.getId().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("upload allows same-tenant solicitacao owner (inspection field photos)")
    void upload_sameTenantSolicitacao_succeeds() throws Exception {
        Solicitacao s = new Solicitacao();
        s.setImobiliariaId(TENANT_1);
        solicitacoes.save(s);

        byte[] fileContent = "test image content".getBytes();

        mvc.perform(multipart("/v1/uploads")
                .file("file", fileContent)
                .contentType(MediaType.IMAGE_PNG)
                .param("ownerType", "solicitacao")
                .param("ownerId", s.getId().toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.arquivoId").isString());
    }

    @Test
    @DisplayName("upload rejects cross-tenant solicitacao (BOLA fix)")
    void upload_crossTenantSolicitacao_returnsForbidden() throws Exception {
        Solicitacao s = new Solicitacao();
        s.setImobiliariaId(TENANT_OTHER);
        solicitacoes.save(s);

        byte[] fileContent = "test image content".getBytes();

        mvc.perform(multipart("/v1/uploads")
                .file("file", fileContent)
                .contentType(MediaType.IMAGE_PNG)
                .param("ownerType", "solicitacao")
                .param("ownerId", s.getId().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /v1/uploads lists images already uploaded for an owner")
    void listar_sameTenantSolicitacao_returnsUploadedImages() throws Exception {
        Solicitacao s = new Solicitacao();
        s.setImobiliariaId(TENANT_1);
        solicitacoes.save(s);

        byte[] fileContent = "test image content".getBytes();
        mvc.perform(multipart("/v1/uploads")
                .file("file", fileContent)
                .contentType(MediaType.IMAGE_PNG)
                .param("ownerType", "solicitacao")
                .param("ownerId", s.getId().toString()))
                .andExpect(status().isCreated());

        mvc.perform(get("/v1/uploads")
                .param("ownerType", "solicitacao")
                .param("ownerId", s.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].mime").value("image/png"))
                .andExpect(jsonPath("$[0].tamanho").value(fileContent.length));
    }

    @Test
    @DisplayName("GET /v1/uploads rejects cross-tenant access (BOLA fix)")
    void listar_crossTenantSolicitacao_returnsForbidden() throws Exception {
        Solicitacao s = new Solicitacao();
        s.setImobiliariaId(TENANT_OTHER);
        solicitacoes.save(s);

        mvc.perform(get("/v1/uploads")
                .param("ownerType", "solicitacao")
                .param("ownerId", s.getId().toString()))
                .andExpect(status().isForbidden());
    }
}
