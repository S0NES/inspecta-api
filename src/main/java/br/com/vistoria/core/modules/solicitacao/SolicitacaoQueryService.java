package br.com.vistoria.core.modules.solicitacao;

import br.com.vistoria.core.modules.cadastro.Cidade;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.CidadeRepository;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.ImovelRepository;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.TipoServicoRepository;
import br.com.vistoria.core.modules.cadastro.Imovel;
import br.com.vistoria.core.modules.cadastro.TipoServico;
import br.com.vistoria.core.modules.disponibilidade.DisponibilidadeRepositories.VistoriadorRepository;
import br.com.vistoria.core.modules.disponibilidade.Vistoriador;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.ServicoRepository;
import br.com.vistoria.core.modules.solicitacao.dto.SolicitacaoListItem;
import br.com.vistoria.core.modules.usuario.Usuario;
import br.com.vistoria.core.modules.usuario.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Monta o read model das telas de listagem ({@link SolicitacaoListItem}).
 *
 * <p>As referências (imóvel, cidade, tipo de serviço, vistoriador) são carregadas em lote a
 * partir dos ids da página corrente — nunca uma consulta por linha.</p>
 */
@Service
@RequiredArgsConstructor
public class SolicitacaoQueryService {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    private final ServicoRepository servicos;
    private final ImovelRepository imoveis;
    private final CidadeRepository cidades;
    private final TipoServicoRepository tiposServico;
    private final VistoriadorRepository vistoriadores;
    private final UsuarioRepository usuarios;
    private final br.com.vistoria.core.shared.PrecoVisibilidadePolicy precoPolicy;

    @Transactional(readOnly = true)
    public Page<SolicitacaoListItem> toListItems(Page<Solicitacao> page) {
        List<Solicitacao> rows = page.getContent();
        boolean ocultarPreco = precoPolicy.ocultarPrecoCobrado();
        // Página vazia: o mapper nunca roda, e sair aqui evita cinco consultas de lote inúteis.
        if (rows.isEmpty()) return Page.empty(page.getPageable());

        // Serviços de todas as solicitações da página, agrupados por solicitação.
        Map<UUID, List<SolicitacaoServico>> servicosPorSolicitacao = rows.stream()
                .collect(Collectors.toMap(Solicitacao::getId, s -> servicos.findBySolicitacaoId(s.getId())));

        List<SolicitacaoServico> todosServicos = servicosPorSolicitacao.values().stream()
                .flatMap(List::stream).toList();

        Map<UUID, Imovel> imovelPorId = byId(
                imoveis.findAllById(rows.stream().map(Solicitacao::getImovelId).filter(java.util.Objects::nonNull).distinct().toList()),
                Imovel::getId);

        Map<UUID, Cidade> cidadePorId = byId(
                cidades.findAllById(imovelPorId.values().stream().map(Imovel::getCidadeId).filter(java.util.Objects::nonNull).distinct().toList()),
                Cidade::getId);

        Map<UUID, TipoServico> tipoPorId = byId(
                tiposServico.findAllById(todosServicos.stream().map(SolicitacaoServico::getTipoServicoId).filter(java.util.Objects::nonNull).distinct().toList()),
                TipoServico::getId);

        Map<UUID, Vistoriador> vistoriadorPorId = byId(
                vistoriadores.findAllById(todosServicos.stream().map(SolicitacaoServico::getVistoriadorId).filter(java.util.Objects::nonNull).distinct().toList()),
                Vistoriador::getId);

        Map<UUID, Usuario> usuarioPorId = byId(
                usuarios.findAllById(vistoriadorPorId.values().stream().map(Vistoriador::getUsuarioId).filter(java.util.Objects::nonNull).distinct().toList()),
                Usuario::getId);

        return page.map(s -> {
            List<SolicitacaoServico> ss = servicosPorSolicitacao.getOrDefault(s.getId(), List.of());
            Imovel imovel = imovelPorId.get(s.getImovelId());
            Cidade cidade = imovel == null ? null : cidadePorId.get(imovel.getCidadeId());

            // A data/hora exibida é a do primeiro serviço agendado; sem agendamento, cai na criação.
            Instant quando = ss.stream()
                    .map(SolicitacaoServico::getDataAgendada)
                    .filter(java.util.Objects::nonNull)
                    .min(Instant::compareTo)
                    .orElse(s.getCriadoEm());
            LocalDateTime dt = LocalDateTime.ofInstant(quando, ZONE);

            String tipo = ss.stream()
                    .map(x -> tipoPorId.get(x.getTipoServicoId()))
                    .filter(java.util.Objects::nonNull)
                    .map(TipoServico::getNome)
                    .distinct()
                    .collect(Collectors.joining(" + "));

            String vistoriador = ss.stream()
                    .map(SolicitacaoServico::getVistoriadorId)
                    .filter(java.util.Objects::nonNull)
                    .map(vistoriadorPorId::get)
                    .filter(java.util.Objects::nonNull)
                    .map(v -> usuarioPorId.get(v.getUsuarioId()))
                    .filter(java.util.Objects::nonNull)
                    .map(Usuario::getNome)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse("A atribuir");

            BigDecimal total = ss.stream()
                    .map(SolicitacaoServico::getPrecoSnapshot)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            return new SolicitacaoListItem(
                    "SOL-" + s.getId(),
                    s.getId(),
                    imovel == null ? "—" : imovel.getEndereco(),
                    cidade == null ? "—" : cidade.getNome() + "/" + cidade.getUf(),
                    tipo.isBlank() ? "—" : tipo,
                    vistoriador,
                    dt.format(DATA),
                    dt.format(HORA),
                    rotuloStatus(ss),
                    ocultarPreco ? null : formatarMoeda(total),
                    s.isUrgente(),
                    s.getPaymentStatus(),
                    s.getCheckoutUrl());
        });
    }

    private static <T> Map<UUID, T> byId(List<T> list, Function<T, UUID> id) {
        return list.stream().collect(Collectors.toMap(id, Function.identity(), (a, b) -> a));
    }

    /**
     * Traduz o estado dos serviços para os rótulos que a UI exibe. Espelha a mesma regra de
     * {@code SolicitacaoService.statusDerivado}, em português de tela.
     */
    private static String rotuloStatus(List<SolicitacaoServico> ss) {
        if (ss.isEmpty()) return "Solicitada";
        if (ss.stream().allMatch(x -> x.getStatus() == SolicitacaoServico.Status.cancelada)) return "Cancelada";
        if (ss.stream().allMatch(x -> x.getStatus() == SolicitacaoServico.Status.concluida)) return "Concluída";
        if (ss.stream().anyMatch(x -> x.getStatus() == SolicitacaoServico.Status.em_execucao)) return "Em campo";
        if (ss.stream().anyMatch(x -> x.getStatus() == SolicitacaoServico.Status.agendada)) return "Agendada";
        if (ss.stream().anyMatch(x -> x.getStatus() == SolicitacaoServico.Status.atribuida)) return "Atribuída";
        return "Solicitada";
    }

    private static String formatarMoeda(BigDecimal valor) {
        return "R$ " + valor.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }
}
