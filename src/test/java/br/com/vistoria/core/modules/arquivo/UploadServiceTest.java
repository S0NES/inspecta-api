package br.com.vistoria.core.modules.arquivo;

import br.com.vistoria.core.config.TenantContext;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.ImovelRepository;
import br.com.vistoria.core.modules.cadastro.Imovel;
import br.com.vistoria.core.modules.disponibilidade.DisponibilidadeRepositories.VistoriadorRepository;
import br.com.vistoria.core.modules.disponibilidade.Vistoriador;
import br.com.vistoria.core.modules.laudo.Laudo;
import br.com.vistoria.core.modules.laudo.LaudoRepository;
import br.com.vistoria.core.modules.solicitacao.Solicitacao;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoServico;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.SolicitacaoRepository;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.ServicoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadServiceTest {

    private final ArquivoService arquivoService = mock(ArquivoService.class);
    private final ImovelRepository imoveis = mock(ImovelRepository.class);
    private final LaudoRepository laudos = mock(LaudoRepository.class);
    private final ServicoRepository servicos = mock(ServicoRepository.class);
    private final SolicitacaoRepository solicitacoes = mock(SolicitacaoRepository.class);
    private final VistoriadorRepository vistoriadores = mock(VistoriadorRepository.class);

    private UploadService newService() {
        return new UploadService(arquivoService, imoveis, laudos, servicos, solicitacoes, vistoriadores);
    }

    /** UUID determinístico por pequeno inteiro, só para tornar os ids do teste legíveis. */
    private static UUID id(long n) {
        return new UUID(0L, n);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void uploadRejectsUnsupportedMimeAndOversizedFiles() {
        UploadService service = newService();
        byte[] bytes = new byte[100];
        assertThrows(IllegalArgumentException.class, () -> service.upload("text/plain", bytes, "imovel", id(1)));
        assertThrows(IllegalArgumentException.class, () -> service.upload("image/png", new byte[(int)(26L * 1024 * 1024)], "imovel", id(1)));
    }

    @Test
    void uploadRejectsUnknownOwnerType() {
        UploadService service = newService();
        byte[] bytes = new byte[128];
        assertThrows(IllegalArgumentException.class, () -> service.upload("image/png", bytes, "usuario", id(1)));
    }

    @Test
    void uploadStoresBytesAndRegistersArquivo() {
        Imovel imovel = new Imovel();
        imovel.setId(id(1));
        imovel.setImobiliariaId(id(10));
        when(imoveis.findById(id(1))).thenReturn(Optional.of(imovel));

        Arquivo arquivo = new Arquivo();
        arquivo.setId(id(5));
        when(arquivoService.registrar(anyString(), eq("image/png"), eq(128L), eq("imovel"), eq(id(1)), eq(false))).thenReturn(arquivo);

        UploadService service = newService();
        byte[] bytes = new byte[128];
        UploadService.UploadResult result = service.upload("image/png", bytes, "imovel", id(1));

        assertThat(result.arquivoId()).isEqualTo(id(5));
        verify(arquivoService).registrar(anyString(), eq("image/png"), eq(128L), eq("imovel"), eq(id(1)), eq(false));
        verify(arquivoService).salvarConteudo(id(5), bytes);
    }

    @Test
    void uploadRejectsCrossTenantImovel() {
        Imovel imovel = new Imovel();
        imovel.setId(id(1));
        imovel.setImobiliariaId(id(10));
        when(imoveis.findById(id(1))).thenReturn(Optional.of(imovel));
        TenantContext.set(id(99));

        UploadService service = newService();
        byte[] bytes = new byte[128];
        assertThrows(AccessDeniedException.class, () -> service.upload("image/png", bytes, "imovel", id(1)));
    }

    @Test
    void uploadAllowsSameTenantLaudo() {
        Laudo laudo = new Laudo();
        laudo.setId(id(7));
        laudo.setSolicitacaoServicoId(id(70));
        SolicitacaoServico ss = new SolicitacaoServico();
        ss.setId(id(70));
        ss.setSolicitacaoId(id(700));
        Solicitacao s = new Solicitacao();
        s.setId(id(700));
        s.setImobiliariaId(id(10));

        when(laudos.findById(id(7))).thenReturn(Optional.of(laudo));
        when(servicos.findById(id(70))).thenReturn(Optional.of(ss));
        when(solicitacoes.findById(id(700))).thenReturn(Optional.of(s));

        Arquivo arquivo = new Arquivo();
        arquivo.setId(id(3));
        when(arquivoService.registrar(anyString(), eq("application/pdf"), eq(50L), eq("laudo"), eq(id(7)), eq(false)))
                .thenReturn(arquivo);

        TenantContext.set(id(10));
        UploadService service = newService();
        byte[] bytes = new byte[50];
        UploadService.UploadResult result = service.upload("application/pdf", bytes, "laudo", id(7));
        assertThat(result.arquivoId()).isEqualTo(id(3));
        verify(arquivoService).salvarConteudo(id(3), bytes);
    }

    @Test
    void uploadAllowsImovelDeQualquerImobiliariaVinculada() {
        // V25: usuário em imob 3 e 10; o imóvel é da 10 -> permitido.
        Imovel imovel = new Imovel();
        imovel.setId(id(1));
        imovel.setImobiliariaId(id(10));
        when(imoveis.findById(id(1))).thenReturn(Optional.of(imovel));

        Arquivo arquivo = new Arquivo();
        arquivo.setId(id(5));
        when(arquivoService.registrar(anyString(), eq("image/png"), eq(128L), eq("imovel"), eq(id(1)), eq(false)))
                .thenReturn(arquivo);

        TenantContext.set(java.util.List.of(id(3), id(10)));
        UploadService service = newService();
        byte[] bytes = new byte[128];
        UploadService.UploadResult result = service.upload("image/png", bytes, "imovel", id(1));
        assertThat(result.arquivoId()).isEqualTo(id(5));
    }

    @Test
    void uploadRejectsCrossTenantLaudo() {
        Laudo laudo = new Laudo();
        laudo.setId(id(7));
        laudo.setSolicitacaoServicoId(id(70));
        SolicitacaoServico ss = new SolicitacaoServico();
        ss.setId(id(70));
        ss.setSolicitacaoId(id(700));
        Solicitacao s = new Solicitacao();
        s.setId(id(700));
        s.setImobiliariaId(id(10));

        when(laudos.findById(id(7))).thenReturn(Optional.of(laudo));
        when(servicos.findById(id(70))).thenReturn(Optional.of(ss));
        when(solicitacoes.findById(id(700))).thenReturn(Optional.of(s));

        TenantContext.set(id(99));
        UploadService service = newService();
        byte[] bytes = new byte[50];
        assertThrows(AccessDeniedException.class, () -> service.upload("application/pdf", bytes, "laudo", id(7)));
    }

    @Test
    void uploadAllowsComprovantePagamentoForOwningVistoriador() {
        Vistoriador v = new Vistoriador();
        v.setId(id(4));
        v.setUsuarioId(id(55));
        when(vistoriadores.findById(id(4))).thenReturn(Optional.of(v));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id(55), null, java.util.List.of()));

        Arquivo arquivo = new Arquivo();
        arquivo.setId(id(9));
        when(arquivoService.registrar(anyString(), eq("application/pdf"), eq(50L), eq("comprovante-pagamento"), eq(id(4)), eq(false)))
                .thenReturn(arquivo);

        UploadService service = newService();
        byte[] bytes = new byte[50];
        UploadService.UploadResult result = service.upload("application/pdf", bytes, "comprovante-pagamento", id(4));
        assertThat(result.arquivoId()).isEqualTo(id(9));
        verify(arquivoService).salvarConteudo(id(9), bytes);
    }

    @Test
    void uploadRejectsComprovantePagamentoForOtherVistoriador() {
        Vistoriador v = new Vistoriador();
        v.setId(id(4));
        v.setUsuarioId(id(55));
        when(vistoriadores.findById(id(4))).thenReturn(Optional.of(v));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id(66), null, java.util.List.of()));

        UploadService service = newService();
        byte[] bytes = new byte[50];
        assertThrows(AccessDeniedException.class,
                () -> service.upload("application/pdf", bytes, "comprovante-pagamento", id(4)));
    }
}
