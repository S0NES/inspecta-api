package br.com.vistoria.core.config;

import br.com.vistoria.core.shared.idempotency.IdempotencyService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyFilter")
class IdempotencyFilterTest {

    @Mock private IdempotencyService idempotency;
    @Mock private FilterChain chain;

    private IdempotencyFilter filter() {
        return new IdempotencyFilter(idempotency);
    }

    @Test
    @DisplayName("POST /v1/solicitacoes sem Idempotency-Key -> 400 e nao segue a cadeia")
    void postSolicitacoes_semChave_400() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/solicitacoes");
        req.setContent("{}".getBytes());
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter().doFilterInternal(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(res.getContentAsString()).contains("Idempotency-Key obrigatorio");
        verify(chain, never()).doFilter(any(), any());
        verify(idempotency, never()).lookup(any(), any());
    }

    @Test
    @DisplayName("POST /v1/solicitacoes/{id}/pagamento sem chave segue (nao e criacao)")
    void postPagamento_semChave_passa() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST",
                "/v1/solicitacoes/00000000-0000-0000-0000-000000000001/pagamento");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter().doFilterInternal(req, res, chain);

        verify(chain).doFilter(req, res);
        verify(idempotency, never()).lookup(any(), any());
    }

    @Test
    @DisplayName("GET /v1/solicitacoes sem chave segue")
    void getSolicitacoes_semChave_passa() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/solicitacoes");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter().doFilterInternal(req, res, chain);

        verify(chain).doFilter(req, res);
    }
}
