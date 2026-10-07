package br.com.vistoria.core.shared.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ApiExceptionHandler - Exception to HTTP Response Mapping")
class ApiExceptionHandlerTest {

    private ApiExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ApiExceptionHandler();
    }

    @Test
    @DisplayName("notFound maps NotFoundException to 404 NOT_FOUND")
    void notFound_mapsTo404() {
        NotFoundException ex = new NotFoundException("Resource not found");

        ProblemDetail result = handler.notFound(ex);

        assertThat(result.getStatus()).isEqualTo(404);
        assertThat(result.getDetail()).isEqualTo("Resource not found");
    }

    @Test
    @DisplayName("templateInvalido maps TemplateInvalidoException to 422 with the error list")
    void templateInvalido_mapsTo422WithErrors() {
        var ex = new br.com.vistoria.core.modules.pdftemplate.TemplateInvalidoException(
                java.util.List.of("campo obrigatorio ausente: nome", "chave desconhecida: 'x'"));

        ProblemDetail result = handler.templateInvalido(ex);

        assertThat(result.getStatus()).isEqualTo(422);
        assertThat(result.getProperties()).containsKey("errors");
        assertThat((java.util.List<?>) result.getProperties().get("errors")).hasSize(2);
    }

    @Test
    @DisplayName("illegalArgument maps IllegalArgumentException to 400 BAD_REQUEST")
    void illegalArgument_mapsTo400() {
        IllegalArgumentException ex = new IllegalArgumentException("Motivo obrigatorio");

        ProblemDetail result = handler.illegalArgument(ex);

        assertThat(result.getStatus()).isEqualTo(400);
        assertThat(result.getDetail()).isEqualTo("Motivo obrigatorio");
    }

    @Test
    @DisplayName("authenticationCredentialsNotFound maps to 401 UNAUTHORIZED with generic message")
    void authenticationCredentialsNotFound_mapsTo401WithGenericMessage() {
        AuthenticationCredentialsNotFoundException ex =
                new AuthenticationCredentialsNotFoundException("No token provided");

        ProblemDetail result = handler.authenticationCredentialsNotFound(ex);

        assertThat(result.getStatus()).isEqualTo(401);
        assertThat(result.getDetail()).isEqualTo("Authentication required");
    }

    @Test
    @DisplayName("accessDenied maps AccessDeniedException to 403 FORBIDDEN with generic message")
    void accessDenied_mapsTo403WithGenericMessage() {
        AccessDeniedException ex = new AccessDeniedException("User lacks permission");

        ProblemDetail result = handler.accessDenied(ex);

        assertThat(result.getStatus()).isEqualTo(403);
        assertThat(result.getDetail()).isEqualTo("Access denied");
    }

    @Test
    @DisplayName("notFound preserves detailed error message from exception")
    void notFound_preservesDetailedMessage() {
        String detailedMessage = "Order with id 999 not found in database";
        NotFoundException ex = new NotFoundException(detailedMessage);

        ProblemDetail result = handler.notFound(ex);

        assertThat(result.getDetail()).isEqualTo(detailedMessage);
    }

    @Test
    @DisplayName("illegalArgument preserves exception message detail")
    void illegalArgument_preservesExceptionMessage() {
        String exceptionMessage = "Email already exists in system";
        IllegalArgumentException ex = new IllegalArgumentException(exceptionMessage);

        ProblemDetail result = handler.illegalArgument(ex);

        assertThat(result.getDetail()).isEqualTo(exceptionMessage);
        assertThat(result.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("ApiExceptionHandler is marked with @RestControllerAdvice")
    void apiExceptionHandlerHasCorrectAnnotation() {
        RestControllerAdvice annotation = ApiExceptionHandler.class.getAnnotation(RestControllerAdvice.class);

        assertThat(annotation).isNotNull();
    }

    @Test
    @DisplayName("illegalArgument uses BAD_REQUEST — input validation is not an auth failure")
    void illegalArgument_usesBadRequestStatusCode() {
        IllegalArgumentException ex = new IllegalArgumentException("hora_fim deve ser depois de hora_inicio");

        ProblemDetail result = handler.illegalArgument(ex);

        assertThat(result.getStatus()).isEqualByComparingTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    @DisplayName("authentication maps BadCredentialsException to 401 without leaking which half failed")
    void authentication_mapsTo401WithGenericMessage() {
        BadCredentialsException ex = new BadCredentialsException("Credenciais invalidas para joao@exemplo.com");

        ProblemDetail result = handler.authentication(ex);

        assertThat(result.getStatus()).isEqualTo(401);
        // Mensagem generica e deliberada (ver Javadoc de ApiExceptionHandler#authentication):
        // nao distingue "email inexistente" de "senha errada" para quem enumera contas.
        assertThat(result.getDetail()).isEqualTo("Credencial invalida");
        assertThat(result.getDetail()).doesNotContain("joao@exemplo.com");
    }

    @Test
    @DisplayName("validation maps MethodArgumentNotValidException to 422 with per-field errors")
    void validation_mapsTo422WithFieldErrors() throws Exception {
        var target = new Object();
        BindingResult binding = new BeanPropertyBindingResult(target, "body");
        binding.addError(new FieldError("body", "email", "must not be blank"));
        var method = new MethodParameter(
                ApiExceptionHandlerTest.class.getDeclaredMethod("dummyHandlerMethod", String.class), 0);
        var ex = new MethodArgumentNotValidException(method, binding);

        ProblemDetail result = handler.validation(ex);

        assertThat(result.getStatus()).isEqualTo(422);
        assertThat(result.getDetail()).isEqualTo("Validation failed");
        assertThat(result.getProperties()).containsKey("errors");
        @SuppressWarnings("unchecked")
        java.util.List<String> errors = (java.util.List<String>) result.getProperties().get("errors");
        assertThat(errors).containsExactly("email: must not be blank");
    }

    @SuppressWarnings("unused")
    private void dummyHandlerMethod(String email) {
        // Apenas fornece um MethodParameter real para construir o MethodArgumentNotValidException.
    }

    @Test
    @DisplayName("accessDenied uses FORBIDDEN status code for authorization failures")
    void accessDenied_usesForbiddenStatusCode() {
        AccessDeniedException ex = new AccessDeniedException("Not authorized");

        ProblemDetail result = handler.accessDenied(ex);

        assertThat(result.getStatus()).isEqualByComparingTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    @DisplayName("notFound uses NOT_FOUND status code")
    void notFound_usesNotFoundStatusCode() {
        NotFoundException ex = new NotFoundException("Item missing");

        ProblemDetail result = handler.notFound(ex);

        assertThat(result.getStatus()).isEqualByComparingTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    @DisplayName("authenticationCredentialsNotFound returns generic message for security")
    void authenticationCredentialsNotFound_doesNotExposeDetails() {
        String originalMessage = "Very sensitive internal error: JWT validation failed against key X";
        AuthenticationCredentialsNotFoundException ex =
                new AuthenticationCredentialsNotFoundException(originalMessage);

        ProblemDetail result = handler.authenticationCredentialsNotFound(ex);

        // Should not expose the original message
        assertThat(result.getDetail()).doesNotContain("sensitive");
        assertThat(result.getDetail()).isEqualTo("Authentication required");
    }

    @Test
    @DisplayName("accessDenied returns generic message for security")
    void accessDenied_doesNotExposeDetails() {
        String originalMessage = "Role ADMIN required but user has CLIENTE";
        AccessDeniedException ex = new AccessDeniedException(originalMessage);

        ProblemDetail result = handler.accessDenied(ex);

        // Should not expose the original message details
        assertThat(result.getDetail()).isEqualTo("Access denied");
    }

    @Test
    @DisplayName("dataIntegrity maps DataIntegrityViolationException to 409 without leaking SQL/constraint detail")
    void dataIntegrity_mapsTo409Generic() {
        var ex = new org.springframework.dao.DataIntegrityViolationException(
                "ERROR: update or delete on table \"cidades\" violates foreign key constraint \"imoveis_cidade_id_fkey\"");

        ProblemDetail result = handler.dataIntegrity(ex);

        assertThat(result.getStatus()).isEqualByComparingTo(HttpStatus.CONFLICT.value());
        assertThat(result.getDetail()).doesNotContain("fkey").doesNotContain("cidades");
    }
}
