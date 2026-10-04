package com.bytemarket.order.controller.api.admin;

import com.bytemarket.order.client.CatalogClient;
import com.bytemarket.order.model.Order;
import com.bytemarket.order.model.OrderItem;
import com.bytemarket.order.repository.IOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reconstruye los movimientos de inventario de pedidos antiguos.
 *
 * Vive aquí y no en catalog-service porque los pedidos son de este servicio;
 * catalog solo recibe la lista ya armada y crea lo que le falte. El gateway
 * enruta /api/admin/inventory/backfill aquí, y el resto del kardex a catálogo.
 */
@RestController
@RequestMapping("/api/admin/inventory")
@RequiredArgsConstructor
@Slf4j
public class ApiAdminInventoryBackfillController {

    private final IOrderRepository orderRepository;
    private final CatalogClient catalogClient;

    @PostMapping("/backfill")
    public ResponseEntity<?> backfill() {
        List<Map<String, Object>> entradas = new ArrayList<>();
        int pedidos = 0;

        for (Order o : orderRepository.findAll()) {
            // Un pedido cancelado nunca descontó stock: no hay nada que reconstruir.
            if ("cancelled".equals(o.getStatus()) || o.getItems() == null) continue;
            pedidos++;
            for (OrderItem item : o.getItems()) {
                if (item.getProductId() == null) continue;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("orderId", o.getId());
                e.put("productId", item.getProductId());
                e.put("quantity", item.getQuantity());
                entradas.add(e);
            }
        }

        int creados;
        try {
            Map<String, Object> res = catalogClient.backfillMovements(entradas);
            creados = res.get("created") == null ? 0 : Integer.parseInt(res.get("created").toString());
        } catch (Exception e) {
            log.error("El catálogo no pudo reconstruir los movimientos: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("statusCode", 502, "message", "No se pudo contactar al catálogo"));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("created", creados);
        resp.put("processedOrders", pedidos);
        return ResponseEntity.ok(resp);
    }
}
