package com.bytemarket.order.client;

/**
 * Catalog-service no respondió y la operación no se puede fingir.
 *
 * La lanzan los fallbacks de precios e inventario. Estaba declarada dentro
 * de CatalogClientFallback, pero al partir ese cliente en tres la comparten
 * dos fallbacks distintos, así que vive aparte.
 */
public class CatalogNoDisponibleException extends RuntimeException {

    public CatalogNoDisponibleException(String mensaje) {
        super(mensaje);
    }
}
