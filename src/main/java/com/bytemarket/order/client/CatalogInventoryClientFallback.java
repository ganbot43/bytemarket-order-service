package com.bytemarket.order.client;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Reconstruir el kardex a medias es peor que no reconstruirlo. */
@Component
public class CatalogInventoryClientFallback implements CatalogInventoryClient {

    /**
     * Sin catálogo no se puede apartar nada, y sin apartar no se puede
     * vender: relanza, igual que hace el fallback de precios. Dejar pasar el
     * pedido aquí sería prometer unidades que nadie ha comprobado.
     */
    @Override
    public Map<String, Object> reserveStock(Map<String, Object> body) {
        throw new CatalogNoDisponibleException(
                "No pudimos comprobar el stock. Inténtalo en un momento.");
    }

    /**
     * Liberar sí puede fallar en silencio: las reservas caducan solas a los
     * quince minutos, así que lo peor que pasa es que el producto figure
     * agotado un rato de más.
     */
    @Override
    public Map<String, Object> releaseStock(Map<String, Object> body) {
        return Map.of("released", 0);
    }

    @Override
    public Map<String, Object> backfillMovements(List<Map<String, Object>> entradas) {
        throw new CatalogNoDisponibleException("No se pudo registrar el kardex");
    }
}
