package com.bytemarket.order.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * El panel prefiere una tarjeta vacía a no cargar.
 *
 * Las dos devuelven lista vacía, que es la misma regla para toda la
 * interfaz: por eso estos dos métodos están juntos y separados del resto.
 */
@Component
public class CatalogReportingClientFallback implements CatalogReportingClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogReportingClientFallback.class);

    @Override
    public List<Map<String, Object>> lowStock(int threshold) {
        log.warn("catalog-service no responde: el stock bajo se muestra vacío");
        return List.of();
    }

    @Override
    public List<Map<String, Object>> exportProducts() {
        log.warn("catalog-service no responde: la exportación va sin la parte del catálogo");
        return List.of();
    }
}
