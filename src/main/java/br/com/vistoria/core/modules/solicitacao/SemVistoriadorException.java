package br.com.vistoria.core.modules.solicitacao;

import java.util.List;
import java.util.UUID;

/**
 * V3 (decisão de negócio 4) — não há vistoriador elegível para ao menos um serviço pendente
 * da solicitação na janela desejada. O inspecta-api recusa o início do pagamento com 409 e a lista
 * de {@code tipoServicoId} sem cobertura, para a UI orientar o solicitante.
 */
public class SemVistoriadorException extends RuntimeException {

    private final List<UUID> tiposServicoId;

    public SemVistoriadorException(List<UUID> tiposServicoId) {
        super("Sem vistoriador elegivel para os servicos: " + tiposServicoId);
        this.tiposServicoId = List.copyOf(tiposServicoId);
    }

    public List<UUID> getTiposServicoId() {
        return tiposServicoId;
    }
}
