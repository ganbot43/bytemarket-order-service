package com.bytemarket.order.config;

import com.bytemarket.order.model.Coupon;
import com.bytemarket.order.model.PaymentMethod;
import com.bytemarket.order.repository.CouponRepository;
import com.bytemarket.order.repository.IPaymentMethodRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Seeder de ByteMarket (order-service): métodos de pago y cupones.
 */
@Component
public class DatabaseSeeder implements CommandLineRunner {

    @Autowired
    private IPaymentMethodRepository paymentMethodRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Override
    public void run(String... args) {
        System.out.println("🌱 Iniciando seed de ByteMarket (order-service)...");
        seedPaymentMethods();
        seedCoupons();
        System.out.println("✅ Seed de order-service completado.");
    }

    private void seedPaymentMethods() {
        if (paymentMethodRepository.count() == 0) {
            save("yape", "Yape", "ByteMarket SAC", "923821520", 1);
            save("plin", "Plin", "ByteMarket SAC", "923821520", 2);
            save("bank_transfer", "Transferencia Bancaria (BCP)", "ByteMarket SAC", "191-98765432-0-15", 3);
            System.out.println("💳 Métodos de pago creados.");
        } else {
            // Migrar titulares antiguos de CelParts
            paymentMethodRepository.findAll().forEach(pm -> {
                if (pm.getAccountName() != null && pm.getAccountName().toLowerCase().replace(" ", "").contains("celparts")) {
                    pm.setAccountName("ByteMarket SAC");
                    paymentMethodRepository.save(pm);
                    System.out.println("🔁 Método de pago migrado: " + pm.getLabel());
                }
            });
        }
    }

    private void save(String type, String label, String accountName, String accountNumber, int order) {
        PaymentMethod pm = new PaymentMethod();
        pm.setType(type);
        pm.setLabel(label);
        pm.setAccountName(accountName);
        pm.setAccountNumber(accountNumber);
        pm.setIsActive(1);
        pm.setSortOrder(order);
        paymentMethodRepository.save(pm);
    }

    private void seedCoupons() {
        coupon("BIENVENIDO10", "PERCENT", 10.0, LocalDate.now().plusMonths(6), null);
        coupon("BYTE20", "FIXED", 20.0, LocalDate.now().plusMonths(3), 100);
        coupon("GAMER15", "PERCENT", 15.0, LocalDate.now().plusMonths(1), 50);
    }

    private void coupon(String code, String type, Double value, LocalDate exp, Integer limit) {
        if (couponRepository.findByCode(code).isPresent()) return;
        Coupon c = new Coupon();
        c.setCode(code);
        c.setDiscountType(type);
        c.setDiscountValue(value);
        c.setExpirationDate(exp);
        c.setUsageLimit(limit);
        c.setIsActive(1);
        couponRepository.save(c);
        System.out.println("🎟️ Cupón creado: " + code);
    }
}
