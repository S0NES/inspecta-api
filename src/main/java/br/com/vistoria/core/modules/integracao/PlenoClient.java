package br.com.vistoria.core.modules.integracao;

public interface PlenoClient {

    record Resultado(String recursoTipo, String plenoId, String dadosJson, String fonte) {}

    Resultado pessoa(String plenoId);
    Resultado imovel(String plenoId);
    Resultado solicitacao(String plenoId);
}
