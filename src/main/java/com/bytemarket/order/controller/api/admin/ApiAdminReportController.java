package com.bytemarket.order.controller.api.admin;

import com.bytemarket.order.client.CatalogReportingClient;
import com.bytemarket.order.model.Order;
import com.bytemarket.order.repository.IOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.bytemarket.order.service.CartService;
import com.bytemarket.order.service.PanelReportService;
import java.time.format.DateTimeParseException;

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
    private final CatalogReportingClient catalogReportingClient;
    private final PanelReportService panelReportService;

    /** Estados que siguen esperando algo de la tienda. */
    private static final List<String> ABIERTOS =
            List.of("pending", "voucher_sent", "payment_validated", "in_preparation");

    // ─── Resumen del panel ──────────────────────────────────────

    @GetMapping("/automation/reports")
    public Map<String, Object> reports(@RequestParam(required = false) Integer threshold) {
        LocalDateTime hoy = LocalDate.now().atStartOfDay();
        LocalDateTime semana = hoy.minusDays(7);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("todaySales", orderRepository.sumarDesde(hoy));
        totals.put("todayOrders", orderRepository.contarDesde(hoy));
        totals.put("weekOrders", orderRepository.contarDesde(semana));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("totals", totals);
        resp.put("topProducts", orderRepository.productosMasVendidos(PageRequest.of(0, 5)));
        resp.put("lowStock", bajoStock(lowStockSeguro(threshold)));
        return resp;
    }

    // ─── Alertas de stock ───────────────────────────────────────

    /**
     * El panel llama con notify=1 al pulsar "avisar". El aviso por correo
     * todavía no está conectado, así que se registra y se devuelve la lista
     * igual: la pantalla sigue siendo útil.
     */
    @GetMapping("/automation/stock-alerts")
    public Map<String, Object> stockAlerts(@RequestParam(required = false) Integer threshold,
                                           @RequestParam(required = false) Integer notify) {
        Map<String, Object> catalogo = lowStockSeguro(threshold);
        List<Map<String, Object>> data = bajoStock(catalogo);
        if (notify != null && notify == 1) {
            log.info("Alerta de stock solicitada: {} productos bajo el umbral {}",
                    data.size(), catalogo.get("threshold"));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", data);
        // El umbral lo pone catalog; aquí solo se reenvía el que aplicó.
        resp.put("threshold", catalogo.get("threshold"));
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

    /**
     * Ventas por día para la gráfica de la portada.
     *
     * Reusa ventasPorDiaEntre, la misma consulta del reporte de ventas, en
     * vez de tener la suya: la regla de "cobrado" es ese COALESCE largo
     * sobre payment_status, y cada copia que exista es una copia que se
     * puede quedar atrás. Esta tenía la suya y se quedó: sumaba todo lo no
     * cancelado, así que la gráfica decía 3964 el día que la tarjeta de
     * arriba decía 2022. Una gráfica que no cuadra con la cifra de al lado
     * no se puede usar para decidir nada.
     *
     * El rango incluye hoy: `days` días contando hacia atrás desde hoy.
     */
    @GetMapping("/reports/sales-chart")
    public List<Map<String, Object>> salesChart(@RequestParam(defaultValue = "30") int days) {
        int rango = Math.max(1, Math.min(365, days));
        LocalDateTime desde = LocalDate.now().minusDays(rango - 1L).atStartOfDay();
        LocalDateTime hasta = LocalDate.now().plusDays(1).atStartOfDay();

        List<Map<String, Object>> puntos = new ArrayList<>();
        for (Map<String, Object> fila : orderRepository.ventasPorDiaEntre(desde, hasta)) {
            Map<String, Object> punto = new LinkedHashMap<>();
            punto.put("date", String.valueOf(fila.get("day")));
            punto.put("totalSales", fila.get("amount"));
            punto.put("orders", fila.get("orders"));
            puntos.add(punto);
        }
        return puntos;
    }

    // ─── Exportación (Excel / PDF los arma el navegador) ────────

    @GetMapping("/reports/export")
    public Map<String, Object> export() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("orders", orderRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 1000)).getContent());
        try {
            resp.put("products", catalogReportingClient.exportProducts());
        } catch (Exception e) {
            // Sin catálogo se exporta igual lo de pedidos en vez de fallar todo.
            log.error("No se pudo leer el catálogo para la exportación: {}", e.getMessage());
            resp.put("products", List.of());
        }
        return resp;
    }

    // ─── Resumen de la portada ──────────────────────────────────

    /**
     * Lo que el panel muestra al abrirse: dinero cobrado y trabajo pendiente.
     *
     * Vive bajo /api/admin/reports/ y no en /api/admin/dashboard porque el
     * gateway ya enruta /api/admin/reports/** a este servicio; una ruta
     * nueva habría obligado a tocar el gateway para nada.
     *
     * El stock bajo se pide a catalog con su fallback: si el catálogo no
     * responde, esa tarjeta sale en 0 y el resto del panel carga igual.
     */
    @GetMapping("/reports/dashboard")
    public Map<String, Object> dashboard(@RequestParam(required = false) Integer threshold) {
        Map<String, Object> resp = new LinkedHashMap<>(panelReportService.resumen());
        Map<String, Object> catalogo = lowStockSeguro(threshold);
        List<Map<String, Object>> bajo = bajoStock(catalogo);
        resp.put("lowStockCount", bajo.size());
        resp.put("lowStockThreshold", catalogo.get("threshold"));
        resp.put("lowStock", bajo.stream().limit(5).toList());
        return resp;
    }

    // ─── Reporte de ventas ──────────────────────────────────────

    /**
     * Ventas de un período comparadas con el anterior del mismo largo.
     *
     * Fechas en formato aaaa-mm-dd. Sin parámetros, los últimos 30 días.
     */
    @GetMapping("/reports/sales")
    public ResponseEntity<?> sales(@RequestParam(required = false) String desde,
                                   @RequestParam(required = false) String hasta,
                                   @RequestParam(defaultValue = "10") int top) {
        try {
            return ResponseEntity.ok(panelReportService.ventas(
                    fecha(desde, "desde"), fecha(hasta, "hasta"), Math.max(1, Math.min(50, top))));
        } catch (CartService.CartException e) {
            return ResponseEntity.badRequest().body(Map.of("statusCode", 400, "message", e.getMessage()));
        }
    }

    /** Una fecha mal escrita es un 400 que dice cuál, no un 500. */
    private static LocalDate fecha(String valor, String campo) {
        if (valor == null || valor.isBlank()) return null;
        try {
            return LocalDate.parse(valor.trim());
        } catch (DateTimeParseException e) {
            throw new CartService.CartException(
                    "La fecha '" + campo + "' no es válida: usa el formato aaaa-mm-dd");
        }
    }

    /** Un catálogo caído no debe tumbar el panel entero. */
    /**
     * Stock bajo del catálogo: la lista y el umbral que catalog aplicó.
     *
     * `threshold` normalmente llega null —el panel ya no manda ninguno— y
     * entonces decide catalog, que es quien tiene los productos. Se deja
     * aceptar uno explícito para poder consultar otro corte sin tocar la
     * regla.
     */
    private Map<String, Object> lowStockSeguro(Integer threshold) {
        try {
            Map<String, Object> resp = catalogReportingClient.lowStock(threshold);
            return resp == null ? Map.of() : resp;
        } catch (Exception e) {
            log.error("No se pudo leer el stock bajo del catálogo: {}", e.getMessage());
            return Map.of();
        }
    }

    /** La lista de productos de esa respuesta, vacía si el catálogo no contestó. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> bajoStock(Map<String, Object> respuesta) {
        Object data = respuesta.get("data");
        return data instanceof List<?> lista ? (List<Map<String, Object>>) lista : List.of();
    }
}
