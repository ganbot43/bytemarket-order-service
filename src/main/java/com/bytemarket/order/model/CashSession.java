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

    /**
     * Los cinco estados del turno, en el orden en que ocurren.
     *
     *   fund_assigned  el jefe registró el fondo, el cajero aún no puede abrir
     *   enabled        habilitado: el cajero ya puede confirmar la apertura
     *   open           turno en curso; admite ventas y arqueos sorpresivos
     *   closed         el cajero cerró; no admite más ventas
     *   settled        el jefe contó y firmó el papel de caja
     *
     * Que sean cinco y no dos es lo que separa «dejo de vender» de «me
     * cuentan el dinero». El cajero cierra su caja cuando acaba el turno; el
     * cuadre es después, delante del encargado, y hasta que no ocurre el
     * turno no está terminado. Con dos estados las dos cosas eran la misma,
     * y entonces o el cajero declaraba su propio dinero o el jefe le cerraba
     * la caja desde otra pantalla sin que él lo supiera.
     *
     * Asignar fondo y habilitar son dos estados pero una sola acción: el
     * jefe entrega el dinero y con eso queda habilitado. Se guardan
     * separados porque un día puede hacer falta preparar los fondos por la
     * mañana y habilitar al abrir la tienda.
     */
    public static final String FONDO_ASIGNADO = "fund_assigned";
    public static final String HABILITADA = "enabled";
    public static final String ABIERTA = "open";
    public static final String CERRADA = "closed";
    public static final String CUADRADA = "settled";

    /** Los estados en los que el turno sigue vivo y no se puede empezar otro. */
    public static final java.util.List<String> VIVOS =
            java.util.List.of(FONDO_ASIGNADO, HABILITADA, ABIERTA, CERRADA);

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

    /** El nombre del cajero, copiado al asignar el fondo. Etiqueta, no permiso. */
    @Column(name = "cashier_name", columnDefinition = "TEXT")
    private String cashierName;

    /**
     * El DNI del cajero, copiado igual que el nombre.
     *
     * Va impreso en el ticket de apertura y en el papel de caja: un papel
     * firmado que solo dice «Cajero» no identifica a nadie cuando hay dos
     * con el mismo nombre de pila.
     */
    @Column(name = "cashier_dni", length = 20)
    private String cashierDni;

    /** Quién asignó el fondo y habilitó al cajero: el jefe o el encargado. */
    @Column(name = "assigned_by")
    private Long assignedBy;

    @Column(name = "assigned_by_name", columnDefinition = "TEXT")
    private String assignedByName;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "enabled_at")
    private LocalDateTime enabledAt;

    /**
     * Quién confirmó la apertura: el propio cajero.
     *
     * Antes era el jefe, porque era él quien abría. Ahora el jefe entrega el
     * fondo y el cajero lo acepta: esa confirmación es la que firma en el
     * ticket de apertura, y es lo que convierte el fondo en responsabilidad
     * suya.
     */
    @Column(name = "opened_by")
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

    @Column(name = "opened_at")
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

    /**
     * La observación del cuadre.
     *
     * Obligatoria cuando hay diferencia, en efectivo o en vouchers: un
     * faltante sin explicar es un faltante que nadie va a poder reconstruir
     * dentro de un mes.
     */
    @Column(name = "closing_notes", columnDefinition = "TEXT")
    private String closingNotes;

    /** Quién cuadró y cuándo: el jefe de ventas o el encargado. */
    @Column(name = "settled_by")
    private Long settledBy;

    @Column(name = "settled_by_name", columnDefinition = "TEXT")
    private String settledByName;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(length = 20, nullable = false)
    private String status = FONDO_ASIGNADO;
}
