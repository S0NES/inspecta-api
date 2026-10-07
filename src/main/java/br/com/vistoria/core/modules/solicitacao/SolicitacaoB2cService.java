package br.com.vistoria.core.modules.solicitacao;

import br.com.vistoria.core.modules.b2cservico.B2cService;
import br.com.vistoria.core.modules.b2cservico.B2cServiceRepository;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.ImovelRepository;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.IntervenientePapelRepository;
import br.com.vistoria.core.modules.cadastro.CadastroService;
import br.com.vistoria.core.modules.cadastro.Imovel;
import br.com.vistoria.core.modules.clienteb2c.ClienteB2c;
import br.com.vistoria.core.modules.clienteb2c.ClienteB2cRepository;
import br.com.vistoria.core.shared.exception.NotFoundException;
import br.com.vistoria.core.modules.contrato.PrecoService;
import br.com.vistoria.core.modules.disponibilidade.DisponibilidadeService;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.ServicoRepository;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.SolicitacaoIntervenienteRepository;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.SolicitacaoRepository;
import br.com.vistoria.core.shared.validation.PessoaDocumento;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Solicitação B2C (Página 4 do prompt "4 telas B2C"). Vive em inspecta-api (não inspecta-core) porque
 * lança {@link SemVistoriadorException} (também inspecta-api, mesmo pacote — ver
 * {@link CheckoutDisponibilidadeGuard}, que é o equivalente B2B read-only reusado no Passo 5).
 *
 * <p>Hold síncrono (decisão deste prompt, não existia antes): ao contrário do B2B — onde a
 * atribuição é 100% assíncrona pós-pagamento — o B2C escolhe e RESERVA um vistoriador já no
 * rascunho (fim do Passo 2), pra não cobrar por um horário sem cobertura. Isso usa
 * {@link DisponibilidadeService#escolher}, uma extração nova do
 * {@code existeVistoriadorElegivel} existente que devolve o vistoriador em vez de um booleano.
 * Simplificação deliberada, documentada lá: sem lock exclusivo nem expiração de hold.</p>
 */
@Service
@RequiredArgsConstructor
public class SolicitacaoB2cService {

    /** Fallback quando não há duração estimada configurada — B2C não tem catálogo de duração hoje. */
    private static final int DURACAO_PADRAO_MIN = 60;

    private final SolicitacaoRepository solicitacoes;
    private final ServicoRepository servicos;
    private final SolicitacaoIntervenienteRepository intervenientes;
    private final ImovelRepository imoveis;
    private final IntervenientePapelRepository papeis;
    private final ClienteB2cRepository clientesB2c;
    private final CadastroService cadastroService;
    private final PrecoService precoService;
    private final DisponibilidadeService disponibilidade;
    private final B2cServiceRepository b2cServicos;

    public record ImovelRascunho(UUID cidadeId, String cep, String endereco, String numero, String complemento,
                                 java.math.BigDecimal metragem, String tipo, Integer quartos, boolean cobertura,
                                 java.math.BigDecimal valorAluguel, boolean mobiliado) {}

    public record AcompanhanteRascunho(String papelSlug, String nome, String cpf, String email, String telefone) {}

    public record CriarRascunhoRequest(ImovelRascunho imovel, UUID b2cServiceId,
                                       Instant dataDesejada, AcompanhanteRascunho acompanhante) {}

    private B2cService servicoOuFalha(UUID b2cServiceId) {
        return b2cServicos.findById(b2cServiceId)
                .orElseThrow(() -> new NotFoundException("Servico B2C " + b2cServiceId));
    }

    private ClienteB2c clienteDoUsuario(UUID usuarioId) {
        return clientesB2c.findByUsuarioId(usuarioId)
                .orElseThrow(() -> new IllegalStateException("Usuario nao e um cliente B2C"));
    }

    private Solicitacao solicitacaoDoCliente(ClienteB2c cliente, UUID solicitacaoId) {
        Solicitacao s = solicitacoes.findById(solicitacaoId)
                .orElseThrow(() -> new NotFoundException("Solicitacao " + solicitacaoId));
        if (!cliente.getId().equals(s.getClienteB2cId()))
            throw new IllegalArgumentException("solicitacao_de_outro_cliente");
        return s;
    }

    @Transactional
    public Solicitacao criarRascunhoB2c(UUID usuarioId, CriarRascunhoRequest req) {
        ClienteB2c cliente = clienteDoUsuario(usuarioId);
        if (cliente.getClientServices() == null || !cliente.getClientServices().contains(req.b2cServiceId()))
            throw new IllegalArgumentException("client_service_nao_habilitado");
        if (req.imovel().cidadeId() == null || !req.imovel().cidadeId().equals(cliente.getCidadeId()))
            throw new IllegalArgumentException("cidade_do_imovel_diverge_da_cidade_do_cliente");

        Imovel imovel = new Imovel();
        imovel.setCidadeId(cliente.getCidadeId());
        imovel.setEndereco(req.imovel().endereco());
        imovel.setNumero(req.imovel().numero());
        imovel.setComplemento(req.imovel().complemento());
        imovel.setCep(req.imovel().cep());
        imovel.setMetragem(req.imovel().metragem());
        imovel.setTipo(req.imovel().tipo());
        imovel.setQuartos(req.imovel().quartos());
        imovel.setCobertura(req.imovel().cobertura());
        imovel.setMobiliado(req.imovel().mobiliado());
        imovel.setValorAluguel(req.imovel().valorAluguel());
        imovel = cadastroService.criarImovel(imovel);

        Solicitacao s = new Solicitacao();
        s.setClienteB2cId(cliente.getId());
        s.setImovelId(imovel.getId());
        s.setOrigem(Solicitacao.Origem.portal);
        s.setValorAluguel(req.imovel().valorAluguel());
        s.setMobiliado(req.imovel().mobiliado());
        s.setPaymentStatus("nao_iniciado");
        s = solicitacoes.saveAndFlush(s);

        SolicitacaoServico ss = new SolicitacaoServico();
        ss.setSolicitacaoId(s.getId());
        ss.setProductId(UUID.randomUUID());
        escolherEBind(s, ss, imovel, req.b2cServiceId(), req.dataDesejada());

        if (req.acompanhante() != null) {
            salvarAcompanhante(s.getId(), req.acompanhante());
        }
        return s;
    }

    /** Passo 1 mudou data/hora (ou o serviço) depois de um 409 — reusa o mesmo rascunho/imóvel. */
    @Transactional
    public Solicitacao reagendarPrecadastro(UUID usuarioId, UUID solicitacaoId,
                                            UUID b2cServiceId, Instant novaDataDesejada) {
        ClienteB2c cliente = clienteDoUsuario(usuarioId);
        Solicitacao s = solicitacaoDoCliente(cliente, solicitacaoId);
        Imovel imovel = imoveis.findById(s.getImovelId())
                .orElseThrow(() -> new NotFoundException("Imovel " + s.getImovelId()));
        List<SolicitacaoServico> lista = servicos.findBySolicitacaoId(s.getId());
        SolicitacaoServico ss = lista.isEmpty() ? novoServico(s) : lista.get(0);
        escolherEBind(s, ss, imovel, b2cServiceId, novaDataDesejada);
        return s;
    }

    private SolicitacaoServico novoServico(Solicitacao s) {
        SolicitacaoServico ss = new SolicitacaoServico();
        ss.setSolicitacaoId(s.getId());
        ss.setProductId(UUID.randomUUID());
        return ss;
    }

    private void escolherEBind(Solicitacao s, SolicitacaoServico ss, Imovel imovel,
                               UUID b2cServiceId, Instant dataDesejada) {
        B2cService servico = servicoOuFalha(b2cServiceId);
        ss.setTipoServicoId(servico.getTipoServicoId());
        ss.setB2cServiceId(b2cServiceId);
        ss.setDataDesejada(dataDesejada);

        PrecoService.Preco preco = precoService.resolverB2c(b2cServiceId, imovel.getMetragem(),
                s.getValorAluguel(), s.getMobiliado());
        ss.setPrecoSnapshot(preco.valor());

        var vistoriador = disponibilidade.escolher(imovel.getCidadeId(), imovel.getLat(), imovel.getLng(),
                DURACAO_PADRAO_MIN, dataDesejada);
        if (vistoriador.isEmpty()) {
            ss.setVistoriadorId(null);
            ss.setStatus(SolicitacaoServico.Status.pendente);
            ss.setDataAgendada(null);
            servicos.save(ss);
            throw new SemVistoriadorException(List.of(ss.getTipoServicoId()));
        }
        ss.setVistoriadorId(vistoriador.get().getId());
        ss.setStatus(SolicitacaoServico.Status.atribuida);
        ss.setDataAgendada(dataDesejada);
        servicos.save(ss);
    }

    private void salvarAcompanhante(UUID solicitacaoId, AcompanhanteRascunho a) {
        String papelSlug = a.papelSlug() != null && !a.papelSlug().isBlank() ? a.papelSlug() : "locatario";
        if (!papeis.existsBySlugAndAtivoTrue(papelSlug))
            throw new IllegalArgumentException("papel_invalido");
        intervenientes.findBySolicitacaoId(solicitacaoId).stream()
                .filter(SolicitacaoInterveniente::isAcompanhante)
                .forEach(intervenientes::delete);
        SolicitacaoInterveniente p = new SolicitacaoInterveniente();
        p.setSolicitacaoId(solicitacaoId);
        p.setPapelSlug(papelSlug);
        p.setNome(PessoaDocumento.requireNomeCompleto(a.nome()));
        p.setCpf(PessoaDocumento.requireCpf(a.cpf()));
        p.setEmail(PessoaDocumento.requireEmail(a.email()));
        if (a.telefone() != null && !a.telefone().isBlank())
            p.setTelefone(PessoaDocumento.requireTelefone(a.telefone()));
        p.setAcompanhante(true);
        intervenientes.save(p);
    }

    /** Passo 3 — substitui todos os intervenientes exceto o acompanhante do Passo 2 (não pode ser apagado aqui). */
    @Transactional
    public List<SolicitacaoInterveniente> substituirIntervenientes(UUID usuarioId, UUID solicitacaoId,
                                                                    List<AcompanhanteRascunho> novos) {
        ClienteB2c cliente = clienteDoUsuario(usuarioId);
        Solicitacao s = solicitacaoDoCliente(cliente, solicitacaoId);
        intervenientes.findBySolicitacaoId(s.getId()).stream()
                .filter(p -> !p.isAcompanhante())
                .forEach(intervenientes::delete);
        for (AcompanhanteRascunho item : novos) {
            String papelSlug = item.papelSlug();
            if (papelSlug == null || !papeis.existsBySlugAndAtivoTrue(papelSlug))
                throw new IllegalArgumentException("papel_invalido");
            SolicitacaoInterveniente p = new SolicitacaoInterveniente();
            p.setSolicitacaoId(s.getId());
            p.setPapelSlug(papelSlug);
            p.setNome(PessoaDocumento.requireNomeCompleto(item.nome()));
            p.setCpf(PessoaDocumento.requireCpf(item.cpf()));
            p.setEmail(PessoaDocumento.requireEmail(item.email()));
            if (item.telefone() != null && !item.telefone().isBlank())
                p.setTelefone(PessoaDocumento.requireTelefone(item.telefone()));
            p.setAcompanhante(false);
            intervenientes.save(p);
        }
        return intervenientes.findBySolicitacaoId(s.getId());
    }

    /**
     * Passo 4 (Salvar) — recalcula o preço final, valida ≥1 interveniente e que o hold do Passo 2
     * ainda está de pé. Sem expiração de hold implementada (ver javadoc da classe), então
     * "perdeu o slot" só acontece se o serviço nunca chegou a ficar {@code atribuida}.
     */
    @Transactional
    public Solicitacao finalizarRascunhoB2c(UUID usuarioId, UUID solicitacaoId) {
        ClienteB2c cliente = clienteDoUsuario(usuarioId);
        Solicitacao s = solicitacaoDoCliente(cliente, solicitacaoId);

        if (intervenientes.findBySolicitacaoId(s.getId()).isEmpty())
            throw new IllegalArgumentException("ao_menos_um_interveniente_obrigatorio");

        List<SolicitacaoServico> lista = servicos.findBySolicitacaoId(s.getId());
        if (lista.isEmpty()) throw new IllegalStateException("rascunho_sem_servico");
        SolicitacaoServico ss = lista.get(0);
        if (ss.getStatus() != SolicitacaoServico.Status.atribuida || ss.getVistoriadorId() == null)
            throw new SemVistoriadorException(List.of(ss.getTipoServicoId()));

        Imovel imovel = imoveis.findById(s.getImovelId())
                .orElseThrow(() -> new NotFoundException("Imovel " + s.getImovelId()));
        PrecoService.Preco preco = precoService.resolverB2c(ss.getB2cServiceId(), imovel.getMetragem(), s.getValorAluguel(), s.getMobiliado());
        ss.setPrecoSnapshot(preco.valor());
        servicos.save(ss);
        return s;
    }
}
