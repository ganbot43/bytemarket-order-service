package com.bytemarket.order.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lo que debe pasar cuando catalog-service no responde.
 *
 * Antes era un solo cliente con seis métodos y un fallback que devolvía
 * lista vacía en unos y relanzaba en otros. Partido en tres, cada interfaz
 * tiene UNA regla, y eso es lo que se fija aquí: si alguien añade un método
 * a la interfaz equivocada, el fallback tendrá que mentir y este test
 * dejará de tener sentido.
 */
class CatalogFallbacksTest {

    @Test
    @DisplayName("precios: relanza, porque un subtotal inventado vendería gratis")
    void preciosRelanza() {
        CatalogPricingClient fallback = new CatalogPricingClientFallback();

        CatalogNoDisponibleException e = assertThrows(CatalogNoDisponibleException.class,
                () -> fallback.resolve(List.of(1, 2, 3)));
        assertFalse(e.getMessage().isBlank(), "el mensaje lo lee el cliente en el checkout");
    }

    @Test
    @DisplayName("inventario: relanza, porque un kardex a medias no avisa de que falta")
    void inventarioRelanza() {
        CatalogInventoryClient fallback = new CatalogInventoryClientFallback();

        assertThrows(CatalogNoDisponibleException.class,
                () -> fallback.backfillMovements(List.of(Map.of("orderId", 1, "productId", 7))));
    }

    @Test
    @DisplayName("informes: lista vacía, porque el panel pinta igual sin el catálogo")
    void informesDevuelveVacio() {
        CatalogReportingClient fallback = new CatalogReportingClientFallback();

        // lowStock devuelve {data, threshold}, no la lista pelada: el umbral
        // lo pone catalog y el panel necesita saber cuál aplicó. Caído el
        // catálogo no hay umbral que dar, así que va la lista vacía y nada
        // más, y el panel lo trata como desconocido en vez de inventarse un
        // número.
        Map<String, Object> informe = fallback.lowStock(null);
        assertEquals(List.of(), informe.get("data"));
        assertNull(informe.get("threshold"), "sin catálogo no hay umbral que afirmar");

        assertEquals(List.of(), fallback.exportProducts());
    }
}
