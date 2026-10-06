package com.bytemarket.order.controller.api.admin;

import com.bytemarket.order.model.Order;
import com.bytemarket.order.repository.IOrderRepository;
import com.bytemarket.order.security.CurrentUser;
import com.bytemarket.order.service.CartService;
import com.bytemarket.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pedidos desde el panel. El acceso por rol lo corta SecurityConfig
 * (/api/admin/** exige ADMIN o SUPERADMIN), aquí ya llega staff.
 */
@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
public class ApiAdminOrderController {

    private final IOrderRepository orderRepository;
    private final OrderService orderService;

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "20") int limit,
                                    @RequestParam(defaultValue = "0") int offset,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String q) {
        int tamano = Math.max(1, limit);
        Pageable pagina = PageRequest.of(offset / tamano, tamano);

        String estado = (status == null || status.isBlank()) ? null : status;
        String patron = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase() + "%";

        Page<Order> page;
        if (patron == null) {
            page = estado == null
                    ? orderRepository.findAllByOrderByCreatedAtDesc(pagina)
                    : orderRepository.findByStatusOrderByCreatedAtDesc(estado, pagina);
        } else {
            page = orderRepository.buscar(estado, patron, pagina);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", page.getContent());
        resp.put("total", page.getTotalElements());
        // Totales de todos los pedidos, no de esta página: las tarjetas del
        // panel los contaban sobre `data` y se quedaban en el tamaño de página.
        resp.put("totalAll", orderRepository.count());
        resp.put("totalPending", orderRepository.countByStatus("pending"));
        resp.put("totalDelivered", orderRepository.countByStatus("delivered"));
        return resp;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable Integer id) {
        return orderService.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "Pedido no encontrado"));
    }

    @RequestMapping(value = "/{id}/status", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public ResponseEntity<?> changeStatus(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        Order order = orderService.findById(id).orElse(null);
        if (order == null) return error(HttpStatus.NOT_FOUND, "Pedido no encontrado");

        Long adminId = CurrentUser.get().map(u -> u.id()).orElse(null);
        String estado = OrderService.texto(body.get("status"));
        String nota = OrderService.texto(body.get("note"));

        try {
            return ResponseEntity.ok(orderService.changeStatus(order, estado, nota, adminId));
        } catch (CartService.CartException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("statusCode", status.value(), "message", message));
    }
}
