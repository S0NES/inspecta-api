package br.com.vistoria.core.modules.solicitacao;

import br.com.vistoria.core.auth.ApiKeyFilter;
import br.com.vistoria.core.auth.JwtAuthFilter;
import br.com.vistoria.core.config.TestMethodSecurityConfig;
import br.com.vistoria.core.modules.contrato.PrecoService;
import br.com.vistoria.core.shared.exception.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PrecoPreviewController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMethodSecurityConfig.class)
@DisplayName("PrecoPreviewController - biletagem preview (V3)")
class PrecoPreviewControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;
    @MockitoBean
    private ApiKeyFilter apiKeyFilter;
    @MockitoBean
    private PrecoService precoService;

    private static final UUID IMOB_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID IMOB_9 = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final UUID TIPO_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID FAIXA_5 = UUID.fromString("00000000-0000-0000-0000-000000000005");

    @Test
    @WithMockUser(roles = "IMOB_GERENCIA")
    @DisplayName("GET preco-preview happy path -> 200 {valor,repasse,faixaId}")
    void preview_ok() throws Exception {
        when(precoService.resolver(eq(IMOB_1), eq(TIPO_2), any(), anyBoolean(), any()))
                .thenReturn(new PrecoService.Preco(new BigDecimal("120.00"), new BigDecimal("80.00"), FAIXA_5));

        mvc.perform(get("/v1/solicitacoes/preco-preview")
                        .param("imobiliariaId", IMOB_1.toString())
                        .param("tipoServicoId", TIPO_2.toString())
                        .param("metragem", "75")
                        .param("mobiliado", "false")
                        .param("valorAluguel", "2000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valor").value(120.00))
                .andExpect(jsonPath("$.repasse").value(80.00))
                .andExpect(jsonPath("$.faixaId").value(FAIXA_5.toString()));
    }

    @Test
    @WithMockUser(roles = "SUPORTE")
    @DisplayName("GET preco-preview when contract not configured -> 422 {codigo,mensagem}")
    void preview_notConfigured_422() throws Exception {
        when(precoService.resolver(any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new NotFoundException("Contrato ativo nao encontrado para imobiliaria 9"));

        mvc.perform(get("/v1/solicitacoes/preco-preview")
                        .param("imobiliariaId", IMOB_9.toString())
                        .param("tipoServicoId", TIPO_2.toString())
                        .param("metragem", "50"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.codigo").value("CONTRATO_NAO_CONFIGURADO"));
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("GET preco-preview sem imobiliariaId -> 400 (parâmetro obrigatório; não existe mais 'avulsa')")
    void preview_semImobiliaria_400() throws Exception {
        mvc.perform(get("/v1/solicitacoes/preco-preview")
                        .param("tipoServicoId", TIPO_2.toString())
                        .param("metragem", "50"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "VISTORIADOR")
    @DisplayName("GET preco-preview denies VISTORIADOR")
    void preview_vistoriador_forbidden() throws Exception {
        mvc.perform(get("/v1/solicitacoes/preco-preview")
                        .param("imobiliariaId", IMOB_1.toString())
                        .param("tipoServicoId", TIPO_2.toString()))
                .andExpect(status().isForbidden());
    }
}
