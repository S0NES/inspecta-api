package br.com.vistoria.core.modules.clienteb2c;

import br.com.vistoria.core.auth.AuthService;
import br.com.vistoria.core.modules.b2cservico.B2cServiceRepository;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.CidadeRepository;
import br.com.vistoria.core.modules.usuario.Usuario;
import br.com.vistoria.core.modules.usuario.UsuarioRepository;
import br.com.vistoria.core.shared.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClienteB2cService")
class ClienteB2cServiceTest {

    @Mock private ClienteB2cRepository clientesB2c;
    @Mock private UsuarioRepository usuarios;
    @Mock private CidadeRepository cidades;
    @Mock private AuthService authService;
    @Mock private B2cServiceRepository b2cServicos;

    private ClienteB2cService service;

    private static final UUID SERVICO_ENTRADA = UUID.fromString("00000000-b2c2-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        service = new ClienteB2cService(clientesB2c, usuarios, cidades, authService, b2cServicos);
    }

    private static UUID uid(long n) {
        return new UUID(0L, n);
    }

    private ClienteB2cService.CreateRequest validCreate(UUID cidadeId) {
        return new ClienteB2cService.CreateRequest(
                "Maria da Silva", "maria@example.com", "52998224725", "11987654321",
                cidadeId, List.of(SERVICO_ENTRADA));
    }

    @Test
    @DisplayName("criarComConvite: convida via AuthService, persiste com o usuarioId retornado")
    void criarComConvite_happyPath() {
        UUID cidadeId = uid(1);
        UUID usuarioId = uid(2);
        when(cidades.existsById(cidadeId)).thenReturn(true);
        when(b2cServicos.existsById(SERVICO_ENTRADA)).thenReturn(true);
        Usuario u = new Usuario();
        u.setId(usuarioId);
        u.setEmail("maria@example.com");
        when(usuarios.findByEmail("maria@example.com")).thenReturn(Optional.of(u));
        when(clientesB2c.save(any(ClienteB2c.class))).thenAnswer(inv -> inv.getArgument(0));

        ClienteB2c result = service.criarComConvite(validCreate(cidadeId));

        verify(authService).invite("maria@example.com", "Maria da Silva", Usuario.Perfil.cliente, List.of());
        assertThat(result.getUsuarioId()).isEqualTo(usuarioId);
        assertThat(result.getCidadeId()).isEqualTo(cidadeId);
        assertThat(result.getClientServices()).containsExactly(SERVICO_ENTRADA);
    }

    @Test
    @DisplayName("criarComConvite: cidade ausente ou inexistente -> IllegalArgumentException, nunca convida")
    void criarComConvite_cidadeInvalida() {
        UUID cidadeId = uid(1);
        when(cidades.existsById(cidadeId)).thenReturn(false);

        assertThatThrownBy(() -> service.criarComConvite(validCreate(cidadeId)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("criarComConvite: sem client_service selecionado -> IllegalArgumentException, nunca convida")
    void criarComConvite_semServicoHabilitado() {
        UUID cidadeId = uid(1);
        when(cidades.existsById(cidadeId)).thenReturn(true);
        var req = new ClienteB2cService.CreateRequest(
                "Maria da Silva", "maria@example.com", "52998224725", "11987654321",
                cidadeId, List.of());

        assertThatThrownBy(() -> service.criarComConvite(req))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("criarComConvite: cpf invalido -> falha na validacao antes de tocar repositorios/AuthService")
    void criarComConvite_cpfInvalido() {
        var req = new ClienteB2cService.CreateRequest(
                "Maria da Silva", "maria@example.com", "11111111111", "11987654321",
                uid(1), List.of(SERVICO_ENTRADA));

        assertThatThrownBy(() -> service.criarComConvite(req))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(authService, cidades, clientesB2c, b2cServicos);
    }

    @Test
    @DisplayName("listar: decora cada linha com o status do usuario vinculado")
    void listar_decoraStatus() {
        Pageable pageable = PageRequest.of(0, 20);
        ClienteB2c c = new ClienteB2c();
        c.setId(uid(3));
        c.setUsuarioId(uid(2));
        c.setNome("Maria da Silva");
        var page = new org.springframework.data.domain.PageImpl<>(List.of(c));
        when(clientesB2c.search(anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), eq(pageable)))
                .thenReturn(page);
        Usuario u = new Usuario();
        u.setId(uid(2));
        u.setStatus(Usuario.Status.ativo);
        when(usuarios.findAllById(List.of(uid(2)))).thenReturn(List.of(u));

        var result = service.listar(pageable, null, null, null);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).status()).isEqualTo(Usuario.Status.ativo);
        assertThat(result.getContent().get(0).quantidadeVistorias()).isZero();
    }

    @Test
    @DisplayName("atualizar: 404 se cliente nao existe")
    void atualizar_notFound() {
        UUID id = uid(9);
        when(clientesB2c.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.atualizar(id, new ClienteB2cService.PatchRequest(null, null, null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("inativar: delega para AuthService.atualizarUsuario com status=inativo")
    void inativar_delegaParaAuthService() {
        UUID id = uid(4);
        UUID usuarioId = uid(5);
        ClienteB2c c = new ClienteB2c();
        c.setId(id);
        c.setUsuarioId(usuarioId);
        when(clientesB2c.findById(id)).thenReturn(Optional.of(c));

        service.inativar(id);

        verify(authService).atualizarUsuario(usuarioId, Usuario.Status.inativo, null, null, null);
    }
}
