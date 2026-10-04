package com.bytemarket.order.controller.api.admin;

import com.bytemarket.order.client.CatalogClient;
import com.bytemarket.order.model.Order;
import com.bytemarket.order.repository.IOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reportes y automatizaciones del panel. Los datos de producto vienen de
 * catalog-service: aquí solo vive lo de pedidos.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Slf4j
public class ApiAdminReportController {

    private final IOrderRepository orderRepository;
    private final CatalogClient catalogClient;

    /** Estados que siguen esperando algo de la tienda. */
    private static final List<String> ABIERTOS =
            List.of("pending", "voucher_sent", "payment_validated", "in_preparation");

    // ─── Resumen del panel ──────────────────────────────────────

    @GetMapping("/automation/reports")
    public Map<String, Object> reports(@RequestParam(defaultValue = "10") int threshold) {
        LocalDateTime hoy = LocalDate.now().atStartOfDay();
        LocalDateTime semana = hoy.minusDays(7);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("todaySales", orderRepository.sumarDesde(hoy));
        totals.put("todayOrders", orderRepository.contarDesde(hoy));
        totals.put("weekOrders", orderRepository.contarDesde(semana));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("totals", totals);
        resp.put("topProducts", orderRepository.productosMasVendidos(PageRequest.of(0, 5)));
        resp.put("lowStock", lowStockSeguro(threshold));
        return resp;
    }

    // ─── Alertas de stock ───────────────────────────────────────

    /**
     * El panel llama con notify=1 al pulsar "avisar". El aviso por correo
     * todavía no está conectado, así que se registra y se devuelve la lista
     * igual: la pantalla sigue siendo útil.
     */
    @GetMapping("/automation/stock-alerts")
    public Map<String, Object> stockAlerts(@RequestParam(defaultValue = "10") int threshold,
                                           @RequestParam(required = false) Integer notify) {
        List<Map<String, Object>> data = lowStockSeguro(threshold);
        if (notify != null && notify == 1) {
            log.info("Alerta de stock solicitada: {} productos bajo el umbral {}", data.size(), threshold);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", data);
        resp.put("threshold", threshold);
        resp.put("notified", notify != null && notify == 1);
        return resp;
    }

    // ─── Pedidos que piden seguimiento ──────────────────────────

    @GetMapping("/automation/follow-ups")
    public Map<String, Object> followUps(@RequestParam(defaultValue = "24") long hours) {
        List<Map<String, Object>> data = new ArrayList<>();
        LocalDateTime ahora = LocalDateTime.now();

        for (Order o : orderRepository.findByStatusInOrderByCreatedAtAsc(ABIERTOS)) {
            if (o.getCreatedAt() == null) continue;
            long abiertas = Duration.between(o.getCreatedAt(), ahora).toHours();
            if (abiertas < hours) continue;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.getId());
            m.put("orderCode", o.getOrderCode());
            m.put("customerName", o.getCustomerName());
            m.put("customerPhone", o.getCustomerPhone());
            m.put("status", o.getStatus());
            m.put("total", o.getTotal());
            m.put("hoursOpen", abiertas);
            m.put("createdAt", o.getCreatedAt());
            data.add(m);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", data);
        resp.put("thresholdHours", hours);
        return resp;
    }

    // ─── Gráfica de ventas ──────────────────────────────────────

    @GetMapping("/reports/sales-chart")
    public List<Map<String, Object>> salesChart(@RequestParam(defaultValue = "30") int days) {
        return orderRepository.ventasPorDia(LocalDate.now().minusDays(days).atStartOfDay());
    }

    // ─── Exportación (Excel / PDF los arma el navegador) ────────

    @GetMapping("/reports/export")
    public Map<String, Object> export() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("orders", orderRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 1000)).getContent());
        try {
            resp.put("products", catalogClient.exportProducts());
        } catch (Exception e) {
            // Sin catálogo se exporta igual lo de pedidos en vez de fallar todo.
            log.error("No se pudo leer el catálogo para la exportación: {}", e.getMessage());
            resp.put("products", List.of());
        }
        return resp;
    }

    /** Un catálogo caído no debe tumbar el panel entero. */
    private List<Map<String, Object>> lowStockSeguro(int threshold) {
        try {
            return catalogClient.lowStock(threshold);
        } catch (Exception e) {
            log.error("No se pudo leer el stock bajo del catálogo: {}", e.getMessage());
            return List.of();
        }
    }
}
