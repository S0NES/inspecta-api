package br.com.vistoria.core.modules.cadastro;

import br.com.vistoria.core.modules.cadastro.CadastroRepositories.*;
import br.com.vistoria.core.shared.exception.NotFoundException;
import br.com.vistoria.core.shared.validation.PessoaDocumento;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "Cadastro", description = "Dados de referência: cidades, imóveis, clientes, intervenientes, tipos de serviço")
public class CadastroController {

    private final CidadeRepository cidades;
    private final ImovelRepository imoveis;
    private final ClienteRepository clientes;
    private final IntervenientePapelRepository intervenientePapeis;
    private final TipoServicoRepository tiposServico;
    private final CadastroService cadastroService;

    @GetMapping("/cidades")
    @Operation(summary = "Listar cidades", description = "Públicas sem autenticação.")
    @ApiResponse(responseCode = "200", description = "Lista de cidades")
    public Page<Cidade> listCidades(Pageable p) { return cidades.findAll(p); }

    @PostMapping("/cidades")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Criar cidade")
    @SecurityRequirement(name = "bearerAuth")
    public Cidade createCidade(@RequestBody(description = "Dados da cidade") @org.springframework.web.bind.annotation.RequestBody Cidade c) { return cidades.save(c); }

    @DeleteMapping("/cidades/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Excluir cidade",
            description = "Remove uma cidade cadastrada. 404 se não existir; 409 se ainda houver imóveis vinculados a ela.")
    @SecurityRequirement(name = "bearerAuth")
    public void deleteCidade(@PathVariable UUID id) {
        if (!cidades.existsById(id)) {
            throw new NotFoundException("Cidade " + id);
        }
        cidades.deleteById(id);
    }

    @GetMapping("/imoveis")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA','IMOB_OPERACAO')")
    @Operation(summary = "Listar imóveis")
    @SecurityRequirement(name = "bearerAuth")
    public Page<Imovel> listImoveis(Pageable p) { return imoveis.findAll(p); }

    @PostMapping("/imoveis")
    @PreAuthorize("hasAnyRole('ADMIN','IMOB_GERENCIA','IMOB_OPERACAO')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Criar imóvel")
    @SecurityRequirement(name = "bearerAuth")
    public Imovel createImovel(@RequestBody(description = "Dados do imóvel") @org.springframework.web.bind.annotation.RequestBody Imovel i) { return cadastroService.criarImovel(i); }

    @GetMapping("/imoveis/{id}")
    @Operation(summary = "Obter imóvel por ID")
    public Imovel getImovel(@PathVariable UUID id) {
        return imoveis.findById(id).orElseThrow(() -> new NotFoundException("Imovel " + id));
    }

    public record ImovelPatch(UUID cidadeId, String endereco, String numero, String complemento, String cib,
                              String observacoes, String cep, java.math.BigDecimal metragem,
                              Boolean mobiliado, String tipo, Boolean ativo,
                              Integer quartos, Boolean cobertura) {}

    @PatchMapping("/imoveis/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','IMOB_GERENCIA','IMOB_OPERACAO')")
    @Operation(summary = "Atualizar imóvel (parcial) — inclui situação ativo/inativo")
    @SecurityRequirement(name = "bearerAuth")
    public Imovel patchImovel(@PathVariable UUID id, @org.springframework.web.bind.annotation.RequestBody ImovelPatch b) {
        Imovel i = imoveis.findById(id).orElseThrow(() -> new NotFoundException("Imovel " + id));
        if (b.cidadeId() != null) i.setCidadeId(b.cidadeId());
        if (b.endereco() != null && !b.endereco().isBlank()) i.setEndereco(b.endereco().trim());
        if (b.numero() != null) i.setNumero(b.numero().isBlank() ? null : b.numero().trim());
        if (b.complemento() != null) i.setComplemento(b.complemento().isBlank() ? null : b.complemento().trim());
        if (b.cib() != null) i.setCib(b.cib().isBlank() ? null : b.cib().trim());
        if (b.observacoes() != null) i.setObservacoes(b.observacoes().isBlank() ? null : b.observacoes());
        if (b.cep() != null) i.setCep(b.cep().isBlank() ? null : b.cep().trim());
        if (b.metragem() != null) i.setMetragem(b.metragem());
        if (b.mobiliado() != null) i.setMobiliado(b.mobiliado());
        if (b.tipo() != null) i.setTipo(b.tipo().isBlank() ? null : b.tipo());
        if (b.ativo() != null) i.setAtivo(b.ativo());
        if (b.quartos() != null) i.setQuartos(b.quartos() <= 0 ? null : b.quartos());
        if (b.cobertura() != null) i.setCobertura(b.cobertura());
        return imoveis.save(i);
    }

    @GetMapping("/interveniente-papeis")
    @Operation(summary = "Listar papéis de interveniente ativos",
            description = "Lookup editável só pelo banco — sem POST/PUT/DELETE.")
    public java.util.List<IntervenientePapel> listIntervenientePapeis() {
        return intervenientePapeis.findByAtivoTrueOrderBySlug();
    }

    // Intervenientes DO IMÓVEL removidos — intervenientes vivem só na solicitação
    // (POST /v1/solicitacoes -> solicitacao_intervenientes). O lookup de papéis
    // continua em GET /v1/interveniente-papeis.

    @GetMapping("/clientes")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA','IMOB_OPERACAO')")
    @Operation(summary = "Listar clientes")
    @SecurityRequirement(name = "bearerAuth")
    public Page<Cliente> listClientes(Pageable p) { return clientes.findAll(p); }

    @PostMapping("/clientes")
    @PreAuthorize("hasAnyRole('ADMIN','SUPORTE','IMOB_GERENCIA','IMOB_OPERACAO')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Criar cliente")
    @SecurityRequirement(name = "bearerAuth")
    public Cliente createCliente(@RequestBody(description = "Dados do cliente") @org.springframework.web.bind.annotation.RequestBody Cliente c) {
        if (c.getCpfCnpj() != null && !c.getCpfCnpj().isBlank()) {
            String d = c.getCpfCnpj().replaceAll("\\D", "");
            c.setCpfCnpj(d.length() > 11 ? PessoaDocumento.requireCnpj(c.getCpfCnpj())
                                         : PessoaDocumento.requireCpf(c.getCpfCnpj()));
        }
        if (c.getEmail() != null && !c.getEmail().isBlank()) c.setEmail(PessoaDocumento.requireEmail(c.getEmail()));
        if (c.getTelefone() != null && !c.getTelefone().isBlank()) c.setTelefone(PessoaDocumento.requireTelefone(c.getTelefone()));
        return clientes.save(c);
    }

    @GetMapping("/tipos-servico")
    @Operation(summary = "Listar tipos de serviço")
    public Page<TipoServico> listTipos(Pageable p) { return tiposServico.findAll(p); }

    @PostMapping("/tipos-servico")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Criar tipo de serviço")
    @SecurityRequirement(name = "bearerAuth")
    public TipoServico createTipo(@RequestBody(description = "Dados do tipo de serviço") @org.springframework.web.bind.annotation.RequestBody TipoServico t) { return tiposServico.save(t); }

    public record TipoServicoPatch(String nome, Boolean ativo) {}

    @PatchMapping("/tipos-servico/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Atualizar nome/ativo de um tipo de serviço")
    @SecurityRequirement(name = "bearerAuth")
    public TipoServico patchTipo(@PathVariable UUID id, @org.springframework.web.bind.annotation.RequestBody TipoServicoPatch body) {
        TipoServico t = tiposServico.findById(id).orElseThrow(() -> new NotFoundException("TipoServico " + id));
        if (body.nome() != null && !body.nome().isBlank()) t.setNome(body.nome().trim());
        if (body.ativo() != null) t.setAtivo(body.ativo());
        return tiposServico.save(t);
    }
}
