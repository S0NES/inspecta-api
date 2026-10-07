package br.com.vistoria.core.modules.integracao;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component("mock")
@Slf4j
public class PlenoClientMock implements PlenoClient {

    @Override
    public Resultado pessoa(String plenoId) { return mock("pessoa", plenoId); }

    @Override
    public Resultado imovel(String plenoId) { return mock("imovel", plenoId); }

    @Override
    public Resultado solicitacao(String plenoId) { return mock("solicitacao", plenoId); }

    private Resultado mock(String tipo, String id) {
        log.debug("[pleno-mock] {} {}", tipo, id);
        String json = "{\"id\":\"" + id + "\",\"tipo\":\"" + tipo + "\",\"mock\":true}";
        return new Resultado(tipo, id, json, "mock");
    }
}
