package com.bytemarket.order.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Un turno de caja: desde que alguien la abre con su fondo hasta que la
 * cuadra y la cierra.
 *
 * Sin esto, la pantalla de caja registraba ventas sueltas. Sabía quién
 * cobró y cuánto efectivo recibió, pero nadie podía responder la pregunta
 * que de verdad importa al final del día: **cuánto debería haber en el
 * cajón**. Un pedido pertenece a un turno, y esa pertenencia es lo que hace
 * el cuadre posible.
 *
 * Es por persona, no por local. Encaja con que el comprobante ya imprime
 * quién atendió, y hace que un faltante tenga dueño: con una caja
 * compartida, el descuadre aparece y no hay a quién preguntar.
 *
 * Solo el EFECTIVO entra en el cuadre. Yape, tarjeta y transferencia se
 * cuentan aparte como referencia, pero ese dinero nunca pasó por el cajón:
 * meterlo en el esperado es el error más común al montar esto, y hace que
 * la caja descuadre siempre por la misma cantidad sin que nadie entienda
 * por qué.
 */
@Data
@Entity
@Table(name = "cash_sessions", indexes = {
        // La consulta de cada carga de la pantalla: "¿tengo caja abierta?"
        @Index(name = "idx_caja_usuario_estado", columnList = "opened_by, status"),
        @Index(name = "idx_caja_estado_apertura", columnList = "status, opened_at")
})
public class CashSession {

    public static final String ABIERTA = "open";
    public static final String CERRADA = "closed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "opened_by", nullable = false)
    private Long openedBy;

    /**
     * El nombre de quien abrió, copiado al abrir.
     *
     * Se guarda y no se consulta a user-service al leer el turno: es quien
     * abrió ESE día. Si mañana cambia de nombre, el arqueo de ayer sigue
     * diciendo la verdad. Mismo criterio que el correo del pedido.
     */
    @Column(name = "opened_by_name", columnDefinition = "TEXT")
    private String openedByName;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    /** El sencillo con el que se empieza, para poder dar vuelto. */
    @Column(name = "opening_float", nullable = false)
    private Double openingFloat = 0d;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /**
     * Lo que se contó físicamente al cerrar.
     *
     * Lo escribe la persona ANTES de ver el esperado. Al revés el arqueo no
     * vale nada: quien ve la cifra objetivo deja de contar y la copia.
     */
    @Column(name = "counted_cash")
    private Double countedCash;

    /** Lo que debería haber, calculado al cerrar y congelado aquí. */
    @Column(name = "expected_cash")
    private Double expectedCash;

    /** Contado menos esperado: positivo sobra, negativo falta. */
    @Column(name = "difference")
    private Double difference;

    @Column(name = "closing_notes", columnDefinition = "TEXT")
    private String closingNotes;

    @Column(length = 20, nullable = false)
    private String status = ABIERTA;
}
