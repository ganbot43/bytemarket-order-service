package com.bytemarket.order.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Una línea del arqueo: lo que el sistema dice que entró por una forma de
 * pago frente a lo que el encargado contó de esa forma.
 *
 * Antes el arqueo era una sola cifra, el efectivo, y lo demás se mostraba
 * "de referencia". Eso deja fuera la mitad del día: si el sistema registra
 * 900 de Yape y en el teléfono del negocio hay 820, falta una venta que se
 * cobró mal o sobra una que se registró dos veces, y con un arqueo de solo
 * efectivo eso no aparece hasta que alguien revise el banco a fin de mes.
 *
 * Por eso cada forma se cuenta contra su propio comprobante:
 *
 *   efectivo  → el dinero del cajón, contado a mano
 *   tarjeta   → el cierre de lote del POS (Niubiz)
 *   yape/plin → los abonos recibidos en la cuenta del negocio
 *
 * Solo el efectivo arrastra el fondo inicial y los movimientos: es el único
 * que pasa por el cajón. En las demás, lo esperado son las ventas del turno
 * y nada más.
 *
 * Las tres cifras se congelan al cerrar y no se recalculan al leer: un
 * arqueo firmado no puede cambiar porque mañana se anule una venta de ese
 * turno.
 */
@Data
@Entity
@Table(name = "cash_counts", indexes = {
        @Index(name = "idx_arqueo_sesion", columnList = "session_id")
}, uniqueConstraints = {
        // Una línea por forma y turno: dos filas de "yape" en el mismo
        // arqueo serían dos verdades distintas sobre el mismo dinero.
        @UniqueConstraint(name = "uk_arqueo_sesion_forma", columnNames = {"session_id", "method"})
})
public class CashCount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private CashSession session;

    /** La clave de la forma de pago: efectivo, yape, plin, tarjeta… */
    @Column(length = 30, nullable = false)
    private String method;

    /** El nombre con el que se imprime. Copiado, igual que en el pedido. */
    @Column(columnDefinition = "TEXT")
    private String label;

    /** Lo que debería haber según el sistema. */
    @Column(nullable = false)
    private Double expected = 0d;

    /** Lo que el encargado contó. */
    @Column(nullable = false)
    private Double counted = 0d;

    /** Contado menos esperado: positivo sobra, negativo falta. */
    @Column(nullable = false)
    private Double difference = 0d;

    /** Cuántas ventas del turno fueron por esta forma. Para el papel. */
    @Column(name = "sales_count")
    private Integer salesCount = 0;
}
