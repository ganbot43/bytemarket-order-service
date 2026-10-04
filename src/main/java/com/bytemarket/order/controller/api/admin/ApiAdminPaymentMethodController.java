package com.bytemarket.order.controller.api.admin;

import com.bytemarket.order.model.PaymentMethod;
import com.bytemarket.order.repository.IPaymentMethodRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CRUD de métodos de pago del panel. */
@RestController
@RequestMapping("/api/admin/payment-methods")
@RequiredArgsConstructor
public class ApiAdminPaymentMethodController {

    private final IPaymentMethodRepository paymentMethodRepository;

    @GetMapping
    public Map<String, Object> list() {
        // Se mapea a DTO en vez de devolver la entidad para que isActive
        // salga booleano, como el resto de la API (en base es 0/1).
        List<Map<String, Object>> data = paymentMethodRepository.findAll().stream()
                .sorted(Comparator.comparing(pm -> pm.getSortOrder() == null ? 0 : pm.getSortOrder()))
                .map(ApiAdminPaymentMethodController::dto)
                .toList();
        return Map.of("data", data);
    }

    /** Público para que ApiPaymentMethodController devuelva la misma forma. */
    public static Map<String, Object> dto(PaymentMethod pm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", pm.getId());
        m.put("type", pm.getType());
        m.put("label", pm.getLabel());
        m.put("qrUrl", pm.getQrUrl());
        m.put("accountNumber", pm.getAccountNumber());
        m.put("accountName", pm.getAccountName());
        m.put("isActive", pm.getIsActive() != null && pm.getIsActive() == 1);
        m.put("sortOrder", pm.getSortOrder());
        return m;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> dto) {
        PaymentMethod pm = new PaymentMethod();
        aplicar(pm, dto);
        if (pm.getLabel() == null || pm.getLabel().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "El nombre del método es obligatorio");
        }
        if (pm.getType() == null || pm.getType().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "Elige el tipo de método de pago");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentMethodRepository.save(pm));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> dto) {
        PaymentMethod pm = paymentMethodRepository.findById(id).orElse(null);
        if (pm == null) return error(HttpStatus.NOT_FOUND, "Método de pago no encontrado");
        aplicar(pm, dto);
        return ResponseEntity.ok(paymentMethodRepository.save(pm));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        if (!paymentMethodRepository.existsById(id)) {
            return error(HttpStatus.NOT_FOUND, "Método de pago no encontrado");
        }
        paymentMethodRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private void aplicar(PaymentMethod pm, Map<String, Object> dto) {
        if (dto.containsKey("type")) pm.setType(texto(dto.get("type")));
        if (dto.containsKey("label")) pm.setLabel(texto(dto.get("label")));
        if (dto.containsKey("qrUrl")) pm.setQrUrl(texto(dto.get("qrUrl")));
        if (dto.containsKey("accountNumber")) pm.setAccountNumber(texto(dto.get("accountNumber")));
        if (dto.containsKey("accountName")) pm.setAccountName(texto(dto.get("accountName")));
        // El panel manda isActive como booleano; en base es 0/1.
        if (dto.containsKey("isActive") && dto.get("isActive") != null) {
            pm.setIsActive(Boolean.parseBoolean(dto.get("isActive").toString()) ? 1 : 0);
        }
        if (dto.containsKey("sortOrder") && dto.get("sortOrder") != null) {
            pm.setSortOrder(Integer.parseInt(dto.get("sortOrder").toString()));
        }
    }

    private static String texto(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("statusCode", status.value(), "message", message));
    }
}
