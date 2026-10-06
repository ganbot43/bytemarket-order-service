package com.bytemarket.order.controller.api.admin;

import com.bytemarket.order.model.Coupon;
import com.bytemarket.order.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CRUD de cupones de descuento. */
@RestController
@RequestMapping("/api/admin/coupons")
@RequiredArgsConstructor
public class ApiAdminCouponController {

    private static final List<String> TIPOS = List.of("PERCENT", "FIXED");

    private final CouponRepository couponRepository;

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "20") int limit,
                                    @RequestParam(defaultValue = "0") int offset,
                                    @RequestParam(required = false) String q) {
        int tamano = Math.max(1, limit);
        PageRequest pagina = PageRequest.of(offset / tamano, tamano);

        Page<Coupon> page = (q == null || q.isBlank())
                ? couponRepository.findAllByOrderByCreatedAtDesc(pagina)
                : couponRepository.findByCodeContainingIgnoreCaseOrderByCreatedAtDesc(q.trim(), pagina);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", page.getContent().stream().map(ApiAdminCouponController::dto).toList());
        resp.put("total", page.getTotalElements());
        return resp;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String code = texto(body.get("code"));
        if (code == null) return error(HttpStatus.BAD_REQUEST, "El código es obligatorio");
        code = code.toUpperCase();
        if (couponRepository.findByCode(code).isPresent()) {
            return error(HttpStatus.CONFLICT, "Ya existe un cupón con ese código");
        }

        Coupon c = new Coupon();
        c.setCode(code);
        c.setTimesUsed(0);
        c.setIsActive(1);
        ResponseEntity<?> fallo = aplicar(c, body, true);
        if (fallo != null) return fallo;

        return ResponseEntity.status(HttpStatus.CREATED).body(dto(couponRepository.save(c)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        Coupon c = couponRepository.findById(id).orElse(null);
        if (c == null) return error(HttpStatus.NOT_FOUND, "Cupón no encontrado");

        // El código no se cambia: los pedidos antiguos lo guardan como texto
        // y renombrarlo dejaría su historial apuntando a algo inexistente.
        ResponseEntity<?> fallo = aplicar(c, body, false);
        if (fallo != null) return fallo;

        return ResponseEntity.ok(dto(couponRepository.save(c)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        Coupon c = couponRepository.findById(id).orElse(null);
        if (c == null) return error(HttpStatus.NOT_FOUND, "Cupón no encontrado");

        // Un cupón ya usado no se borra: desactivarlo conserva el rastro de
        // los pedidos que lo aplicaron.
        if (c.getTimesUsed() != null && c.getTimesUsed() > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "statusCode", 409,
                    "message", "Este cupón ya se usó " + c.getTimesUsed() + " vez/veces. "
                             + "Desactívalo en vez de eliminarlo para no perder el historial."));
        }
        couponRepository.delete(c);
        return ResponseEntity.noContent().build();
    }

    /** Devuelve una respuesta de error si algún campo no es válido, o null si todo bien. */
    private ResponseEntity<?> aplicar(Coupon c, Map<String, Object> body, boolean esNuevo) {
        if (body.containsKey("discountType") || esNuevo) {
            String tipo = texto(body.get("discountType"));
            if (tipo == null) return error(HttpStatus.BAD_REQUEST, "Elige el tipo: PERCENT o FIXED");
            tipo = tipo.toUpperCase();
            if (!TIPOS.contains(tipo)) return error(HttpStatus.BAD_REQUEST, "Tipo no válido: usa PERCENT o FIXED");
            c.setDiscountType(tipo);
        }

        if (body.containsKey("discountValue") || esNuevo) {
            if (body.get("discountValue") == null) {
                return error(HttpStatus.BAD_REQUEST, "El valor del descuento es obligatorio");
            }
            double valor;
            try {
                valor = Double.parseDouble(body.get("discountValue").toString());
            } catch (NumberFormatException e) {
                return error(HttpStatus.BAD_REQUEST, "El valor del descuento debe ser un número");
            }
            if (valor <= 0) return error(HttpStatus.BAD_REQUEST, "El descuento debe ser mayor que cero");
            // Un 150% dejaría el total en negativo; el cálculo lo recorta,
            // pero es mejor no dejar guardar un dato absurdo.
            if ("PERCENT".equals(c.getDiscountType()) && valor > 100) {
                return error(HttpStatus.BAD_REQUEST, "Un descuento porcentual no puede pasar del 100%");
            }
            c.setDiscountValue(valor);
        }

        if (body.containsKey("expirationDate")) {
            String fecha = texto(body.get("expirationDate"));
            if (fecha == null) {
                c.setExpirationDate(null);
            } else {
                try {
                    c.setExpirationDate(LocalDate.parse(fecha));
                } catch (DateTimeParseException e) {
                    return error(HttpStatus.BAD_REQUEST, "Fecha no válida: usa el formato AAAA-MM-DD");
                }
            }
        }

        if (body.containsKey("usageLimit")) {
            if (body.get("usageLimit") == null) {
                c.setUsageLimit(null); // sin límite
            } else {
                int limite;
                try {
                    limite = Integer.parseInt(body.get("usageLimit").toString());
                } catch (NumberFormatException e) {
                    return error(HttpStatus.BAD_REQUEST, "El límite de usos debe ser un número");
                }
                if (limite < 1) return error(HttpStatus.BAD_REQUEST, "El límite de usos debe ser al menos 1");
                c.setUsageLimit(limite);
            }
        }

        if (body.containsKey("isActive") && body.get("isActive") != null) {
            c.setIsActive(Boolean.parseBoolean(body.get("isActive").toString()) ? 1 : 0);
        }
        return null;
    }

    static Map<String, Object> dto(Coupon c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("code", c.getCode());
        m.put("discountType", c.getDiscountType());
        m.put("discountValue", c.getDiscountValue());
        m.put("expirationDate", c.getExpirationDate());
        m.put("usageLimit", c.getUsageLimit());
        m.put("timesUsed", c.getTimesUsed());
        m.put("isActive", c.getIsActive() != null && c.getIsActive() == 1);
        // Útil para el panel: un cupón puede estar activo pero ya agotado.
        m.put("exhausted", c.getUsageLimit() != null && c.getTimesUsed() != null
                && c.getTimesUsed() >= c.getUsageLimit());
        m.put("expired", c.getExpirationDate() != null && c.getExpirationDate().isBefore(LocalDate.now()));
        m.put("createdAt", c.getCreatedAt());
        return m;
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
