package br.com.vistoria.core.modules.pdftemplate;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PdfTemplateController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("PdfTemplateController")
class PdfTemplateControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;

    @MockitoBean private JwtAuthFilter jwtAuthFilter;
    @MockitoBean private ApiKeyFilter apiKeyFilter;
    @MockitoBean private PdfTemplateService service;

    private static PdfTemplate template() {
        PdfTemplate t = new PdfTemplate();
        t.setId(UUID.randomUUID());
        t.setChave("laudo");
        t.setNome("Laudo padrão");
        t.setHtml("<html></html>");
        t.setVersao(1);
        t.setAtualizadoEm(Instant.now());
        return t;
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("GET lista templates ativos")
    void listar() throws Exception {
        when(service.listarAtivos()).thenReturn(List.of(template()));

        mvc.perform(get("/v1/pdf-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].chave").value("laudo"))
                .andExpect(jsonPath("$[0].id").isNotEmpty());
    }

    @Test
    @WithMockUser(roles = "IMOB_OPERACAO")
    @DisplayName("GET nega acesso a perfil sem permissão")
    void listar_forbidden() throws Exception {
        mvc.perform(get("/v1/pdf-templates")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST cria uma nova versão")
    void salvar() throws Exception {
        when(service.salvar(any(PdfTemplate.class))).thenReturn(template());

        mvc.perform(post("/v1/pdf-templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "chave", "laudo", "nome", "Laudo padrão", "html", "<html></html>"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versao").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST com template inválido retorna 422 com a lista de erros")
    void salvar_invalido_422() throws Exception {
        when(service.salvar(any(PdfTemplate.class)))
                .thenThrow(new TemplateInvalidoException(List.of(
                        "campo obrigatorio ausente: nome", "sintaxe FreeMarker invalida")));

        mvc.perform(post("/v1/pdf-templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("chave", "laudo", "html", "<#if>"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("DELETE desativa a versão")
    void desativar() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000009");
        mvc.perform(delete("/v1/pdf-templates/" + id)).andExpect(status().isNoContent());
        verify(service).desativar(id);
    }
}
