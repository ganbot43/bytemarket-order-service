package com.bytemarket.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * API interna de catalog-service. El precio y el stock se leen de aquí, no
 * del cuerpo que manda el navegador.
 */
@FeignClient(name = "bytemarket-catalog-service", path = "/api/internal/products",
        fallback = CatalogClientFallback.class)
public interface CatalogClient {

    @PostMapping("/resolve")
    List<Map<String, Object>> resolve(@RequestBody List<Integer> productIds);

    @PostMapping("/stock-decrement")
    Map<String, Object> decrementStock(@RequestBody Map<String, Object> body);

    @PostMapping("/stock-restore")
    Map<String, Object> restoreStock(@RequestBody Map<String, Object> body);

    @GetMapping("/low-stock")
    List<Map<String, Object>> lowStock(@RequestParam("threshold") int threshold);

    @GetMapping("/export")
    List<Map<String, Object>> exportProducts();

    @PostMapping("/movements/backfill")
    Map<String, Object> backfillMovements(@RequestBody List<Map<String, Object>> entradas);
}
