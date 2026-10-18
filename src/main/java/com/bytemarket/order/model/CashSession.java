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
 * Y tiene DOS personas, que es la parte que al principio no estaba:
 * `cashierId` es de quién es el cajón, y `openedBy` quién lo aperturó y
 * entregó el fondo. Cuando eran la misma columna, el cajero se abría su
 * propia caja con el fondo que quisiera y la cerraba él solo declarando lo
 * que quisiera: el arqueo lo firmaba la misma persona a la que vigila, así
 * que no vigilaba nada. Separarlas es lo que convierte esto en un control
 * y no en un formulario.
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
        // La consulta de cada carga de la pantalla: "¿tengo caja abierta?",
        // que ahora se pregunta por el cajero y no por quien aperturó.
        @Index(name = "idx_caja_cajero_estado", columnList = "cashier_id, status"),
        @Index(name = "idx_caja_usuario_estado", columnList = "opened_by, status"),
        @Index(name = "idx_caja_estado_apertura", columnList = "status, opened_at")
})
public class CashSession {

    public static final String ABIERTA = "open";
    public static final String CERRADA = "closed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /**
     * De quién es este cajón: quien va a cobrar con él.
     *
     * Es el dato con el que la pantalla de caja encuentra su turno y con el
     * que cada venta se engancha. Nullable en la columna por las filas que
     * ya existían antes de separar los dos papeles —un arranque las
     * rellena con `opened_by`, que era lo que significaban entonces—, pero
     * de aquí en adelante siempre viene.
     */
    @Column(name = "cashier_id")
    private Long cashierId;

    /** El nombre del cajero, copiado al aperturar. Etiqueta, no permiso. */
    @Column(name = "cashier_name", columnDefinition = "TEXT")
    private String cashierName;

    /** Quién aperturó y entregó el fondo. El jefe de ventas o el encargado. */
    @Column(name = "opened_by", nullable = false)
    private Long openedBy;

    /**
     * El nombre de quien aperturó, copiado al aperturar.
     *
     * Se guarda y no se consulta a user-service al leer el turno: es quien
     * aperturó ESE día. Si mañana cambia de nombre, el arqueo de ayer sigue
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
     * Lo cuenta el encargado, no el cajero, y lo escribe ANTES de ver el
     * esperado. Al revés el arqueo no vale nada: quien ve la cifra objetivo
     * deja de contar y la copia.
     *
     * El desglose por forma de pago vive en {@link CashCount}; aquí queda
     * el efectivo, que es el único que pasó por el cajón.
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
