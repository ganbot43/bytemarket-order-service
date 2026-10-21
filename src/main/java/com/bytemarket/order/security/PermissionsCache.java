package com.bytemarket.order.security;

import com.bytemarket.order.client.UserPermissionsClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * La matriz de permisos de user-service, cacheada.
 *
 * Se refresca cada 30 segundos y no en cada petición: el panel pega muchas
 * veces seguidas y la matriz cambia cuando el gerente la toca, que no es
 * cada segundo. Treinta segundos es el retraso máximo entre que se guarda un
 * permiso y este servicio lo aplica, y es un retraso que se puede explicar.
 *
 * ── Qué pasa si user-service no contesta ──
 *
 * Hay dos situaciones y no se tratan igual, porque la segura es distinta en
 * cada una:
 *
 *   Ya se leyó la matriz antes → se sigue usando la ÚLTIMA COPIA, por vieja
 *   que sea. Volver a los valores de fábrica sería devolver permisos que el
 *   gerente había quitado, y una caída de user-service no puede deshacer una
 *   revocación.
 *
 *   Nunca se leyó (arranque en frío con user-service caído) → se DENIEGA todo
 *   salvo al gerente, que lo puede por código. Volver a unos valores de
 *   fábrica era lo primero que hice aquí y está mal: devolvería permisos que
 *   el gerente había quitado, y no hace falta un ataque para llegar a ese
 *   estado —en un despliegue este servicio puede arrancar antes de que
 *   user-service responda—. Además user-service ES el servicio de
 *   autenticación: si no contesta, nadie puede iniciar sesión, así que
 *   restaurar permisos revocados justo entonces es el peor de los dos males.
 *
 * Denegar no cuesta nada en el caso normal: la primera petición dispara la
 * lectura, y si user-service está arriba no hay ventana ninguna.
 */
@Component
@RequiredArgsConstructor
public class PermissionsCache {

    private static final Logger log = LoggerFactory.getLogger(PermissionsCache.class);

    private static final long TTL_MS = 30_000L;

    private final UserPermissionsClient client;

    /** rol → módulo → {ver, editar}. null mientras no se haya leído nunca. */
    private volatile Map<String, Map<String, Permiso>> matriz;
    private volatile long caduca;

    public record Permiso(boolean ver, boolean editar) {
        public static final Permiso NADA = new Permiso(false, false);
    }

    public Permiso permiso(String rol, String modulo) {
        Map<String, Map<String, Permiso>> actual = vigente();
        return actual.getOrDefault(rol, Map.of()).getOrDefault(modulo, Permiso.NADA);
    }

    private Map<String, Map<String, Permiso>> vigente() {
        long ahora = System.currentTimeMillis();
        if (matriz != null && ahora < caduca) return matriz;

        try {
            Map<String, Object> respuesta = client.matriz();
            Map<String, Map<String, Permiso>> leida = interpretar(respuesta);
            if (!leida.isEmpty()) {
                matriz = leida;
                caduca = ahora + TTL_MS;
                return matriz;
            }
        } catch (Exception e) {
            log.warn("No se pudo refrescar la matriz de permisos: {}", e.getMessage());
        }

        if (matriz != null) {
            /* Se conserva la copia vieja y se reintenta al siguiente TTL. No
               se extiende la caducidad: si user-service vuelve, se quiere
               notar enseguida. */
            caduca = ahora + TTL_MS;
            return matriz;
        }

        log.error("Sin matriz de permisos y sin copia previa: se deniega /api/admin "
                + "hasta que user-service responda");
        return Map.of();
    }

    private static Map<String, Map<String, Permiso>> interpretar(Map<String, Object> respuesta) {
        Object cruda = respuesta == null ? null : respuesta.get("matrix");
        if (!(cruda instanceof Map<?, ?> porRol)) return Map.of();

        Map<String, Map<String, Permiso>> out = new LinkedHashMap<>();
        for (var entradaRol : porRol.entrySet()) {
            if (!(entradaRol.getValue() instanceof Map<?, ?> porModulo)) continue;
            Map<String, Permiso> permisos = new LinkedHashMap<>();
            for (var entradaModulo : porModulo.entrySet()) {
                if (!(entradaModulo.getValue() instanceof Map<?, ?> flags)) continue;
                permisos.put(String.valueOf(entradaModulo.getKey()), new Permiso(
                        esSi(flags.get("view")), esSi(flags.get("edit"))));
            }
            out.put(String.valueOf(entradaRol.getKey()), Map.copyOf(permisos));
        }
        return Map.copyOf(out);
    }

    private static boolean esSi(Object v) {
        return v instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(v));
    }
}
