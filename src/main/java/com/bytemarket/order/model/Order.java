package com.bytemarket.order.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "orders")
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

    /** Qué usuario del panel registró la venta. Null si la hizo el cliente. */
    @Column(name = "created_by")
    private Long createdBy;

    /** Efectivo que puso el cliente en el mostrador, y su vuelto. */
    @Column(name = "cash_received")
    private Double cashReceived;

    @Column(name = "change_given")
    private Double changeGiven;

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

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private java.util.List<OrderItem> items;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @OrderBy("createdAt DESC")
    private java.util.List<OrderStatusLog> statusLogs;
}
