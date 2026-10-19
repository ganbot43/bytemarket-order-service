package com.bytemarket.order.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * El historial del turno: cada cosa que se hizo, quién y cuándo.
 *
 * Los datos ya estaban, pero repartidos: quién asignó el fondo en una
 * columna de la sesión, quién abrió en otra, los arqueos en su tabla, las
 * ventas en la suya. Para responder «qué pasó en este turno» había que
 * juntar cuatro sitios y ordenarlos a mano, que es tanto como no tener
 * historial.
 *
 * Aquí va la línea de tiempo entera, en una tabla y en orden. Es lo que se
 * mira cuando el cuadre sale raro y hay que reconstruir la mañana.
 *
 * No sustituye a las otras tablas: el importe de un arqueo sigue en
 * `cash_audits` y el de una venta en `orders`. Esto es el índice, con lo
 * justo para leerlo de corrido.
 */
@Data
@Entity
@Table(name = "cash_logs", indexes = {
        @Index(name = "idx_historial_sesion", columnList = "session_id, created_at")
})
public class CashLog {

    /** El fondo quedó registrado a nombre de un cajero. */
    public static final String ASIGNADO = "assigned";
    /** El cajero ya puede abrir. */
    public static final String HABILITADO = "enabled";
    /** El cajero confirmó y aceptó el fondo. */
    public static final String ABIERTO = "opened";
    /** Una venta cobrada en este turno. */
    public static final String VENTA = "sale";
    /** Entró o salió efectivo que no es una venta. */
    public static final String MOVIMIENTO = "movement";
    /** Un arqueo sorpresivo. */
    public static final String ARQUEO = "audit";
    /** El cajero terminó su turno. */
    public static final String CERRADO = "closed";
    /** El encargado contó, firmó y cuadró. */
    public static final String CUADRADO = "settled";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "session_id", nullable = false)
    private Integer sessionId;

    @Column(length = 20, nullable = false)
    private String action;

    @Column(name = "user_id")
    private Long userId;

    /**
     * El nombre de quien lo hizo, copiado.
     *
     * Mismo criterio que en el resto del turno: es quien lo hizo ESE día.
     * Si mañana cambia de nombre, el historial de ayer sigue diciendo la
     * verdad.
     */
    @Column(name = "user_name", columnDefinition = "TEXT")
    private String userName;

    /** Qué pasó, en una línea legible. */
    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void alCrear() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
