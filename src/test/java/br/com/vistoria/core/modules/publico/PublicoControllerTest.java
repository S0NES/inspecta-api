package br.com.vistoria.core.modules.publico;

import br.com.vistoria.core.AbstractIntegrationTest;
import br.com.vistoria.core.auth.TokenHasher;
import br.com.vistoria.core.modules.arquivo.Arquivo;
import br.com.vistoria.core.modules.arquivo.ArquivoRepository;
import br.com.vistoria.core.modules.laudo.Laudo;
import br.com.vistoria.core.modules.laudo.LaudoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("PublicoController - Public Image Gallery Access (T2.10)")
class PublicoControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AcessoPublicoRepository acessos;

    @Autowired
    private ArquivoRepository arquivos;

    @Autowired
    private LaudoRepository laudos;

    private Laudo testLaudo;
    private Arquivo testFile;
    private String validToken;
    private String revokedToken;
    private String expiredToken;

    @BeforeEach
    void setUp() {
        testLaudo = new Laudo();
        testLaudo.setVersao(1);
        laudos.save(testLaudo);

        // recurso_id de um link tipo=imagens e sempre o laudo, nunca o arquivo (T2.10 fix).
        testFile = new Arquivo();
        testFile.setMime("image/png");
        testFile.setTamanho(1024L);
        testFile.setOwnerType("laudo");
        testFile.setOwnerId(testLaudo.getId());
        testFile.setConteudo(new byte[]{1, 2, 3});
        arquivos.save(testFile);

        validToken = TokenHasher.newToken();
        AcessoPublico validAccess = new AcessoPublico();
        validAccess.setTokenHash(TokenHasher.sha256(validToken));
        validAccess.setTipo(AcessoPublico.Tipo.imagens);
        validAccess.setRecursoId(testLaudo.getId());
        validAccess.setExpiraEm(Instant.now().plus(Duration.ofDays(90)));
        validAccess.setRevogado(false);
        acessos.save(validAccess);

        revokedToken = TokenHasher.newToken();
        AcessoPublico revokedAccess = new AcessoPublico();
        revokedAccess.setTokenHash(TokenHasher.sha256(revokedToken));
        revokedAccess.setTipo(AcessoPublico.Tipo.imagens);
        revokedAccess.setRecursoId(testLaudo.getId());
        revokedAccess.setExpiraEm(Instant.now().plus(Duration.ofDays(90)));
        revokedAccess.setRevogado(true);
        acessos.save(revokedAccess);

        expiredToken = TokenHasher.newToken();
        AcessoPublico expiredAccess = new AcessoPublico();
        expiredAccess.setTokenHash(TokenHasher.sha256(expiredToken));
        expiredAccess.setTipo(AcessoPublico.Tipo.imagens);
        expiredAccess.setRecursoId(testLaudo.getId());
        expiredAccess.setExpiraEm(Instant.now().minus(Duration.ofHours(1)));
        expiredAccess.setRevogado(false);
        acessos.save(expiredAccess);
    }

    @Test
    @DisplayName("GET /v1/public/imagens/{token} with valid token returns the laudo's photo list")
    void getImagensPublicas_validToken_returnsList() throws Exception {
        mvc.perform(get("/v1/public/imagens/" + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(testFile.getId()))
                .andExpect(jsonPath("$[0].mime").value("image/png"))
                .andExpect(jsonPath("$[0].tamanho").value(1024));
    }

    @Test
    @DisplayName("GET /v1/public/imagens/{token} with revoked token returns 404")
    void getImagensPublicas_revokedToken_returns404() throws Exception {
        mvc.perform(get("/v1/public/imagens/" + revokedToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /v1/public/imagens/{token} with expired token returns 404")
    void getImagensPublicas_expiredToken_returns404() throws Exception {
        mvc.perform(get("/v1/public/imagens/" + expiredToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /v1/public/imagens/{token} with invalid token returns 404")
    void getImagensPublicas_invalidToken_returns404() throws Exception {
        mvc.perform(get("/v1/public/imagens/invalid-token-that-does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /v1/public/imagens/{token} with wrong token type (laudo) returns 404")
    void getImagensPublicas_wrongTokenType_returns404() throws Exception {
        String laudoToken = TokenHasher.newToken();
        AcessoPublico laudoAccess = new AcessoPublico();
        laudoAccess.setTokenHash(TokenHasher.sha256(laudoToken));
        laudoAccess.setTipo(AcessoPublico.Tipo.laudo);
        laudoAccess.setRecursoId(testLaudo.getId());
        laudoAccess.setExpiraEm(Instant.now().plus(Duration.ofDays(90)));
        laudoAccess.setRevogado(false);
        acessos.save(laudoAccess);

        mvc.perform(get("/v1/public/imagens/" + laudoToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /v1/public/imagens/{token}/pagina returns an HTML gallery listing the byte-serving URLs")
    void galeriaPublica_validToken_returnsHtmlWithImageUrls() throws Exception {
        mvc.perform(get("/v1/public/imagens/" + validToken + "/pagina"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString(
                        "/v1/public/imagens/" + validToken + "/" + testFile.getId())));
    }

    @Test
    @DisplayName("GET /v1/public/imagens/{token}/pagina with revoked token returns 404")
    void galeriaPublica_revokedToken_returns404() throws Exception {
        mvc.perform(get("/v1/public/imagens/" + revokedToken + "/pagina"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /v1/public/imagens/{token}/{arquivoId} streams the bytes with the real Content-Type")
    void conteudoImagemPublica_ownedArquivo_returnsBytes() throws Exception {
        mvc.perform(get("/v1/public/imagens/" + validToken + "/" + testFile.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    @DisplayName("GET /v1/public/imagens/{token}/{arquivoId} for an arquivo of a different laudo returns 404 (IDOR guard)")
    void conteudoImagemPublica_arquivoFromDifferentLaudo_returns404() throws Exception {
        Laudo outroLaudo = new Laudo();
        outroLaudo.setVersao(1);
        laudos.save(outroLaudo);

        Arquivo outraFoto = new Arquivo();
        outraFoto.setMime("image/png");
        outraFoto.setTamanho(10L);
        outraFoto.setOwnerType("laudo");
        outraFoto.setOwnerId(outroLaudo.getId());
        outraFoto.setConteudo(new byte[]{9});
        arquivos.save(outraFoto);

        mvc.perform(get("/v1/public/imagens/" + validToken + "/" + outraFoto.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /v1/public/imagens/{token}/{arquivoId} for the laudo's own PDF (same owner, not an image) returns 404")
    void conteudoImagemPublica_laudoPdf_returns404() throws Exception {
        Arquivo pdf = new Arquivo();
        pdf.setMime("application/pdf");
        pdf.setTamanho(500L);
        pdf.setOwnerType("laudo");
        pdf.setOwnerId(testLaudo.getId());
        pdf.setConteudo(new byte[]{5});
        arquivos.save(pdf);

        mvc.perform(get("/v1/public/imagens/" + validToken + "/" + pdf.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /v1/public/laudos/{token} with valid token returns laudo")
    void getPublicLaudo_validToken_returnsLaudo() throws Exception {
        String laudoToken = TokenHasher.newToken();
        AcessoPublico laudoAccess = new AcessoPublico();
        laudoAccess.setTokenHash(TokenHasher.sha256(laudoToken));
        laudoAccess.setTipo(AcessoPublico.Tipo.laudo);
        laudoAccess.setRecursoId(testLaudo.getId());
        laudoAccess.setExpiraEm(Instant.now().plus(Duration.ofDays(90)));
        laudoAccess.setRevogado(false);
        acessos.save(laudoAccess);

        mvc.perform(get("/v1/public/laudos/" + laudoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString());
    }

    @Test
    @DisplayName("GET /v1/public/laudos/{token} with revoked token returns 404")
    void getPublicLaudo_revokedToken_returns404() throws Exception {
        String laudoToken = TokenHasher.newToken();
        AcessoPublico revokedLaudoAccess = new AcessoPublico();
        revokedLaudoAccess.setTokenHash(TokenHasher.sha256(laudoToken));
        revokedLaudoAccess.setTipo(AcessoPublico.Tipo.laudo);
        revokedLaudoAccess.setRecursoId(UUID.fromString("00000000-0000-0000-0000-000000000999"));
        revokedLaudoAccess.setExpiraEm(Instant.now().plus(Duration.ofDays(90)));
        revokedLaudoAccess.setRevogado(true);
        acessos.save(revokedLaudoAccess);

        mvc.perform(get("/v1/public/laudos/" + laudoToken))
                .andExpect(status().isNotFound());
    }
}
