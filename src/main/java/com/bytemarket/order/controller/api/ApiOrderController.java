package com.bytemarket.order.controller.api;

import com.bytemarket.order.dto.CartItemDTO;
import com.bytemarket.order.model.Order;
import com.bytemarket.order.security.AuthUser;
import com.bytemarket.order.security.CurrentUser;
import com.bytemarket.order.service.CartService;
import com.bytemarket.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pedidos del cliente: alta desde el checkout y consulta de los propios.
 * El rol y el id salen del JWT (ver JwtAuthenticationFilter), no del cuerpo.
 */
@RestController
@RequiredArgsConstructor
public class ApiOrderController {

    private final OrderService orderService;

    // ─── Checkout ───────────────────────────────────────────────

    @PostMapping("/api/orders")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        Optional<AuthUser> auth = CurrentUser.get();
        if (auth.isEmpty()) return error(HttpStatus.UNAUTHORIZED, "Inicia sesión para completar tu pedido");

        try {
            Order order = orderService.create(body, auth.get().id(), auth.get().email(), leerItems(body));
            return ResponseEntity.status(HttpStatus.CREATED).body(order);
        } catch (CartService.CartException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // ─── Seguimiento de un pedido por código ────────────────────

    @GetMapping("/api/orders/{code}")
    public ResponseEntity<?> getByCode(@PathVariable String code) {
        Optional<AuthUser> auth = CurrentUser.get();
        if (auth.isEmpty()) return error(HttpStatus.UNAUTHORIZED, "Inicia sesión para ver tu pedido");

        Order order = orderService.findByCode(code).orElse(null);
        if (order == null) return error(HttpStatus.NOT_FOUND, "Pedido no encontrado");

        // El staff puede abrir cualquier pedido; el cliente, solo los suyos.
        // Se responde 404 y no 403 para no confirmar que el código existe.
        if (!puedeVer(order, auth.get())) return error(HttpStatus.NOT_FOUND, "Pedido no encontrado");

        return ResponseEntity.ok(order);
    }

    // ─── "Mis pedidos" ──────────────────────────────────────────

    @GetMapping("/api/my-orders")
    public ResponseEntity<?> myOrders() {
        Optional<AuthUser> auth = CurrentUser.get();
        if (auth.isEmpty()) return error(HttpStatus.UNAUTHORIZED, "Inicia sesión para ver tus pedidos");
        return ResponseEntity.ok(orderService.findForUser(auth.get().id()));
    }

    @GetMapping("/api/my-orders/{code}")
    public ResponseEntity<?> myOrder(@PathVariable String code) {
        Optional<AuthUser> auth = CurrentUser.get();
        if (auth.isEmpty()) return error(HttpStatus.UNAUTHORIZED, "Inicia sesión para ver tu pedido");

        Order order = orderService.findByCode(code).orElse(null);
        if (order == null || !puedeVer(order, auth.get())) {
            return error(HttpStatus.NOT_FOUND, "Pedido no encontrado");
        }
        return ResponseEntity.ok(order);
    }

    // ─── helpers ────────────────────────────────────────────────

    private boolean puedeVer(Order order, AuthUser user) {
        if (user.isStaff()) return true;
        return order.getUserId() != null && order.getUserId().equals(user.id());
    }

    @SuppressWarnings("unchecked")
    private List<CartItemDTO> leerItems(Map<String, Object> body) {
        List<CartItemDTO> items = new ArrayList<>();
        Object raw = body.get("items");
        if (!(raw instanceof List<?> lista)) return items;

        for (Object o : lista) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> item = (Map<String, Object>) m;
            Object id = item.get("productId");
            Object qty = item.get("quantity");
            if (id == null) continue;
            items.add(new CartItemDTO(
                    Long.valueOf(id.toString()),
                    qty == null ? 0 : Integer.valueOf(qty.toString())));
        }
        return items;
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("statusCode", status.value(), "message", message));
    }
}
