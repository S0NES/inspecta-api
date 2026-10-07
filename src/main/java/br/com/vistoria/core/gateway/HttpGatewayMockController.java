package br.com.vistoria.core.gateway;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/test/http-gateway")
@Profile("!prod")
@RequiredArgsConstructor
@Tag(name = "Testing - HTTP Gateway Mock", description = "Mock HTTP gateway para testes (apenas em !prod)")
@Hidden
public class HttpGatewayMockController {

    private final MockHttpGateway mockHttpGateway;

    public record StubRequest(int status, String body, Map<String, String> headers) {}

    @PostMapping("/stub-next")
    @Operation(summary = "Programar próxima resposta do gateway")
    public ResponseEntity<Void> stubNext(@RequestBody StubRequest req) {
        mockHttpGateway.programarProxima(new HttpGatewayResponse(req.status(), req.headers(), req.body()));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/reset")
    @Operation(summary = "Limpar mock do gateway")
    public ResponseEntity<Void> reset() {
        mockHttpGateway.limpar();
        return ResponseEntity.noContent().build();
    }
}