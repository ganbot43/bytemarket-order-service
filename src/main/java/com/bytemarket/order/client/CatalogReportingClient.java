package com.bytemarket.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * Lecturas del catálogo que alimentan el panel: stock bajo y exportación.
 *
 * Separadas del precio porque su regla ante un fallo es la contraria: aquí
 * una lista vacía es una respuesta aceptable —un informe sin la parte del
 * catálogo sigue sirviendo— y además se les da menos timeout, porque
 * esperar ocho segundos para pintar una tabla vacía no tiene sentido.
 */
@FeignClient(
        name = "bytemarket-catalog-service",
        contextId = "catalogReporting",
        path = "/api/internal/products",
        fallback = CatalogReportingClientFallback.class)
public interface CatalogReportingClient {

    /**
     * Stock bajo del catálogo. El umbral va como Integer y no como int
     * porque null significa "aplica el tuyo": la regla de cuándo un stock
     * es bajo es de catalog, que es quien tiene los productos, y duplicarla
     * aquí dejaba al dashboard contando con un número distinto del de la
     * pantalla de Inventario.
     */
    @GetMapping("/low-stock")
    Map<String, Object> lowStock(@RequestParam(name = "threshold", required = false) Integer threshold);

    @GetMapping("/export")
    List<Map<String, Object>> exportProducts();
}
