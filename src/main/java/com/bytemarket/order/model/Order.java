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

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private java.util.List<OrderItem> items;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @OrderBy("createdAt DESC")
    private java.util.List<OrderStatusLog> statusLogs;
}
