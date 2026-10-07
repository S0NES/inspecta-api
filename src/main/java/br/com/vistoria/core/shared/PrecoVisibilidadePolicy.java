package br.com.vistoria.core.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Decisão de negócio: o vistoriador NÃO vê o preço cobrado do cliente (só o repasse dele).
 *
 * <p>Para reverter, basta {@code inspecta.visibilidade.vistoriador-ve-preco-cobrado=true}
 * (env {@code VISTORIADOR_VE_PRECO_COBRADO=true}) — todos os pontos que expõem preço ao perfil
 * vistoriador consultam este componente, então não há mais nada a mudar no código.</p>
 *
 * <p>"Vistoriador" = principal cujas únicas authorities são {@code ROLE_VISTORIADOR}; ADMIN,
 * SUPORTE e perfis de imobiliária continuam vendo o preço.</p>
 */
@Component
public class PrecoVisibilidadePolicy {

    private final boolean vistoriadorVePrecoCobrado;

    public PrecoVisibilidadePolicy(
            @Value("${inspecta.visibilidade.vistoriador-ve-preco-cobrado:false}") boolean vistoriadorVePrecoCobrado) {
        this.vistoriadorVePrecoCobrado = vistoriadorVePrecoCobrado;
    }

    /** {@code true} quando o preço cobrado deve ser omitido para o usuário autenticado. */
    public boolean ocultarPrecoCobrado() {
        if (vistoriadorVePrecoCobrado) return false;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().isEmpty()) return false;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .allMatch("ROLE_VISTORIADOR"::equals);
    }
}
