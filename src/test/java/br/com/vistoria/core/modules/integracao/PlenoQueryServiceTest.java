package br.com.vistoria.core.modules.integracao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlenoQueryService - Pleno integration query router")
class PlenoQueryServiceTest {

    @Mock
    private PlenoClient mockClient;

    @InjectMocks
    private PlenoQueryService service;

    @Test
    @DisplayName("pessoa delegates to active client")
    void pessoa_callsActiveClient() {
        ReflectionTestUtils.setField(service, "modoAtivo", "mock");
        Map<String, PlenoClient> clients = Map.of("mock", mockClient);
        ReflectionTestUtils.setField(service, "clients", clients);

        var resultado = new PlenoClient.Resultado("pessoa", "pleno_123", "{}", "mock");
        when(mockClient.pessoa("pleno_123")).thenReturn(resultado);

        var result = service.pessoa("pleno_123");

        assertThat(result).isNotNull();
        assertThat(result.recursoTipo()).isEqualTo("pessoa");
    }

    @Test
    @DisplayName("imovel delegates to active client")
    void imovel_callsActiveClient() {
        ReflectionTestUtils.setField(service, "modoAtivo", "mock");
        Map<String, PlenoClient> clients = Map.of("mock", mockClient);
        ReflectionTestUtils.setField(service, "clients", clients);

        var resultado = new PlenoClient.Resultado("imovel", "imov_456", "{}", "mock");
        when(mockClient.imovel("imov_456")).thenReturn(resultado);

        var result = service.imovel("imov_456");

        assertThat(result).isNotNull();
        assertThat(result.recursoTipo()).isEqualTo("imovel");
    }

    @Test
    @DisplayName("solicitacao delegates to active client")
    void solicitacao_callsActiveClient() {
        ReflectionTestUtils.setField(service, "modoAtivo", "mock");
        Map<String, PlenoClient> clients = Map.of("mock", mockClient);
        ReflectionTestUtils.setField(service, "clients", clients);

        var resultado = new PlenoClient.Resultado("solicitacao", "sol_789", "{}", "mock");
        when(mockClient.solicitacao("sol_789")).thenReturn(resultado);

        var result = service.solicitacao("sol_789");

        assertThat(result).isNotNull();
        assertThat(result.recursoTipo()).isEqualTo("solicitacao");
    }

    @Test
    @DisplayName("uses configured active mode from property")
    void usesConfiguredMode() {
        var mockHttpClient = new PlenoClient() {
            @Override
            public Resultado pessoa(String plenoId) { return new Resultado("pessoa", plenoId, "{}", "http"); }
            @Override
            public Resultado imovel(String plenoId) { return new Resultado("imovel", plenoId, "{}", "http"); }
            @Override
            public Resultado solicitacao(String plenoId) { return new Resultado("solicitacao", plenoId, "{}", "http"); }
        };

        ReflectionTestUtils.setField(service, "modoAtivo", "http");
        Map<String, PlenoClient> clients = Map.of("http", mockHttpClient);
        ReflectionTestUtils.setField(service, "clients", clients);

        var result = service.pessoa("pleno_123");

        assertThat(result.recursoTipo()).isEqualTo("pessoa");
    }
}
