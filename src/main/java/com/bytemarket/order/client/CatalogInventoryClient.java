package com.bytemarket.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * Escrituras sobre el inventario del catálogo.
 *
 * Solo queda el backfill del kardex. El descuento y la devolución de stock
 * viajan por RabbitMQ desde que existe StockPublisher, así que ya no se
 * llaman por aquí: tenerlos declarados obligaría a este fallback a decidir
 * qué hacer con métodos que nadie usa.
 *
 * Su fallback RELANZA: el backfill es una operación manual y puntual, y
 * terminar en silencio sería peor que fallar.
 */
@FeignClient(
        name = "bytemarket-catalog-service",
        contextId = "catalogInventory",
        path = "/api/internal/products",
        fallback = CatalogInventoryClientFallback.class)
public interface CatalogInventoryClient {

    /**
     * Aparta las unidades del pedido antes de guardarlo.
     *
     * Devuelve 409 si no alcanzan, y ese error tiene que llegar al cliente
     * tal cual: es la única respuesta honesta a "lo que querías ya no está".
     */
    @PostMapping("/stock-reserve")
    Map<String, Object> reserveStock(@RequestBody Map<String, Object> body);

    /** Devuelve lo apartado de un pedido que no salió adelante. */
    @PostMapping("/stock-release")
    Map<String, Object> releaseStock(@RequestBody Map<String, Object> body);

    @PostMapping("/movements/backfill")
    Map<String, Object> backfillMovements(@RequestBody List<Map<String, Object>> entradas);
}
