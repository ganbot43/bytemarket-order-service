package com.bytemarket.order.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "payment_methods")
public class PaymentMethod {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(length = 30, nullable = false)
    private String type;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String label;

    @Column(name = "qr_url", columnDefinition = "TEXT")
    private String qrUrl;

    @Column(name = "account_number", columnDefinition = "TEXT")
    private String accountNumber;

    @Column(name = "account_name", columnDefinition = "TEXT")
    private String accountName;

    @Column(name = "is_active", nullable = false)
    private Integer isActive = 1;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;
}
