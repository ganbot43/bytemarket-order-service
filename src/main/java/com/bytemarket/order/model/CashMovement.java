package com.bytemarket.order.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Dinero que entra o sale del cajón sin ser una venta.
 *
 * Hace falta porque sin esto el cuadre sale mal siempre y nadie sabe por
 * qué. Durante un turno se saca dinero a la caja fuerte, se mete sencillo
 * para poder dar vuelto, y a veces se paga un taxi o un almuerzo con lo que
 * hay en el cajón. Todo eso mueve el efectivo y ninguna de las tres cosas
 * es un pedido.
 *
 * Cada uno lleva su motivo obligatorio: un retiro de 200 soles sin explicar
 * es indistinguible de un faltante.
 */
@Data
@Entity
@Table(name = "cash_movements", indexes = {
        @Index(name = "idx_mov_caja_sesion", columnList = "session_id")
})
public class CashMovement {

    /** Entra dinero que no es una venta: sencillo, reposición de fondo. */
    public static final String INGRESO = "in";
    /** Sale a la caja fuerte o al banco. Sigue siendo de la tienda. */
    public static final String RETIRO = "out";
    /** Sale y no vuelve: un gasto pagado con el efectivo del cajón. */
    public static final String GASTO = "expense";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private CashSession session;

    @Column(length = 20, nullable = false)
    private String type;

    /** Siempre positivo. El signo lo pone el tipo, no el número. */
    @Column(nullable = false)
    private Double amount;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String reason;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void alCrear() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    /** Cuánto mueve el cajón: los retiros y los gastos lo bajan. */
    public double delta() {
        double v = amount == null ? 0d : amount;
        return INGRESO.equals(type) ? v : -v;
    }
}
