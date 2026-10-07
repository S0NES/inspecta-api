package br.com.vistoria.core.modules.clienteb2c;

import br.com.vistoria.core.auth.AuthService;
import br.com.vistoria.core.modules.b2cservico.B2cServiceRepository;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.CidadeRepository;
import br.com.vistoria.core.modules.clienteb2c.dto.ClienteB2cResponse;
import br.com.vistoria.core.modules.usuario.Usuario;
import br.com.vistoria.core.modules.usuario.UsuarioRepository;
import br.com.vistoria.core.shared.exception.NotFoundException;
import br.com.vistoria.core.shared.validation.PessoaDocumento;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cliente B2C (Páginas 1-2 do prompt "4 telas B2C"): cadastro admin/suporte com convite
 * (perfil {@code cliente}) + listagem. Vive em inspecta-api (não inspecta-core) porque depende de
 * {@link AuthService#invite}, que por sua vez depende do SecurityContext — mesma divisão que
 * {@code UsuarioController}/{@code AuthService} já usam para o resto da equipe.
 */
@Service
@RequiredArgsConstructor
public class ClienteB2cService {

    private final ClienteB2cRepository clientesB2c;
    private final UsuarioRepository usuarios;
    private final CidadeRepository cidades;
    private final AuthService authService;
    private final B2cServiceRepository b2cServicos;

    public record CreateRequest(String nome, String email, String cpf, String telefone,
                                UUID cidadeId, List<UUID> clientServices) {}

    public record PatchRequest(String nome, String telefone, UUID cidadeId,
                               List<UUID> clientServices) {}

    private void validarClientServices(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("Selecione ao menos um servico habilitado");
        for (UUID id : ids) {
            if (!b2cServicos.existsById(id)) throw new IllegalArgumentException("Servico B2C invalido: " + id);
        }
    }

    @Transactional
    public ClienteB2c criarComConvite(CreateRequest req) {
        String nome = PessoaDocumento.requireNomeCompleto(req.nome());
        String email = PessoaDocumento.requireEmail(req.email());
        String cpf = PessoaDocumento.requireCpf(req.cpf());
        String telefone = PessoaDocumento.requireTelefone(req.telefone());

        if (req.cidadeId() == null || !cidades.existsById(req.cidadeId()))
            throw new IllegalArgumentException("Cidade obrigatoria");
        validarClientServices(req.clientServices());

        // invite() já valida email único (409/IllegalArgumentException) e dispara o inspecta-messenger de convite.
        authService.invite(email, nome, Usuario.Perfil.cliente, List.of());
        Usuario u = usuarios.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Usuario recem-convidado nao encontrado: " + email));

        ClienteB2c c = new ClienteB2c();
        c.setUsuarioId(u.getId());
        c.setNome(nome);
        c.setEmail(email);
        c.setCpf(cpf);
        c.setTelefone(telefone);
        c.setCidadeId(req.cidadeId());
        c.setClientServices(req.clientServices());
        return clientesB2c.save(c);
    }

    @Transactional(readOnly = true)
    public Page<ClienteB2cResponse> listar(Pageable pageable, String q, UUID cidadeId, Usuario.Status status) {
        boolean temQ = q != null && !q.isBlank();
        boolean temCidade = cidadeId != null;
        boolean temStatus = status != null;
        Page<ClienteB2c> page = clientesB2c.search(
                temQ, temQ ? "%" + q.trim().toLowerCase() + "%" : "",
                temCidade, cidadeId,
                temStatus, temStatus ? status : Usuario.Status.ativo,
                pageable);

        List<UUID> usuarioIds = page.getContent().stream().map(ClienteB2c::getUsuarioId).toList();
        Map<UUID, Usuario.Status> statusPorUsuario = new HashMap<>();
        usuarios.findAllById(usuarioIds).forEach(u -> statusPorUsuario.put(u.getId(), u.getStatus()));

        // quantidadeVistorias fica 0 ate a Pagina 4 (rascunho-b2c) ligar solicitacoes a clientes_b2c.
        return page.map(c -> ClienteB2cResponse.from(c, statusPorUsuario.get(c.getUsuarioId()), 0L));
    }

    @Transactional
    public ClienteB2c atualizar(UUID id, PatchRequest req) {
        ClienteB2c c = clientesB2c.findById(id).orElseThrow(() -> new NotFoundException("Cliente " + id));
        if (req.nome() != null && !req.nome().isBlank()) c.setNome(PessoaDocumento.requireNomeCompleto(req.nome()));
        if (req.telefone() != null && !req.telefone().isBlank()) c.setTelefone(PessoaDocumento.requireTelefone(req.telefone()));
        if (req.cidadeId() != null) {
            if (!cidades.existsById(req.cidadeId())) throw new IllegalArgumentException("Cidade invalida");
            c.setCidadeId(req.cidadeId());
        }
        if (req.clientServices() != null) {
            validarClientServices(req.clientServices());
            c.setClientServices(req.clientServices());
        }
        return clientesB2c.save(c);
    }

    @Transactional
    public void inativar(UUID id) {
        ClienteB2c c = clientesB2c.findById(id).orElseThrow(() -> new NotFoundException("Cliente " + id));
        authService.atualizarUsuario(c.getUsuarioId(), Usuario.Status.inativo, null, null, null);
    }
}
