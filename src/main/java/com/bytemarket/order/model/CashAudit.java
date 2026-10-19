package com.bytemarket.order.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Un arqueo sorpresivo: el encargado cuenta el cajón en mitad del turno.
 *
 * No cierra la caja ni la toca de ninguna forma. El cajero sigue vendiendo
 * en cuanto termina, y eso es justamente el punto: si para contar hubiera
 * que cerrar el turno, nadie lo haría a media mañana y dejaría de ser
 * sorpresivo.
 *
 * Cada uno queda guardado entero —quién contó, cuándo, qué esperaba, qué
 * encontró y qué anotó—. Un arqueo que no se guarda es una conversación, y
 * al mes siguiente nadie puede decir si se hizo.
 *
 * El esperado aquí SÍ se le enseña a quien cuenta, al revés que en el
 * cuadre del cierre: el encargado mira en su pantalla cuánto ha vendido el
 * cajero en efectivo y con esa cifra se pone a contar billetes. Quien
 * vigila es él; a quien vigila es al cajero, que no ve esta pantalla.
 */
@Data
@Entity
@Table(name = "cash_audits", indexes = {
        @Index(name = "idx_arqueo_sorpresa_sesion", columnList = "session_id")
})
public class CashAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private CashSession session;

    /** Quién lo hizo: el jefe de ventas o el encargado. */
    @Column(name = "performed_by")
    private Long performedBy;

    @Column(name = "performed_by_name", columnDefinition = "TEXT")
    private String performedByName;

    /** Lo que el sistema decía que debía haber en el cajón en ese momento. */
    @Column(name = "expected_cash", nullable = false)
    private Double expectedCash = 0d;

    /** Lo que se contó. */
    @Column(name = "counted_cash", nullable = false)
    private Double countedCash = 0d;

    /** Contado menos esperado: positivo sobra, negativo falta. */
    @Column(nullable = false)
    private Double difference = 0d;

    /** Obligatoria si hay diferencia. */
    @Column(columnDefinition = "TEXT")
    private String observation;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void alCrear() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
