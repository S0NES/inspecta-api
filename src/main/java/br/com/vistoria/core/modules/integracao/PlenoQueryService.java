package br.com.vistoria.core.modules.integracao;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class PlenoQueryService {

    private final Map<String, PlenoClient> clients;

    @Value("${inspecta.pleno.mode:mock}")
    private String modoAtivo;

    private PlenoClient ativo() { return clients.get(modoAtivo); }

    public PlenoClient.Resultado pessoa(String plenoId) { return ativo().pessoa(plenoId); }
    public PlenoClient.Resultado imovel(String plenoId) { return ativo().imovel(plenoId); }
    public PlenoClient.Resultado solicitacao(String plenoId) { return ativo().solicitacao(plenoId); }
}
