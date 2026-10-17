package com.bytemarket.order.service;

import com.bytemarket.order.model.Order;
import com.bytemarket.order.repository.IOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Los números del panel: el resumen de la portada y el reporte de ventas.
 *
 * Qué cambia respecto a lo que había. Las tarjetas del panel mostraban
 * CONTEOS —pedidos totales, pendientes, entregados, productos activos—, que
 * son estadísticas: se leen, no se hacen. Ahora muestran dinero y trabajo
 * pendiente, y cada una lleva a la lista ya filtrada. "Pagos por validar: 3"
 * es una tarea; "Pedidos totales: 147" no es nada.
 *
 * El reporte compara siempre contra el período anterior del MISMO largo. Una
 * cifra sola no dice si el negocio va bien: 4.200 soles en noviembre solo
 * significa algo al lado de los 3.100 de octubre.
 *
 * Todo se calcula contra la base de pedidos, sin llamar a catalog. El stock
 * bajo sí vive allí y lo pide el controlador aparte, con su fallback: un
 * catálogo caído deja la tarjeta de stock vacía, no tumba el panel.
 */
@Service
@RequiredArgsConstructor
public class PanelReportService {

    /** Estados que siguen esperando algo de la tienda. */
    public static final List<String> ABIERTOS =
            List.of("pending", "voucher_sent", "payment_validated", "in_preparation");

    /** Nombre legible de cada canal, para no mostrar "whatsapp" en crudo. */
    private static final Map<String, String> CANALES = Map.of(
            "web", "Tienda web",
            "caja", "Caja",
            "whatsapp", "WhatsApp",
            "instagram", "Instagram",
            "facebook", "Facebook",
            "telefono", "Teléfono",
            "otro", "Otro");

    private final IOrderRepository orderRepository;

    // ─── Resumen de la portada ──────────────────────────────────

    public Map<String, Object> resumen() {
        // El día y el mes son los de Lima; los bordes se traducen a UTC,
        // que es como están guardadas las fechas. Ver HoraDeLima.
        LocalDate hoy = HoraDeLima.hoy();
        LocalDateTime inicioMes = HoraDeLima.inicioDe(hoy.withDayOfMonth(1));
        LocalDateTime inicioDia = HoraDeLima.inicioDe(hoy);
        LocalDateTime ahora = HoraDeLima.ahora();

        Map<String, Object> mes = orderRepository.cobradoEntre(inicioMes, ahora);
        Map<String, Object> dia = orderRepository.cobradoEntre(inicioDia, ahora);
        Map<String, Object> pendiente = orderRepository.porCobrar();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("salesMonth", decimal(mes.get("amount")));
        resp.put("paidOrdersMonth", entero(mes.get("orders")));
        resp.put("salesToday", decimal(dia.get("amount")));
        resp.put("paidOrdersToday", entero(dia.get("orders")));
        // Comprobante subido y esperando que alguien lo revise: es la tarea
        // más urgente del panel, porque el cliente ya pagó y está esperando.
        resp.put("paymentsToValidate", orderRepository.countByStatus("voucher_sent"));
        resp.put("ordersToAttend", orderRepository.contarPorEstados(ABIERTOS));
        resp.put("receivableOrders", entero(pendiente.get("orders")));
        resp.put("receivableAmount", decimal(pendiente.get("amount")));
        return resp;
    }

    // ─── Reporte de ventas de un período ────────────────────────

    /**
     * @param desde primer día incluido; si falta, los últimos 30 días
     * @param hasta último día incluido; si falta, hoy
     */
    public Map<String, Object> ventas(LocalDate desde, LocalDate hasta, int tope) {
        LocalDate fin = hasta == null ? HoraDeLima.hoy() : hasta;
        LocalDate ini = desde == null ? fin.minusDays(29) : desde;
        if (ini.isAfter(fin)) {
            throw new CartService.CartException("La fecha inicial debe ser anterior a la final");
        }
        long dias = ChronoUnit.DAYS.between(ini, fin) + 1;
        if (dias > 366) {
            throw new CartService.CartException("Elige un período de hasta un año");
        }

        // El período anterior del mismo largo, pegado al actual: comparar 30
        // días con 7 no diría nada.
        LocalDate previoFin = ini.minusDays(1);
        LocalDate previoIni = previoFin.minusDays(dias - 1);

        Map<String, Object> actual = totales(ini, fin);
        Map<String, Object> previo = totales(previoIni, previoFin);
        Map<String, Object> pendiente = orderRepository.porCobrar();

        LocalDateTime a = HoraDeLima.inicioDe(ini);
        LocalDateTime b = HoraDeLima.finDe(fin);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("from", ini.toString());
        resp.put("to", fin.toString());
        resp.put("days", dias);
        resp.put("previousFrom", previoIni.toString());
        resp.put("previousTo", previoFin.toString());
        resp.put("current", actual);
        resp.put("previous", previo);
        resp.put("series", serieCompleta(ini, fin));
        resp.put("byChannel", etiquetarCanales(orderRepository.ventasPorCanal(a, b)));
        resp.put("byPayment", normalizar(orderRepository.ventasPorFormaDePago(a, b)));
        resp.put("topProducts", normalizar(orderRepository.masVendidosEntre(a, b, PageRequest.of(0, tope))));
        resp.put("coupons", normalizar(orderRepository.cuponesUsados(a, b)));
        resp.put("receivable", Map.of(
                "orders", entero(pendiente.get("orders")),
                "amount", decimal(pendiente.get("amount"))));
        return resp;
    }

    private Map<String, Object> totales(LocalDate ini, LocalDate fin) {
        LocalDateTime a = HoraDeLima.inicioDe(ini);
        LocalDateTime b = HoraDeLima.finDe(fin);

        Map<String, Object> cobrado = orderRepository.cobradoEntre(a, b);
        double amount = decimal(cobrado.get("amount"));
        long orders = entero(cobrado.get("orders"));
        Long unidades = orderRepository.unidadesEntre(a, b);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("revenue", amount);
        m.put("orders", orders);
        m.put("units", unidades == null ? 0L : unidades);
        // Ticket medio: lo que deja un pedido. Es el número que dice si la
        // tienda vende más porque vienen más clientes o porque cada uno
        // compra más, y son dos problemas distintos.
        m.put("averageTicket", orders == 0 ? 0d : redondear(amount / orders));
        return m;
    }

    /**
     * La serie con TODOS los días del rango, incluidos los que no vendieron.
     *
     * La consulta solo devuelve los días con pedidos; si se pintan tal cual,
     * una semana sin ventas se comprime y la gráfica miente sobre el ritmo.
     */
    private List<Map<String, Object>> serieCompleta(LocalDate ini, LocalDate fin) {
        Map<String, Map<String, Object>> porDia = new LinkedHashMap<>();
        for (LocalDate d = ini; !d.isAfter(fin); d = d.plusDays(1)) {
            Map<String, Object> vacio = new LinkedHashMap<>();
            vacio.put("day", d.toString());
            vacio.put("amount", 0d);
            vacio.put("orders", 0L);
            porDia.put(d.toString(), vacio);
        }
        for (Map<String, Object> fila : orderRepository.ventasPorDiaEntre(
                HoraDeLima.inicioDe(ini), HoraDeLima.finDe(fin))) {
            String clave = String.valueOf(fila.get("day"));
            Map<String, Object> dia = porDia.get(clave);
            if (dia == null) continue;
            dia.put("amount", decimal(fila.get("amount")));
            dia.put("orders", entero(fila.get("orders")));
        }
        return new ArrayList<>(porDia.values());
    }

    private List<Map<String, Object>> etiquetarCanales(List<Map<String, Object>> filas) {
        List<Map<String, Object>> salida = new ArrayList<>();
        for (Map<String, Object> f : filas) {
            String clave = String.valueOf(f.get("label"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", clave);
            m.put("label", CANALES.getOrDefault(clave, clave));
            m.put("amount", decimal(f.get("amount")));
            m.put("orders", entero(f.get("orders")));
            salida.add(m);
        }
        return salida;
    }

    /** Pasa los BigDecimal/BigInteger del driver a números que el JSON entiende. */
    private List<Map<String, Object>> normalizar(List<Map<String, Object>> filas) {
        List<Map<String, Object>> salida = new ArrayList<>();
        for (Map<String, Object> f : filas) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", f.get("label") == null ? "—" : String.valueOf(f.get("label")));
            m.put("amount", decimal(f.get("amount")));
            if (f.containsKey("units")) m.put("units", entero(f.get("units")));
            if (f.containsKey("orders")) m.put("orders", entero(f.get("orders")));
            salida.add(m);
        }
        return salida;
    }

    /** Variación porcentual contra el período anterior, o null si no había nada que comparar. */
    public static Double variacion(double actual, double previo) {
        if (previo == 0d) return actual == 0d ? 0d : null;
        return redondear(((actual - previo) / previo) * 100d);
    }

    private static double decimal(Object v) {
        if (v == null) return 0d;
        if (v instanceof Number n) return redondear(n.doubleValue());
        try {
            return redondear(Double.parseDouble(v.toString()));
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    private static long entero(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static double redondear(double v) {
        return Math.round(v * 100d) / 100d;
    }
}
