package br.com.vistoria.core.auth;

/**
 * Regra de senha forte aplicada sempre que uma senha nova e definida (aceite de convite,
 * redefinicao via "esqueci minha senha"). Nao se aplica ao login — uma senha antiga criada
 * antes desta regra existir continua valida para autenticar.
 */
public final class PasswordPolicy {
    private PasswordPolicy() {}

    public static void validar(String senha) {
        if (senha == null || senha.length() < 5 || senha.length() > 50)
            throw new IllegalArgumentException("A senha deve ter entre 5 e 50 caracteres");
        if (!senha.matches(".*[A-Z].*"))
            throw new IllegalArgumentException("A senha deve conter ao menos uma letra maiuscula");
        if (!senha.matches(".*[a-z].*"))
            throw new IllegalArgumentException("A senha deve conter ao menos uma letra minuscula");
        if (!senha.matches(".*[^A-Za-z0-9].*"))
            throw new IllegalArgumentException("A senha deve conter ao menos um caractere especial");
    }
}
