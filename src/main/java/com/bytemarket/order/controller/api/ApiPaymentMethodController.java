package com.bytemarket.order.controller.api;

import com.bytemarket.order.model.PaymentMethod;
import com.bytemarket.order.repository.IPaymentMethodRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Métodos de pago que ve el cliente en el checkout. */
@RestController
@RequestMapping("/api/payment-methods")
@RequiredArgsConstructor
public class ApiPaymentMethodController {

    private final IPaymentMethodRepository paymentMethodRepository;

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) Boolean active) {
        // El checkout pide active=true; sin el parámetro se devuelven todos.
        List<PaymentMethod> encontrados = Boolean.FALSE.equals(active)
                ? paymentMethodRepository.findAll()
                : paymentMethodRepository.findByIsActiveOrderBySortOrderAsc(1);
        // Mismo DTO que el panel: isActive booleano, no el 0/1 de la base.
        List<Map<String, Object>> data = encontrados.stream()
                .map(com.bytemarket.order.controller.api.admin.ApiAdminPaymentMethodController::dto)
                .toList();
        return Map.of("data", data);
    }
}
