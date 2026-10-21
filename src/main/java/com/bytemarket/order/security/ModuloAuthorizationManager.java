package com.bytemarket.order.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * Autoriza /api/admin/** consultando la matriz de permisos.
 *
 * Antes eran reglas hasAnyRole aquí mismo, así que quitarle un módulo a
 * alguien desde el panel escondía el menú pero no cerraba la API: bastaba un
 * curl al 8082, que está publicado en el compose. La pantalla de permisos
 * prometía algo que este servicio no cumplía.
 *
 * Deniega por omisión: una ruta de /api/admin que no esté en RutasDeModulo
 * no pasa. Si mañana se añade un controlador y nadie lo declara, quedarse
 * fuera se nota en la primera prueba; pasar sin comprobar nada no se nota
 * hasta que es tarde.
 */
@Component
@RequiredArgsConstructor
public class ModuloAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private static final String GERENTE = "superadmin";

    /** Los roles de panel, de mayor a menor: gana el más alto que traiga. */
    private static final List<String> PIRAMIDE =
            List.of(GERENTE, "admin", "jefe_ventas", "cajero");

    private final PermissionsCache cache;

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication,
                                       RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated()) return new AuthorizationDecision(false);

        List<String> authorities = auth.getAuthorities().stream().map(Object::toString).toList();

        String rol = PIRAMIDE.stream()
                .filter(r -> authorities.contains("ROLE_" + r.toUpperCase()))
                .findFirst().orElse(null);
        if (rol == null) return new AuthorizationDecision(false);

        // El gerente lo puede todo por código, no por datos.
        if (GERENTE.equals(rol)) return new AuthorizationDecision(true);

        List<String> modulos = RutasDeModulo.modulos(context.getRequest());
        if (modulos.isEmpty()) return new AuthorizationDecision(false);

        boolean escribe = RutasDeModulo.escribe(context.getRequest());
        boolean ok = modulos.stream().anyMatch(m -> {
            var p = cache.permiso(rol, m);
            return escribe ? p.editar() : p.ver();
        });
        return new AuthorizationDecision(ok);
    }
}
