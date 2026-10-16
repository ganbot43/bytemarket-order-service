package com.bytemarket.order.controller.api.admin;

import com.bytemarket.order.model.Order;
import com.bytemarket.order.repository.IOrderRepository;
import com.bytemarket.order.security.CurrentUser;
import com.bytemarket.order.service.CartService;
import com.bytemarket.order.service.OrderService;
import com.bytemarket.order.service.PanelSaleService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final PanelSaleService panelSaleService;

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

    // ─── Venta desde el panel (caja y pedidos a mano) ───────────

    /**
     * Registra una venta hecha por el staff: la caja del local, o un pedido
     * que llegó por WhatsApp, Instagram o teléfono.
     *
     * Es POST sobre /api/admin/orders y no sobre /api/orders porque son dos
     * operaciones distintas: aquí el precio lo pone quien vende y la venta
     * puede nacer ya cobrada y entregada. Las reglas están en
     * PanelSaleService; esto solo traduce HTTP.
     */
    @PostMapping
    public ResponseEntity<?> createPanelSale(@RequestBody Map<String, Object> body) {
        Long adminId = CurrentUser.get().map(u -> u.id()).orElse(null);
        try {
            PanelSaleService.Resultado r = panelSaleService.registrar(leerVenta(body), adminId);
            Order o = r.order();

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", o.getId());
            resp.put("orderCode", o.getOrderCode());
            resp.put("total", o.getTotal());
            resp.put("subtotal", o.getSubtotal());
            resp.put("discountApplied", o.getDiscountApplied());
            resp.put("couponCode", o.getCouponCode());
            resp.put("channel", o.canal());
            resp.put("status", o.getStatus());
            resp.put("paymentStatus", o.estadoDePago());
            resp.put("paymentMethodLabel", o.getPaymentMethodLabel());
            resp.put("cashReceived", o.getCashReceived());
            resp.put("change", r.change());
            resp.put("customerName", o.getCustomerName());
            resp.put("customerPhone", o.getCustomerPhone());
            resp.put("createdAt", o.getCreatedAt());
            return ResponseEntity.status(HttpStatus.CREATED).body(resp);
        } catch (CartService.CartException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Las formas de pago y los canales que acepta la caja.
     *
     * Se sirven desde aquí para que la pantalla no los tenga escritos a
     * mano: si mañana se añade "tarjeta", el panel lo ve sin tocar el
     * frontend. No se mezclan con /api/payment-methods, que es lo que ofrece
     * la TIENDA y no incluye efectivo.
     */
    @GetMapping("/sale-options")
    public Map<String, Object> saleOptions() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("paymentTypes", PanelSaleService.FORMAS_DE_PAGO.entrySet().stream()
                .map(e -> Map.of("value", e.getKey(), "label", e.getValue()))
                .sorted((a, b) -> a.get("label").compareTo(b.get("label")))
                .toList());
        resp.put("channels", List.of(
                Map.of("value", "whatsapp", "label", "WhatsApp"),
                Map.of("value", "instagram", "label", "Instagram"),
                Map.of("value", "facebook", "label", "Facebook"),
                Map.of("value", "telefono", "label", "Teléfono"),
                Map.of("value", "otro", "label", "Otro")));
        return resp;
    }

    /** Traduce el cuerpo JSON a la petición del servicio. */
    @SuppressWarnings("unchecked")
    private PanelSaleService.Peticion leerVenta(Map<String, Object> body) {
        List<PanelSaleService.LineaDeVenta> items = new ArrayList<>();
        Object crudo = body.get("items");
        if (crudo instanceof List<?> lista) {
            for (Object o : lista) {
                if (!(o instanceof Map<?, ?> m)) continue;
                items.add(new PanelSaleService.LineaDeVenta(
                        entero(m.get("productId")),
                        entero(m.get("quantity")),
                        decimal(m.get("unitPrice"))));
            }
        }
        return new PanelSaleService.Peticion(
                OrderService.texto(body.get("channel")),
                OrderService.texto(body.get("customerName")),
                OrderService.texto(body.get("customerPhone")),
                OrderService.texto(body.get("customerEmail")),
                OrderService.texto(body.get("customerAddress")),
                OrderService.texto(body.get("customerReference")),
                OrderService.texto(body.get("customerNotes")),
                items,
                decimal(body.get("discount")),
                OrderService.texto(body.get("couponCode")),
                OrderService.texto(body.get("paymentType")),
                OrderService.texto(body.get("paymentStatus")),
                decimal(body.get("cashReceived")));
    }

    /** Un número mal escrito es un 400 con mensaje, no un 500. */
    private static Integer entero(Object v) {
        if (v == null) return null;
        try {
            return Integer.valueOf(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new CartService.CartException("Revisa las cantidades: '" + v + "' no es un número");
        }
    }

    private static Double decimal(Object v) {
        if (v == null) return null;
        try {
            return Double.valueOf(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new CartService.CartException("Revisa los importes: '" + v + "' no es un número");
        }
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("statusCode", status.value(), "message", message));
    }
}
