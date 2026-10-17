package com.bytemarket.order.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * El día del negocio, que es el de Lima y no el del contenedor.
 *
 * Los contenedores corren en UTC, así que `created_at` guarda hora UTC y
 * `LocalDate.now()` devuelve el día UTC. Para una tienda en Perú eso
 * significa que todo lo vendido a partir de las 19:00 cae en el día
 * siguiente: a las ocho de la tarde el panel decía "hoy: S/ 0" con la caja
 * llena, y el cierre del día contaba las ventas de la noche anterior.
 *
 * No se arregla cambiando la zona del contenedor: los pedidos que ya están
 * guardados se escribieron en UTC, y reinterpretarlos como hora de Lima
 * movería cinco horas todo el histórico. Lo que se corrige es el corte: se
 * decide qué día es en Lima y se traducen sus bordes a UTC, que es como
 * están guardados.
 *
 * El desfase es fijo, -05:00 todo el año: Perú no tiene horario de verano
 * desde 1994. Aun así se usa la zona con nombre y no el número, porque si
 * algún día vuelve a tenerlo, esto seguiría siendo cierto.
 */
public final class HoraDeLima {

    public static final ZoneId ZONA = ZoneId.of("America/Lima");

    private HoraDeLima() {
    }

    /** Qué día es ahora en la tienda. */
    public static LocalDate hoy() {
        return LocalDate.now(ZONA);
    }

    /** El instante actual, en la escala en la que están guardadas las fechas. */
    public static LocalDateTime ahora() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Las 00:00 de ese día en Lima, expresadas en UTC.
     *
     * Es el borde que hay que pasarle a las consultas: comparar contra
     * `created_at`, que es UTC, con una medianoche de Lima sin traducir es
     * justo el error que esto arregla.
     */
    public static LocalDateTime inicioDe(LocalDate dia) {
        return dia.atStartOfDay(ZONA).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** El borde superior de ese día: las 00:00 del siguiente, en UTC. */
    public static LocalDateTime finDe(LocalDate dia) {
        return inicioDe(dia.plusDays(1));
    }
}
