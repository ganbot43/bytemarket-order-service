package com.bytemarket.order.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Qué pasa si user-service no contesta: lo decide PermissionsCache. */
@Component
public class UserPermissionsClientFallback implements UserPermissionsClient {

    private static final Logger log = LoggerFactory.getLogger(UserPermissionsClientFallback.class);

    @Override
    public Map<String, Object> matriz() {
        log.warn("user-service no respondió a la matriz de permisos; se usará la copia en memoria");
        return Map.of();
    }
}
