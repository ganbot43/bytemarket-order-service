package com.bytemarket.order.controller.api;

import com.bytemarket.order.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validación de cupones para el checkout.
 *
 * Usa previewCoupon, que NO gasta el cupón: solo se consume al confirmar el
 * pedido. Así el cliente puede escribir el código, ver el descuento y
 * cambiar de idea sin perderlo.
 */
@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
public class ApiCouponController {

    private final CartService cartService;

    @PostMapping("/validate")
    public ResponseEntity<?> validate(@RequestBody Map<String, Object> body) {
        String code = body.get("code") == null ? null : String.valueOf(body.get("code")).trim();
        if (code == null || code.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "valid", false, "message", "Escribe un código de cupón"));
        }

        double subtotal;
        try {
            subtotal = body.get("subtotal") == null ? 0d : Double.parseDouble(body.get("subtotal").toString());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("valid", false, "message", "Subtotal no válido"));
        }
        if (subtotal <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "valid", false, "message", "Agrega productos antes de aplicar un cupón"));
        }

        try {
            CartService.AppliedCoupon aplicado = cartService.previewCoupon(code, subtotal);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("valid", true);
            resp.put("code", aplicado.code());
            resp.put("discount", aplicado.discount());
            resp.put("total", Math.round((subtotal - aplicado.discount()) * 100d) / 100d);
            return ResponseEntity.ok(resp);
        } catch (CartService.CartException e) {
            // 200 con valid=false: que el cupón no sirva no es un error de la
            // petición, y así el checkout puede pintar el mensaje sin más.
            return ResponseEntity.ok(Map.of("valid", false, "message", e.getMessage()));
        }
    }
}
