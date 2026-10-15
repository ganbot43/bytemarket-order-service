package com.bytemarket.order.client;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** El checkout no puede continuar sin precios reales del catálogo. */
@Component
public class CatalogPricingClientFallback implements CatalogPricingClient {

    @Override
    public List<Map<String, Object>> resolve(List<Integer> productIds) {
        throw new CatalogNoDisponibleException(
                "No pudimos verificar los productos. Inténtalo en un momento.");
    }
}
