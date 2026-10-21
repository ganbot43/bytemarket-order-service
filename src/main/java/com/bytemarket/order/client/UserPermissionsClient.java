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
 * NO tiene fallback a propósito. Con uno que devolvía un mapa vacío, "no
 * contestó" y "contestó que nadie puede nada" llegaban idénticos a
 * PermissionsCache, que entonces no podía distinguir un fallo de red de una
 * respuesta autoritativa. Dejando que la llamada lance, el fallo es una
 * excepción y una respuesta vacía es una respuesta.
 */
@FeignClient(
        name = "bytemarket-user-service",
        contextId = "userPermissions",
        path = "/api/internal")
public interface UserPermissionsClient {

    @GetMapping("/permissions")
    Map<String, Object> matriz();
}
