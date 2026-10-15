package com.bytemarket.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * Precio y disponibilidad de los productos de un carrito.
 *
 * Es la única parte del catálogo de la que depende el checkout, y por eso
 * está sola en su interfaz: el precio que se cobra no lo decide el
 * navegador, se lee de aquí.
 *
 * Su fallback RELANZA, sin excepción. Un valor "razonable" aquí sería
 * mentir con dinero de por medio: una lista vacía daría un subtotal de 0.
 */
@FeignClient(
        name = "bytemarket-catalog-service",
        contextId = "catalogPricing",
        path = "/api/internal/products",
        fallback = CatalogPricingClientFallback.class)
public interface CatalogPricingClient {

    @PostMapping("/resolve")
    List<Map<String, Object>> resolve(@RequestBody List<Integer> productIds);
}
