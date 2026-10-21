package com.bytemarket.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

/**
 * La matriz de permisos, que vive en user-service.
 *
 * Se consulta en vez de llevarla en el JWT porque el gerente la cambia en
 * caliente: con los permisos dentro del token, quitarle un módulo a alguien
 * no haría efecto hasta que cerrara sesión.
 *
 * Su fallback NO relanza: devuelve vacío y PermissionsCache decide qué hacer,
 * porque es quien tiene el contexto para decidirlo.
 */
@FeignClient(
        name = "bytemarket-user-service",
        contextId = "userPermissions",
        path = "/api/internal",
        fallback = UserPermissionsClientFallback.class)
public interface UserPermissionsClient {

    @GetMapping("/permissions")
    Map<String, Object> matriz();
}
