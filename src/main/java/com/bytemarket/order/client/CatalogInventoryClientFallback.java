package com.bytemarket.order.client;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Reconstruir el kardex a medias es peor que no reconstruirlo. */
@Component
public class CatalogInventoryClientFallback implements CatalogInventoryClient {

    @Override
    public Map<String, Object> backfillMovements(List<Map<String, Object>> entradas) {
        throw new CatalogNoDisponibleException("No se pudo registrar el kardex");
    }
}
