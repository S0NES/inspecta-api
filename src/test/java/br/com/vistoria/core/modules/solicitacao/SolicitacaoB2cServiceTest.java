package br.com.vistoria.core.modules.solicitacao;

import br.com.vistoria.core.modules.b2cservico.B2cService;
import br.com.vistoria.core.modules.b2cservico.B2cServiceRepository;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.ImovelRepository;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.IntervenientePapelRepository;
import br.com.vistoria.core.modules.cadastro.CadastroService;
import br.com.vistoria.core.modules.cadastro.Imovel;
import br.com.vistoria.core.modules.clienteb2c.ClienteB2c;
import br.com.vistoria.core.modules.clienteb2c.ClienteB2cRepository;
import br.com.vistoria.core.modules.contrato.PrecoService;
import br.com.vistoria.core.modules.disponibilidade.DisponibilidadeService;
import br.com.vistoria.core.modules.disponibilidade.Vistoriador;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.ServicoRepository;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.SolicitacaoIntervenienteRepository;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.SolicitacaoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SolicitacaoB2cService")
class SolicitacaoB2cServiceTest {

    @Mock private SolicitacaoRepository solicitacoes;
    @Mock private ServicoRepository servicos;
    @Mock private SolicitacaoIntervenienteRepository intervenientes;
    @Mock private ImovelRepository imoveis;
    @Mock private IntervenientePapelRepository papeis;
    @Mock private ClienteB2cRepository clientesB2c;
    @Mock private CadastroService cadastroService;
    @Mock private PrecoService precoService;
    @Mock private DisponibilidadeService disponibilidade;
    @Mock private B2cServiceRepository b2cServicos;

    private SolicitacaoB2cService service;

    private static final UUID SERVICO_ENTRADA = UUID.fromString("00000000-b2c2-0000-0000-000000000001");
    private static final UUID SERVICO_SAIDA = UUID.fromString("00000000-b2c2-0000-0000-000000000002");
    private static final UUID TIPO_SERVICO_ENTRADA = UUID.fromString("00000000-b2c0-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        service = new SolicitacaoB2cService(solicitacoes, servicos, intervenientes, imoveis, papeis,
                clientesB2c, cadastroService, precoService, disponibilidade, b2cServicos);
    }

    private static UUID uid(long n) { return new UUID(0L, n); }

    private ClienteB2c cliente(UUID usuarioId, UUID cidadeId) {
        ClienteB2c c = new ClienteB2c();
        c.setId(uid(100));
        c.setUsuarioId(usuarioId);
        c.setCidadeId(cidadeId);
        c.setClientServices(List.of(SERVICO_ENTRADA));
        return c;
    }

    private B2cService servicoEntrada() {
        B2cService s = new B2cService();
        s.setId(SERVICO_ENTRADA);
        s.setNome("Vistoria de Entrada");
        s.setTipoServicoId(TIPO_SERVICO_ENTRADA);
        return s;
    }

    private SolicitacaoB2cService.CriarRascunhoRequest req(UUID cidadeId, Instant data) {
        var imovel = new SolicitacaoB2cService.ImovelRascunho(cidadeId, "01001-000", "Praca da Se", "1", null,
                new BigDecimal("50"), "apartamento", 2, false, new BigDecimal("2000"), false);
        return new SolicitacaoB2cService.CriarRascunhoRequest(imovel, SERVICO_ENTRADA, data, null);
    }

    @Test
    @DisplayName("criarRascunhoB2c: client_service nao habilitado -> IllegalArgumentException")
    void criar_servicoNaoHabilitado() {
        UUID usuarioId = uid(1);
        UUID cidadeId = uid(2);
        when(clientesB2c.findByUsuarioId(usuarioId)).thenReturn(Optional.of(cliente(usuarioId, cidadeId)));
        var reqSaida = new SolicitacaoB2cService.CriarRascunhoRequest(
                new SolicitacaoB2cService.ImovelRascunho(cidadeId, null, "Rua X", null, null,
                        new BigDecimal("50"), "casa", null, false, null, false),
                SERVICO_SAIDA, Instant.now(), null);

        assertThatThrownBy(() -> service.criarRascunhoB2c(usuarioId, reqSaida))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(cadastroService);
    }

    @Test
    @DisplayName("criarRascunhoB2c: cidade do imovel diverge da cidade do cliente -> IllegalArgumentException")
    void criar_cidadeDivergente() {
        UUID usuarioId = uid(1);
        UUID cidadeCliente = uid(2);
        UUID outraCidade = uid(3);
        when(clientesB2c.findByUsuarioId(usuarioId)).thenReturn(Optional.of(cliente(usuarioId, cidadeCliente)));

        assertThatThrownBy(() -> service.criarRascunhoB2c(usuarioId, req(outraCidade, Instant.now())))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(cadastroService);
    }

    @Test
    @DisplayName("criarRascunhoB2c: sem vistoriador elegivel -> SemVistoriadorException, servico salvo como pendente")
    void criar_semVistoriador() {
        UUID usuarioId = uid(1);
        UUID cidadeId = uid(2);
        Instant data = Instant.now();
        when(clientesB2c.findByUsuarioId(usuarioId)).thenReturn(Optional.of(cliente(usuarioId, cidadeId)));
        when(b2cServicos.findById(SERVICO_ENTRADA)).thenReturn(Optional.of(servicoEntrada()));

        Imovel imovelSalvo = new Imovel();
        imovelSalvo.setId(uid(10));
        imovelSalvo.setCidadeId(cidadeId);
        imovelSalvo.setMetragem(new BigDecimal("50"));
        when(cadastroService.criarImovel(any(Imovel.class))).thenReturn(imovelSalvo);

        when(solicitacoes.saveAndFlush(any(Solicitacao.class))).thenAnswer(inv -> {
            Solicitacao s = inv.getArgument(0);
            s.setId(uid(20));
            return s;
        });

        when(precoService.resolverB2c(eq(SERVICO_ENTRADA), any(), any(), any()))
                .thenReturn(new PrecoService.Preco(new BigDecimal("189.00"), null, null));
        when(disponibilidade.escolher(eq(cidadeId), any(), any(), anyInt(), eq(data)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.criarRascunhoB2c(usuarioId, req(cidadeId, data)))
                .isInstanceOf(SemVistoriadorException.class);

        verify(servicos).save(argThat(ss ->
                ss.getStatus() == SolicitacaoServico.Status.pendente && ss.getVistoriadorId() == null));
    }

    @Test
    @DisplayName("criarRascunhoB2c: happy path reserva vistoriador e grava preco")
    void criar_happyPath() {
        UUID usuarioId = uid(1);
        UUID cidadeId = uid(2);
        Instant data = Instant.now();
        when(clientesB2c.findByUsuarioId(usuarioId)).thenReturn(Optional.of(cliente(usuarioId, cidadeId)));
        when(b2cServicos.findById(SERVICO_ENTRADA)).thenReturn(Optional.of(servicoEntrada()));

        Imovel imovelSalvo = new Imovel();
        imovelSalvo.setId(uid(10));
        imovelSalvo.setCidadeId(cidadeId);
        imovelSalvo.setMetragem(new BigDecimal("50"));
        when(cadastroService.criarImovel(any(Imovel.class))).thenReturn(imovelSalvo);
        when(solicitacoes.saveAndFlush(any(Solicitacao.class))).thenAnswer(inv -> {
            Solicitacao s = inv.getArgument(0);
            s.setId(uid(20));
            return s;
        });
        when(precoService.resolverB2c(any(), any(), any(), any()))
                .thenReturn(new PrecoService.Preco(new BigDecimal("189.00"), null, null));
        Vistoriador v = new Vistoriador();
        v.setId(uid(30));
        when(disponibilidade.escolher(eq(cidadeId), any(), any(), anyInt(), eq(data)))
                .thenReturn(Optional.of(v));

        Solicitacao resultado = service.criarRascunhoB2c(usuarioId, req(cidadeId, data));

        assertThat(resultado.getClienteB2cId()).isEqualTo(uid(100));
        verify(servicos).save(argThat(ss ->
                ss.getStatus() == SolicitacaoServico.Status.atribuida
                        && ss.getVistoriadorId().equals(uid(30))
                        && ss.getDataAgendada().equals(data)
                        && ss.getPrecoSnapshot().compareTo(new BigDecimal("189.00")) == 0));
    }

    @Test
    @DisplayName("finalizarRascunhoB2c: sem interveniente -> IllegalArgumentException")
    void finalizar_semInterveniente() {
        UUID usuarioId = uid(1);
        UUID solicitacaoId = uid(20);
        ClienteB2c c = cliente(usuarioId, uid(2));
        when(clientesB2c.findByUsuarioId(usuarioId)).thenReturn(Optional.of(c));
        Solicitacao s = new Solicitacao();
        s.setId(solicitacaoId);
        s.setClienteB2cId(c.getId());
        when(solicitacoes.findById(solicitacaoId)).thenReturn(Optional.of(s));
        when(intervenientes.findBySolicitacaoId(solicitacaoId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.finalizarRascunhoB2c(usuarioId, solicitacaoId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("finalizarRascunhoB2c: solicitacao de outro cliente -> IllegalArgumentException")
    void finalizar_solicitacaoDeOutroCliente() {
        UUID usuarioId = uid(1);
        UUID solicitacaoId = uid(20);
        when(clientesB2c.findByUsuarioId(usuarioId)).thenReturn(Optional.of(cliente(usuarioId, uid(2))));
        Solicitacao s = new Solicitacao();
        s.setId(solicitacaoId);
        s.setClienteB2cId(uid(999));
        when(solicitacoes.findById(solicitacaoId)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.finalizarRascunhoB2c(usuarioId, solicitacaoId))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(intervenientes);
    }

    @Test
    @DisplayName("substituirIntervenientes: preserva o acompanhante, substitui os demais")
    void substituirIntervenientes_preservaAcompanhante() {
        UUID usuarioId = uid(1);
        UUID solicitacaoId = uid(20);
        ClienteB2c c = cliente(usuarioId, uid(2));
        when(clientesB2c.findByUsuarioId(usuarioId)).thenReturn(Optional.of(c));
        Solicitacao s = new Solicitacao();
        s.setId(solicitacaoId);
        s.setClienteB2cId(c.getId());
        when(solicitacoes.findById(solicitacaoId)).thenReturn(Optional.of(s));

        SolicitacaoInterveniente acompanhante = new SolicitacaoInterveniente();
        acompanhante.setId(uid(50));
        acompanhante.setAcompanhante(true);
        SolicitacaoInterveniente outro = new SolicitacaoInterveniente();
        outro.setId(uid(51));
        outro.setAcompanhante(false);
        when(intervenientes.findBySolicitacaoId(solicitacaoId))
                .thenReturn(List.of(acompanhante, outro))
                .thenReturn(List.of(acompanhante));
        when(papeis.existsBySlugAndAtivoTrue("locador")).thenReturn(true);

        service.substituirIntervenientes(usuarioId, solicitacaoId,
                List.of(new SolicitacaoB2cService.AcompanhanteRascunho("locador", "Joao da Silva",
                        "52998224725", "joao@example.com", null)));

        verify(intervenientes).delete(outro);
        verify(intervenientes, never()).delete(acompanhante);
        verify(intervenientes).save(argThat(p -> !p.isAcompanhante() && "locador".equals(p.getPapelSlug())));
    }
}
