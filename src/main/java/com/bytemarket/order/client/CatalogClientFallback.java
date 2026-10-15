package com.bytemarket.order.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Qué hacer cuando catalog-service no responde.
 *
 * La regla no es uniforme, y es a propósito:
 *
 *  · Lecturas del panel  → lista vacía. Un reporte sin la parte del catálogo
 *    sigue siendo útil, y es lo que ya hacían los try/catch.
 *
 *  · Precio, stock y descuentos → RELANZA. Es la decisión importante. Un
 *    fallback que devolviera algo "razonable" aquí sería mentir con dinero
 *    de por medio: si resolve() devolviera lista vacía, el carrito
 *    calcularía un subtotal de 0 y venderías gratis; si decrementStock()
 *    devolviera un ok falso, venderías stock inexistente y encima
 *    perderías el log.error que hoy avisa de que hay que ajustar a mano.
 *
 * El circuit breaker aquí NO sirve para disimular el fallo, sino para que
 * llegue de inmediato en vez de tras agotar el timeout, y para dejar de
 * malgastar hilos mientras catalog siga caído.
 */
@Component
public class CatalogClientFallback implements CatalogClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogClientFallback.class);

    /** El checkout no puede continuar sin precios reales del catálogo. */
    @Override
    public List<Map<String, Object>> resolve(List<Integer> productIds) {
        throw new CatalogNoDisponibleException("No pudimos verificar los productos. Inténtalo en un momento.");
    }

    /** Descontar stock no se puede fingir: quien llama necesita saber que falló. */
    @Override
    public Map<String, Object> decrementStock(Map<String, Object> body) {
        throw new CatalogNoDisponibleException("No se pudo descontar el stock");
    }

    @Override
    public Map<String, Object> restoreStock(Map<String, Object> body) {
        throw new CatalogNoDisponibleException("No se pudo devolver el stock");
    }

    /** El panel prefiere una tarjeta vacía a no cargar. */
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

    /** El backfill es una operación manual y puntual: mejor que avise. */
    @Override
    public Map<String, Object> backfillMovements(List<Map<String, Object>> entradas) {
        throw new CatalogNoDisponibleException("No se pudo registrar el kardex");
    }

    /** Se distingue del resto para que los try/catch existentes la reconozcan. */
    public static class CatalogNoDisponibleException extends RuntimeException {
        public CatalogNoDisponibleException(String mensaje) {
            super(mensaje);
        }
    }
}
