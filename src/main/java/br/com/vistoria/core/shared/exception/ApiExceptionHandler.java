package br.com.vistoria.core.shared.exception;

import br.com.vistoria.core.modules.contrato.ContratoNaoConfiguradoException;
import br.com.vistoria.core.modules.pdftemplate.TemplateInvalidoException;
import br.com.vistoria.core.modules.solicitacao.SemVistoriadorException;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoPosPagaException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * Erro de validacao de entrada vindo da camada de servico/controller.
     *
     * <p>Todos os {@code IllegalArgumentException} lancados neste servico descrevem um pedido
     * malformado ("Motivo obrigatorio", "Arquivo excede 25MB", "hora_fim deve ser depois de
     * hora_inicio"...), nunca uma falha de autenticacao — falhas de credencial usam
     * {@link org.springframework.security.core.AuthenticationException}. Mapear isso para 401
     * fazia o cliente concluir que o token expirou e disparar refresh/logout em cima de um
     * simples payload invalido, alem de poluir a metrica de 401 do gateway. O status correto
     * e 400.</p>
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail illegalArgument(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    public ProblemDetail authenticationCredentialsNotFound(AuthenticationCredentialsNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    /**
     * Falha de credencial (login, refresh, aceite de convite, codigo de redefinicao de senha).
     * Antes escapava do DispatcherServlet e so era traduzida pelo ExceptionTranslationFilter;
     * declarar aqui garante o 401 documentado no OpenAPI mesmo quando a cadeia de filtros nao
     * esta no caminho, e mantem a mensagem generica ("Credencial invalida") para nao distinguir
     * "email inexistente" de "senha errada" (ou "codigo errado" de "codigo expirado") para quem
     * enumera contas.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail authentication(AuthenticationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Credencial invalida");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail accessDenied(AccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied");
    }

    /**
     * Erro de estado de negocio vindo da camada de servico/controller.
     *
     * <p>Todos os {@code IllegalStateException} lancados neste servico descrevem uma operacao
     * incompativel com o estado atual do recurso ("Nova versao apenas de laudo assinado",
     * "Solicitacao B2B nao usa gateway de pagamento"...), nunca uma falha de autenticacao ou
     * autorizacao. Sem handler dedicado, essa excecao escapava sem mapeamento e virava um 403
     * vazio, disfarcando um erro de estado/validacao como se fosse falta de permissao. O status
     * correto e 400.</p>
     */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail illegalState(IllegalStateException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ProblemDetail missingServletRequestPart(MissingServletRequestPartException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Arquivo obrigatorio ausente (campo '" + ex.getRequestPartName() + "')");
    }

    /** Query-param obrigatório ausente — erro do cliente (antes caía no catch-all como 500). */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail missingRequestParameter(MissingServletRequestParameterException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Parametro obrigatorio ausente: '" + ex.getParameterName() + "'");
    }

    /**
     * Template de PDF recusado no save. 422 (nao 400) porque o payload esta bem-formado — o
     * conteudo do template e que nao passa na validacao. A lista completa de erros vai na
     * resposta para o editor corrigir tudo de uma vez.
     */
    @ExceptionHandler(TemplateInvalidoException.class)
    public ProblemDetail templateInvalido(TemplateInvalidoException ex) {
        var pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setDetail("Template invalido");
        pd.setProperty("errors", ex.getErros());
        return pd;
    }

    /**
     * Violacao de restricao do banco (unique, FK, NOT NULL) que escapou da validacao de
     * servico. 409 em vez de 500: e um conflito de estado com dados existentes, nao um bug —
     * ex.: cidade referida por imoveis ao ser apagada, ou template gravado com chave duplicada.
     * O detalhe interno (nome da constraint, SQL) nunca vai na resposta.
     */
    /** Já existe uma imobiliária com esse CNPJ. 409 — conflito de estado, não payload malformado. */
    @ExceptionHandler(CnpjDuplicadoException.class)
    public ProblemDetail cnpjDuplicado(CnpjDuplicadoException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail dataIntegrity(DataIntegrityViolationException ex) {
        log.warn("violacao de integridade: {}", ex.getMostSpecificCause().getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "A operacao conflita com dados existentes e nao pode ser concluida.");
    }

    /**
     * V3 (decisão de negócio 4): não há vistoriador elegível para ao menos um serviço pendente
     * na janela desejada. 409 (conflito com o estado atual da oferta) com o contrato fechado
     * {@code {codigo:"SEM_VISTORIADOR", servicos:[tipoServicoId,...]}}; o pagamento não é iniciado.
     */
    @ExceptionHandler(SemVistoriadorException.class)
    public ProblemDetail semVistoriador(SemVistoriadorException ex) {
        var pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setDetail(ex.getMessage());
        pd.setProperty("codigo", "SEM_VISTORIADOR");
        pd.setProperty("servicos", ex.getTiposServicoId());
        return pd;
    }

    /**
     * Sem contrato ativo / serviço configurado para resolver o preço da solicitação. 422 (payload
     * bem-formado, estado comercial incompleto) com {@code codigo:"CONTRATO_NAO_CONFIGURADO"} — o
     * mesmo código do preview. Nunca há fallback para preço manual.
     */
    @ExceptionHandler(ContratoNaoConfiguradoException.class)
    public ProblemDetail contratoNaoConfigurado(ContratoNaoConfiguradoException ex) {
        var pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setDetail(ex.getMessage());
        pd.setProperty("codigo", "CONTRATO_NAO_CONFIGURADO");
        return pd;
    }

    /**
     * Fluxo B2B (imobiliária): a solicitação é pós-paga e não aceita cobrança individual.
     * 410 Gone (o recurso "cobrança da solicitação" deixou de existir para esse caso) com o
     * contrato fechado {@code {codigo:"SOLICITACAO_POS_PAGA"}}.
     */
    @ExceptionHandler(SolicitacaoPosPagaException.class)
    public ProblemDetail solicitacaoPosPaga(SolicitacaoPosPagaException ex) {
        var pd = ProblemDetail.forStatus(HttpStatus.GONE);
        pd.setDetail(ex.getMessage());
        pd.setProperty("codigo", "SOLICITACAO_POS_PAGA");
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException ex) {
        var pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setDetail("Validation failed");
        pd.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).toList());
        return pd;
    }

    /**
     * Catch-all para excecoes nao previstas (incluindo bugs de programacao genuinos).
     *
     * <p>Sem esse handler, qualquer excecao sem mapeamento especifico escapava do
     * DispatcherServlet e virava um 403 vazio, disfarcando um erro interno do servidor como
     * se fosse falta de permissao do cliente. A mensagem/stacktrace real nao e exposta na
     * resposta para nao vazar detalhe interno.</p>
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception ex) {
        // Sem este log o 5xx era totalmente cego em producao: o cliente recebe o problem-detail
        // generico e o stack real nunca aparecia em lugar nenhum (ver RELATORIO.md achado #7).
        log.error("500 nao tratado", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }
}
