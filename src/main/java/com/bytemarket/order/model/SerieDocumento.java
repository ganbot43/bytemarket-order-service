package com.bytemarket.order.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * El contador de una serie de documentos.
 *
 * Son dos: las boletas que se lleva el cliente (B001) y los vouchers de
 * comercio que se queda el cajero (V001). Comparten mecanismo porque
 * comparten el problema —un correlativo que no se repite— y tenerlo en un
 * sitio evita que el segundo se implemente peor que el primero.
 *
 * Una boleta se identifica por su serie y su número correlativo —B001 y
 * 00000123— y el correlativo no puede repetirse ni saltarse a la ligera:
 * es lo que convierte un papel en un comprobante y no en un recibo
 * cualquiera.
 *
 * Por eso el siguiente número no se calcula con un MAX(numero)+1 sobre las
 * ventas. Dos cajeros cobrando a la vez leerían el mismo máximo y
 * emitirían dos boletas con el mismo número: el error clásico de mirar y
 * luego escribir en dos pasos. Aquí el contador vive en su propia fila y se
 * reserva con la fila bloqueada, así que las dos ventas se ponen en cola y
 * cada una se lleva el suyo.
 *
 * Una fila por serie. En una tienda con varias cajas, cada punto de venta
 * suele tener las suyas para que dos cajeros no se peleen por el mismo
 * contador; aquí hay una de cada y se configuran con
 * `bytemarket.boleta.serie` y `bytemarket.voucher.serie`.
 */
@Data
@Entity
@Table(name = "document_series")
public class SerieDocumento {

    /** La serie: cuatro caracteres, "B001" para boletas y "V001" para vouchers. */
    @Id
    @Column(length = 10, nullable = false)
    private String serie;

    /**
     * El último número emitido. El siguiente es este más uno.
     *
     * Empieza en 0 porque la primera boleta es la 1; guardar "el siguiente"
     * en vez de "el último" obliga a inicializarlo a 1 y a acordarse de por
     * qué cada vez que se lee.
     */
    @Column(name = "last_number", nullable = false)
    private Integer lastNumber = 0;
}
