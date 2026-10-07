package br.com.vistoria.core.modules.solicitacao;

import br.com.vistoria.core.modules.cadastro.CadastroRepositories.CidadeRepository;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.ImovelRepository;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.TipoServicoRepository;
import br.com.vistoria.core.modules.cadastro.Cidade;
import br.com.vistoria.core.modules.cadastro.Imovel;
import br.com.vistoria.core.modules.cadastro.TipoServico;
import br.com.vistoria.core.modules.disponibilidade.DisponibilidadeRepositories.VistoriadorRepository;
import br.com.vistoria.core.modules.disponibilidade.Vistoriador;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.ServicoRepository;
import br.com.vistoria.core.modules.solicitacao.dto.SolicitacaoListItem;
import br.com.vistoria.core.modules.usuario.Usuario;
import br.com.vistoria.core.modules.usuario.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

// No dedicated test existed for this class before (0% jacoco coverage on ~366 lines): it is the
// read-model mapper behind every solicitacao listing screen, with real branching logic (status
// label derivation, batch N+1-avoidance loading, currency/date formatting, and several "no data
// yet" fallbacks) that was completely unverified.
@ExtendWith(MockitoExtension.class)
@DisplayName("SolicitacaoQueryService - list item read-model mapping")
class SolicitacaoQueryServiceTest {

    @Mock
    private ServicoRepository servicos;
    @Mock
    private ImovelRepository imoveis;
    @Mock
    private CidadeRepository cidades;
    @Mock
    private TipoServicoRepository tiposServico;
    @Mock
    private VistoriadorRepository vistoriadores;
    @Mock
    private UsuarioRepository usuarios;
    @Mock
    private br.com.vistoria.core.shared.PrecoVisibilidadePolicy precoPolicy;

    @InjectMocks
    private SolicitacaoQueryService service;

    /** UUID determinístico por pequeno inteiro, só para tornar os ids do teste legíveis. */
    private static UUID id(long n) {
        return new UUID(0L, n);
    }

    private Solicitacao solicitacao(UUID id, UUID imovelId) {
        Solicitacao s = new Solicitacao();
        s.setId(id);
        s.setImovelId(imovelId);
        s.setUrgente(false);
        s.setCriadoEm(Instant.parse("2026-01-10T12:00:00Z"));
        return s;
    }

    private SolicitacaoServico servico(UUID solicitacaoId, SolicitacaoServico.Status status,
                                        UUID tipoServicoId, UUID vistoriadorId, BigDecimal preco) {
        SolicitacaoServico ss = new SolicitacaoServico();
        ss.setSolicitacaoId(solicitacaoId);
        ss.setStatus(status);
        ss.setTipoServicoId(tipoServicoId);
        ss.setVistoriadorId(vistoriadorId);
        ss.setPrecoSnapshot(preco);
        return ss;
    }

    @Test
    @DisplayName("empty page short-circuits without querying any batch repository")
    void toListItems_emptyPage_returnsEmptyWithoutRepoCalls() {
        Page<Solicitacao> empty = Page.empty(PageRequest.of(0, 20));

        Page<SolicitacaoListItem> result = service.toListItems(empty);

        assertThat(result.getContent()).isEmpty();
        verifyNoInteractions(servicos, imoveis, cidades, tiposServico, vistoriadores, usuarios);
    }

    @Test
    @DisplayName("full happy path resolves endereco, cidade, tipo, vistoriador and total value")
    void toListItems_fullMapping_resolvesAllReferences() {
        Solicitacao s = solicitacao(id(1), id(10));
        Page<Solicitacao> page = new PageImpl<>(List.of(s), PageRequest.of(0, 20), 1);

        SolicitacaoServico ss = servico(id(1), SolicitacaoServico.Status.agendada, id(100), id(200), BigDecimal.valueOf(150));
        ss.setDataAgendada(Instant.parse("2026-02-01T13:30:00Z")); // 10:30 in America/Sao_Paulo (UTC-3)
        when(servicos.findBySolicitacaoId(id(1))).thenReturn(List.of(ss));

        Imovel imovel = new Imovel();
        imovel.setId(id(10));
        imovel.setCidadeId(id(20));
        imovel.setEndereco("Rua das Flores, 100");
        when(imoveis.findAllById(List.of(id(10)))).thenReturn(List.of(imovel));

        Cidade cidade = new Cidade();
        cidade.setId(id(20));
        cidade.setNome("Sao Paulo");
        cidade.setUf("SP");
        when(cidades.findAllById(List.of(id(20)))).thenReturn(List.of(cidade));

        TipoServico tipo = new TipoServico();
        tipo.setId(id(100));
        tipo.setNome("Vistoria de Entrada");
        when(tiposServico.findAllById(List.of(id(100)))).thenReturn(List.of(tipo));

        Vistoriador vistoriador = new Vistoriador();
        vistoriador.setId(id(200));
        vistoriador.setUsuarioId(id(300));
        when(vistoriadores.findAllById(List.of(id(200)))).thenReturn(List.of(vistoriador));

        Usuario usuario = new Usuario();
        usuario.setId(id(300));
        usuario.setNome("Joao Vistoriador");
        when(usuarios.findAllById(List.of(id(300)))).thenReturn(List.of(usuario));

        Page<SolicitacaoListItem> result = service.toListItems(page);

        SolicitacaoListItem item = result.getContent().get(0);
        assertThat(item.id()).isEqualTo("SOL-" + id(1));
        assertThat(item.solicitacaoId()).isEqualTo(id(1));
        assertThat(item.endereco()).isEqualTo("Rua das Flores, 100");
        assertThat(item.imagem()).isEqualTo("Sao Paulo/SP");
        assertThat(item.tipo()).isEqualTo("Vistoria de Entrada");
        assertThat(item.vistoriador()).isEqualTo("Joao Vistoriador");
        assertThat(item.data()).isEqualTo("01/02/2026");
        assertThat(item.hora()).isEqualTo("10:30");
        assertThat(item.status()).isEqualTo("Agendada");
        assertThat(item.valor()).isEqualTo("R$ 150,00");
        assertThat(item.urgente()).isFalse();
    }

    @Test
    @DisplayName("missing imovel falls back to dash placeholders for endereco/cidade")
    void toListItems_missingImovel_usesDashPlaceholders() {
        Solicitacao s = solicitacao(id(2), id(999)); // imovel id never resolved
        Page<Solicitacao> page = new PageImpl<>(List.of(s), PageRequest.of(0, 20), 1);

        when(servicos.findBySolicitacaoId(id(2))).thenReturn(List.of());
        when(imoveis.findAllById(List.of(id(999)))).thenReturn(List.of());
        when(cidades.findAllById(List.of())).thenReturn(List.of());
        when(tiposServico.findAllById(List.of())).thenReturn(List.of());
        when(vistoriadores.findAllById(List.of())).thenReturn(List.of());
        when(usuarios.findAllById(List.of())).thenReturn(List.of());

        SolicitacaoListItem item = service.toListItems(page).getContent().get(0);

        assertThat(item.endereco()).isEqualTo("—");
        assertThat(item.imagem()).isEqualTo("—");
        assertThat(item.tipo()).isEqualTo("—");
        assertThat(item.vistoriador()).isEqualTo("A atribuir");
        assertThat(item.valor()).isEqualTo("R$ 0,00");
        // No scheduled servico -> falls back to the solicitacao's own criadoEm.
        assertThat(item.data()).isEqualTo("10/01/2026");
    }

    @Test
    @DisplayName("multiple servicos join distinct tipo names and sum their preco snapshots")
    void toListItems_multipleServicos_joinsTiposAndSumsValores() {
        Solicitacao s = solicitacao(id(3), null);
        Page<Solicitacao> page = new PageImpl<>(List.of(s), PageRequest.of(0, 20), 1);

        SolicitacaoServico ss1 = servico(id(3), SolicitacaoServico.Status.concluida, id(100), null, BigDecimal.valueOf(100));
        SolicitacaoServico ss2 = servico(id(3), SolicitacaoServico.Status.concluida, id(101), null, BigDecimal.valueOf(50));
        when(servicos.findBySolicitacaoId(id(3))).thenReturn(List.of(ss1, ss2));
        when(imoveis.findAllById(List.of())).thenReturn(List.of());
        when(cidades.findAllById(List.of())).thenReturn(List.of());

        TipoServico t1 = new TipoServico();
        t1.setId(id(100));
        t1.setNome("Entrada");
        TipoServico t2 = new TipoServico();
        t2.setId(id(101));
        t2.setNome("Saida");
        when(tiposServico.findAllById(List.of(id(100), id(101)))).thenReturn(List.of(t1, t2));
        when(vistoriadores.findAllById(List.of())).thenReturn(List.of());
        when(usuarios.findAllById(List.of())).thenReturn(List.of());

        SolicitacaoListItem item = service.toListItems(page).getContent().get(0);

        assertThat(item.tipo()).isEqualTo("Entrada + Saida");
        assertThat(item.valor()).isEqualTo("R$ 150,00");
        assertThat(item.status()).isEqualTo("Concluída");
    }

    @Test
    @DisplayName("quando a política manda ocultar (vistoriador), valor sai nulo")
    void toListItems_ocultaValorQuandoPoliticaManda() {
        Solicitacao s = solicitacao(id(4), null);
        Page<Solicitacao> page = new PageImpl<>(List.of(s), PageRequest.of(0, 20), 1);
        SolicitacaoServico ss = servico(id(4), SolicitacaoServico.Status.agendada, id(100), null, BigDecimal.valueOf(200));
        when(precoPolicy.ocultarPrecoCobrado()).thenReturn(true);
        when(servicos.findBySolicitacaoId(id(4))).thenReturn(List.of(ss));
        when(imoveis.findAllById(List.of())).thenReturn(List.of());
        when(cidades.findAllById(List.of())).thenReturn(List.of());
        when(tiposServico.findAllById(List.of(id(100)))).thenReturn(List.of());
        when(vistoriadores.findAllById(List.of())).thenReturn(List.of());
        when(usuarios.findAllById(List.of())).thenReturn(List.of());

        SolicitacaoListItem item = service.toListItems(page).getContent().get(0);

        assertThat(item.valor()).isNull();
    }

    @Test
    @DisplayName("status label: no servicos yet is 'Solicitada'")
    void rotuloStatus_noServicos_solicitada() {
        assertThat(statusFromMix()).isEqualTo("Solicitada");
    }

    @Test
    @DisplayName("status label: em_execucao wins over other mixed statuses ('Em campo')")
    void rotuloStatus_anyEmExecucao_emCampo() {
        assertThat(statusFromMix(SolicitacaoServico.Status.concluida, SolicitacaoServico.Status.em_execucao))
                .isEqualTo("Em campo");
    }

    @Test
    @DisplayName("status label: agendada without em_execucao yields 'Agendada'")
    void rotuloStatus_agendadaWithoutEmExecucao_agendada() {
        assertThat(statusFromMix(SolicitacaoServico.Status.pendente, SolicitacaoServico.Status.agendada))
                .isEqualTo("Agendada");
    }

    @Test
    @DisplayName("status label: atribuida alone (no agendada/em_execucao) yields 'Atribuida'")
    void rotuloStatus_atribuidaOnly_atribuida() {
        assertThat(statusFromMix(SolicitacaoServico.Status.atribuida)).isEqualTo("Atribuída");
    }

    @Test
    @DisplayName("status label: all cancelada yields 'Cancelada'")
    void rotuloStatus_allCancelada_cancelada() {
        assertThat(statusFromMix(SolicitacaoServico.Status.cancelada, SolicitacaoServico.Status.cancelada))
                .isEqualTo("Cancelada");
    }

    @Test
    @DisplayName("status label: mixed pendente-only servicos default back to 'Solicitada'")
    void rotuloStatus_allPendente_solicitada() {
        assertThat(statusFromMix(SolicitacaoServico.Status.pendente, SolicitacaoServico.Status.pendente))
                .isEqualTo("Solicitada");
    }

    private String statusFromMix(SolicitacaoServico.Status... statuses) {
        Solicitacao s = solicitacao(id(9), null);
        Page<Solicitacao> page = new PageImpl<>(List.of(s), PageRequest.of(0, 20), 1);

        List<SolicitacaoServico> lista = List.of(statuses).stream()
                .map(st -> servico(id(9), st, null, null, null))
                .toList();
        when(servicos.findBySolicitacaoId(id(9))).thenReturn(lista);
        when(imoveis.findAllById(List.of())).thenReturn(List.of());
        when(cidades.findAllById(List.of())).thenReturn(List.of());
        when(tiposServico.findAllById(List.of())).thenReturn(List.of());
        when(vistoriadores.findAllById(List.of())).thenReturn(List.of());
        when(usuarios.findAllById(List.of())).thenReturn(List.of());

        return service.toListItems(page).getContent().get(0).status();
    }
}
