package br.com.vistoria.core.modules.notify;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import br.com.vistoria.core.modules.usuario.Usuario;
import br.com.vistoria.core.modules.usuario.UsuarioRepository;
import br.com.vistoria.core.shared.outbox.OutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NotifyTemplateController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("NotifyTemplateController")
class NotifyTemplateControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;

    @MockitoBean private JwtAuthFilter jwtAuthFilter;
    @MockitoBean private ApiKeyFilter apiKeyFilter;
    @MockitoBean private NotifyTemplateService service;
    @MockitoBean private UsuarioRepository usuarios;
    @MockitoBean private OutboxService outbox;

    private static MessageTemplate template(UUID uuid) {
        MessageTemplate t = new MessageTemplate();
        t.setUuid(uuid);
        t.setVersao(1);
        t.setCanal("email");
        t.setNome("Boas-vindas");
        t.setCorpo("Ola {{nome}}");
        t.setAtualizadoEm(Instant.now());
        return t;
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("GET lista templates para SUPORTE")
    void list_suporte() throws Exception {
        when(service.listar(null)).thenReturn(List.of(template(UUID.randomUUID())));

        mvc.perform(get("/v1/notify/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nome").value("Boas-vindas"))
                .andExpect(jsonPath("$[0].canal").value("email"));
    }

    @Test
    @WithMockUser(roles = "IMOB_GERENCIA")
    @DisplayName("GET nega acesso a perfil sem permissão")
    void list_forbidden() throws Exception {
        mvc.perform(get("/v1/notify/templates")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST cria um template novo")
    void criar() throws Exception {
        UUID uuid = UUID.randomUUID();
        when(service.criar(eq("email"), eq("Boas-vindas"), any(), eq("Ola {{nome}}")))
                .thenReturn(template(uuid));

        mvc.perform(post("/v1/notify/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "canal", "email", "nome", "Boas-vindas", "corpo", "Ola {{nome}}"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uuid").value(uuid.toString()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /{uuid}/enviar enfileira um evento idempotente por e-mail (via usuarioIds)")
    void enviar_enfileiraPorUsuario() throws Exception {
        UUID uuid = UUID.randomUUID();
        when(service.porUuid(uuid)).thenReturn(template(uuid));

        UUID usuario1 = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID usuario2 = UUID.fromString("00000000-0000-0000-0000-000000000020");
        Usuario u1 = new Usuario(); u1.setId(usuario1); u1.setNome("Ana"); u1.setEmail("Ana@x.com");
        Usuario u2 = new Usuario(); u2.setId(usuario2); u2.setNome("Bea"); u2.setEmail("bea@x.com");
        when(usuarios.findAllById(List.of(usuario1, usuario2))).thenReturn(List.of(u1, u2));

        mvc.perform(post("/v1/notify/templates/" + uuid + "/enviar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("usuarioIds", List.of(usuario1, usuario2)))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.enfileirados").value(2));

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(outbox, org.mockito.Mockito.times(2)).publish(eq("notify.requested.bulk"), body.capture());
        assertThat(body.getAllValues())
                .anySatisfy(m -> assertThat(m).containsEntry("eventId", "bulk-" + uuid + "-ana@x.com")
                        .containsEntry("destinatario", "ana@x.com")
                        .containsEntry("templateId", uuid.toString())
                        .containsEntry("tipoComunicacao", "campanha"));
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("POST /{uuid}/enviar aceita uma lista de e-mails individuais e deduplica")
    void enviar_aceitaListaDeEmails() throws Exception {
        UUID uuid = UUID.randomUUID();
        when(service.porUuid(uuid)).thenReturn(template(uuid));

        mvc.perform(post("/v1/notify/templates/" + uuid + "/enviar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "emails", List.of("  Alvo@Empresa.com ", "alvo@empresa.com", "invalido", "outro@x.com")))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.enfileirados").value(2));

        verify(outbox, org.mockito.Mockito.times(2)).publish(eq("notify.requested.bulk"), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /{uuid}/enviar sem destinatário válido é 400 e não publica nada")
    void enviar_semDestinatario() throws Exception {
        UUID uuid = UUID.randomUUID();
        when(service.porUuid(uuid)).thenReturn(template(uuid));

        mvc.perform(post("/v1/notify/templates/" + uuid + "/enviar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("emails", List.of("sem-arroba")))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(outbox);
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("DELETE desativa o template")
    void desativar() throws Exception {
        UUID uuid = UUID.randomUUID();
        mvc.perform(delete("/v1/notify/templates/" + uuid)).andExpect(status().isNoContent());
        verify(service).desativar(uuid);
    }

    @Test
    @DisplayName("sem autenticação a criação não publica nada")
    void criar_unauthenticated() throws Exception {
        mvc.perform(post("/v1/notify/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(outbox);
    }
}
