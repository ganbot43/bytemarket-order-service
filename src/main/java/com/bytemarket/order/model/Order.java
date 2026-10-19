package com.bytemarket.order.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
/*
 * Índices de lo que el panel pregunta todo el rato.
 *
 * La tabla no tenía ninguno aparte de la clave y el único de order_code, y
 * todas las consultas del panel filtran u ordenan por estas tres columnas:
 * la lista por estado, el resumen y los reportes por fecha, y el dinero por
 * estado de pago. Con treinta pedidos no se nota; es a partir de unos miles
 * cuando cada carga del panel pasa a recorrer la tabla entera.
 *
 * Hibernate los crea solo con ddl-auto: update, así que no hace falta
 * migración.
 */
@Table(name = "orders", indexes = {
        // El orden de las columnas importa: se filtra por estado y se
        // ordena por fecha, en ese orden, así que el índice sirve para las
        // dos cosas de una pasada.
        @Index(name = "idx_orders_estado_fecha", columnList = "status, created_at"),
        // Los reportes recorren rangos de fecha sin filtrar por estado.
        @Index(name = "idx_orders_fecha", columnList = "created_at"),
        // Y el dinero cobrado filtra por los dos a la vez.
        @Index(name = "idx_orders_pago_estado", columnList = "payment_status, status")
})
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "order_code", length = 191, nullable = false, unique = true)
    private String orderCode;

    @Column(name="user_id")
    private Long userId;

    @Column(name = "payment_method_id")
    private Integer paymentMethodId;

    @Column(name = "payment_method_type", columnDefinition = "TEXT")
    private String paymentMethodType;

    @Column(name = "payment_method_label", columnDefinition = "TEXT")
    private String paymentMethodLabel;

    @Column(name = "payment_method_qr_url", columnDefinition = "TEXT")
    private String paymentMethodQrUrl;

    @Column(name = "payment_method_account_number", columnDefinition = "TEXT")
    private String paymentMethodAccountNumber;

    @Column(name = "payment_method_account_name", columnDefinition = "TEXT")
    private String paymentMethodAccountName;

    @Column(name = "customer_name", nullable = false, columnDefinition = "TEXT")
    private String customerName;

    @Column(name = "customer_phone", columnDefinition = "TEXT")
    private String customerPhone;

    /**
     * Para avisar al cliente del avance de su pedido.
     *
     * No lo escribe el formulario de checkout: se copia del JWT al crear el
     * pedido, que es la misma cuenta con la que el cliente entró. Pedirlo
     * otra vez en el checkout sería dejar que escribiera uno distinto del de
     * su cuenta, y entonces el aviso iría a donde no debe.
     *
     * Queda guardado en el pedido, y no se consulta a user-service al
     * enviarlo, porque es el correo al que se prometió avisar ESE día: si
     * mañana cambia el de su cuenta, el histórico sigue siendo cierto.
     */
    @Column(name = "customer_email", columnDefinition = "TEXT")
    private String customerEmail;

    @Column(name = "customer_address", columnDefinition = "TEXT")
    private String customerAddress;

    @Column(name = "customer_reference", columnDefinition = "TEXT")
    private String customerReference;

    @Column(name = "customer_notes", columnDefinition = "TEXT")
    private String customerNotes;

    @Column(nullable = false)
    private Double subtotal;

    @Column(nullable = false)
    private Double total;

    @Column(name = "coupon_code", length = 50)
    private String couponCode;

    @Column(name = "discount_applied")
    private Double discountApplied = 0.0;

    @Column(name = "voucher_url", columnDefinition = "TEXT")
    private String voucherUrl;

    /**
     * El número de operación del voucher, en las ventas de mostrador que no
     * son en efectivo.
     *
     * Es lo que permite contrastar el papel con el sistema al cuadrar: el
     * encargado tiene quince vouchers en la mano y el sistema dice quince
     * ventas con tarjeta. Sin el número, cuadrar solo el importe deja
     * pasar una venta cobrada dos veces por el mismo total.
     *
     * Distinto de `voucherUrl`, que es la foto del pago que sube el cliente
     * en la tienda web.
     */
    @Column(name = "voucher_number", length = 60)
    private String voucherNumber;

    /**
     * La boleta de venta: serie y correlativo.
     *
     * Es el comprobante que se lleva el cliente, y va en la venta y no en
     * una tabla aparte porque pertenece a ella: una venta tiene una boleta
     * y una boleta es de una venta.
     *
     * Se asigna cuando la venta está cobrada. Un pedido por WhatsApp que
     * todavía no se ha pagado no lleva boleta: el comprobante acompaña al
     * cobro, no al apunte.
     *
     * Y no se reutiliza. Si la venta se anula, su número se queda con ella
     * —lo que corresponde es una nota de crédito, no reciclar el
     * correlativo—: un número que vuelve a salir deja dos papeles distintos
     * diciendo ser el mismo comprobante.
     */
    @Column(name = "boleta_serie", length = 10)
    private String boletaSerie;

    @Column(name = "boleta_numero")
    private Integer boletaNumero;

    @Column(length = 30, nullable = false)
    private String status = "pending";

    /**
     * De dónde vino la venta: "web" si la hizo el cliente, "caja" si se
     * cobró en el local, o la red por la que llegó (whatsapp, instagram…).
     *
     * Hace falta porque desde que el panel puede vender, "pedido" ya no
     * significa una sola cosa: el reporte tiene que poder decir cuánto se
     * vendió por mostrador y cuánto por la tienda, y son negocios distintos.
     *
     * Nullable aunque conceptualmente no lo sea: la columna se añade a una
     * tabla que ya tiene pedidos, y Hibernate no puede rellenarlos. Se lee
     * con canal(), que trata el null como "web" —que es lo que eran todos
     * antes de que existiera la caja—.
     */
    @Column(length = 20)
    private String channel;

    /**
     * Si el dinero ya entró: "paid" o "pending".
     *
     * Es información distinta de `status`, y mezclarlas era el problema. Un
     * pedido puede estar entregado y sin cobrar (contra entrega que falló) o
     * cobrado y sin entregar (pagó por Yape y se envía mañana). Con un solo
     * campo no se puede expresar ninguno de los dos.
     *
     * Nullable por lo mismo que channel; se lee con estadoDePago(), que
     * deduce el histórico a partir del estado.
     */
    @Column(name = "payment_status", length = 20)
    private String paymentStatus;

    /**
     * El turno de caja al que pertenece esta venta.
     *
     * Solo lo llevan las de mostrador: es lo que permite preguntar "¿qué se
     * cobró en efectivo en este turno?" al cuadrar. Null en los pedidos web
     * —esos no pasan por el cajón— y en los de mostrador anteriores a que
     * existieran los turnos.
     */
    @Column(name = "cash_session_id")
    private Integer cashSessionId;

    /** Qué usuario del panel registró la venta. Null si la hizo el cliente. */
    @Column(name = "created_by")
    private Long createdBy;

    /** Efectivo que puso el cliente en el mostrador, y su vuelto. */
    @Column(name = "cash_received")
    private Double cashReceived;

    @Column(name = "change_given")
    private Double changeGiven;

    /**
     * La llave de las unidades apartadas para este pedido.
     *
     * Se guarda porque el descuento llega después, por la cola, y hay que
     * poder decirle a catalog QUÉ reserva consumir. No vale el código del
     * pedido: ese se genera antes de guardar y dos checkouts simultáneos
     * pueden sacar el mismo, y entonces un pedido consumiría las unidades
     * del otro.
     *
     * Null en los pedidos de caja —entregan en el acto, no apartan nada— y
     * en los anteriores a que existieran las reservas.
     */
    @Column(name = "reservation_token", length = 36)
    private String reservationToken;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * El canal, tratando el null de los pedidos anteriores a la caja como
     * "web": antes del panel de ventas todos lo eran.
     *
     * Se expone en el JSON con @JsonProperty, y no se deja que el panel
     * resuelva el null por su cuenta, porque entonces la misma regla viviría
     * en Java y en el frontend y acabarían diciendo cosas distintas.
     */
    @com.fasterxml.jackson.annotation.JsonProperty("canal")
    public String canal() {
        return channel == null || channel.isBlank() ? "web" : channel;
    }

    /**
     * El estado de pago, deducido para los pedidos antiguos que no lo tienen.
     *
     * No se puede dar por pendiente sin más: un pedido entregado o con el
     * pago ya validado estaba cobrado, y contarlo como pendiente inflaría la
     * cifra de "por cobrar" del panel con pedidos de hace meses.
     */
    @com.fasterxml.jackson.annotation.JsonProperty("estadoDePago")
    public String estadoDePago() {
        if (paymentStatus != null && !paymentStatus.isBlank()) return paymentStatus;
        return switch (status == null ? "" : status) {
            case "payment_validated", "in_preparation", "delivered" -> "paid";
            default -> "pending";
        };
    }

    /*
     * LAZY con @BatchSize, y las dos cosas hacen falta.
     *
     * Estaban en EAGER: listar diez pedidos lanzaba una consulta para los
     * pedidos, diez para los items y diez para los historiales. Veintiuna
     * para pintar una tabla de diez filas.
     *
     * LAZY a secas no lo arregla. `open-in-view` está activo —es el valor
     * por defecto de Spring Boot— así que la sesión sigue abierta mientras
     * Jackson serializa, y al recorrer las colecciones las cargaría una por
     * una igual: las mismas veintiuna consultas, solo que más tarde.
     *
     * Lo que las junta es @BatchSize: Hibernate carga las colecciones de
     * los diez pedidos de golpe, con un IN (...), y quedan tres consultas
     * en total. El 25 cubre con holgura la página de diez del panel y los
     * veinte del historial de un producto.
     *
     * Si alguien devuelve estas colecciones FUERA de la petición —en un
     * @Async, o tras cerrar la sesión a mano— volverá el
     * LazyInitializationException; ahí hay que traerlas con JOIN FETCH, no
     * volver a EAGER.
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @org.hibernate.annotations.BatchSize(size = 25)
    private java.util.List<OrderItem> items;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @org.hibernate.annotations.BatchSize(size = 25)
    @OrderBy("createdAt DESC")
    private java.util.List<OrderStatusLog> statusLogs;

    /**
     * La boleta tal como se imprime: B001-00000123. Null si no tiene.
     *
     * Se expone ya formateada, igual que `canal`: si cada pantalla juntara
     * la serie y el número por su cuenta, una acabaría rellenando con
     * ceros a seis dígitos y otra a ocho, y serían dos comprobantes
     * distintos sobre el papel.
     */
    @com.fasterxml.jackson.annotation.JsonProperty("boleta")
    public String numeroDeBoleta() {
        if (boletaSerie == null || boletaNumero == null) return null;
        return boletaSerie + "-" + String.format("%08d", boletaNumero);
    }
}
