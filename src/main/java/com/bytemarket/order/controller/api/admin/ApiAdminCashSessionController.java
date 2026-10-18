package com.bytemarket.order.controller.api.admin;

import com.bytemarket.order.model.CashSession;
import com.bytemarket.order.repository.ICashSessionRepository;
import com.bytemarket.order.security.CurrentUser;
import com.bytemarket.order.service.CashSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * El turno de caja: aperturarlo, moverlo, arquearlo y consultarlo.
 *
 * Quién puede llamar a qué lo decide SecurityConfig, y la lista es corta:
 * al cajero solo le deja GET /sesion. Aperturar, mover efectivo y arquear
 * son del jefe de ventas o del encargado, porque quien vende no cuadra su
 * propio cajón.
 *
 * Por eso /sesion cuelga del usuario del token y no de un id del cuerpo —la
 * caja es de quien cobra con ella—, mientras que aperturar, mover y cerrar
 * llevan a quién o a cuál se refieren: el encargado no actúa sobre su caja
 * sino sobre la de otro.
 */
@RestController
@RequestMapping("/api/admin/caja")
@RequiredArgsConstructor
public class ApiAdminCashSessionController {

    private final CashSessionService cashSessionService;
    private final ICashSessionRepository sessionRepository;

    /** Lo que la pantalla del cajero pregunta al cargar: ¿tengo caja abierta? */
    @GetMapping("/sesion")
    public Map<String, Object> sesionActual() {
        return cashSessionService.resumen(cashSessionService.abiertaDe(usuarioId()));
    }

    /**
     * Las cajas abiertas ahora mismo, para el encargado.
     *
     * Van sin importes mientras están abiertas: el motivo está en
     * {@link CashSessionService#paraArqueo}.
     */
    @GetMapping("/sesiones/abiertas")
    public Map<String, Object> abiertas() {
        List<Map<String, Object>> data = cashSessionService.abiertas().stream()
                .map(cashSessionService::paraArqueo).toList();
        return Map.of("data", data, "total", data.size());
    }

    /**
     * Apertura la caja de un cajero y le entrega el fondo.
     *
     * `cashierName` es solo la etiqueta que se imprime en el papel; lo que
     * identifica al cajero es `cashierId`, y ningún permiso se decide con
     * el nombre.
     */
    @PostMapping("/abrir")
    public ResponseEntity<?> abrir(@RequestBody Map<String, Object> body) {
        try {
            CashSession s = cashSessionService.abrir(
                    usuarioId(),
                    usuarioNombre(),
                    entero(body.get("cashierId")),
                    body.get("cashierName") == null ? null : body.get("cashierName").toString(),
                    decimal(body.get("openingFloat")));
            return ResponseEntity.status(HttpStatus.CREATED).body(cashSessionService.resumen(s));
        } catch (CashSessionService.CajaException e) {
            return error(e);
        }
    }

    @PostMapping("/movimientos")
    public ResponseEntity<?> movimiento(@RequestBody Map<String, Object> body) {
        try {
            Integer sessionId = idDeSesion(body.get("sessionId"));
            cashSessionService.registrarMovimiento(
                    sessionId,
                    usuarioId(),
                    String.valueOf(body.get("type")),
                    decimal(body.get("amount")),
                    body.get("reason") == null ? null : body.get("reason").toString());
            return ResponseEntity.ok(cashSessionService.paraArqueo(
                    sessionRepository.findById(sessionId).orElseThrow()));
        } catch (CashSessionService.CajaException e) {
            return error(e);
        }
    }

    /**
     * Cierra con el arqueo. El cuerpo trae lo CONTADO de cada forma, no lo
     * esperado: la pantalla no enseña ningún esperado hasta después de esta
     * llamada.
     */
    @PostMapping("/cerrar")
    public ResponseEntity<?> cerrar(@RequestBody Map<String, Object> body) {
        try {
            CashSession s = cashSessionService.cerrar(
                    idDeSesion(body.get("sessionId")),
                    usuarioId(),
                    contado(body.get("counted")),
                    body.get("notes") == null ? null : body.get("notes").toString());
            return ResponseEntity.ok(cashSessionService.resumen(s));
        } catch (CashSessionService.CajaException e) {
            return error(e);
        }
    }

    /** El histórico de turnos cerrados, para el reporte. */
    @GetMapping("/sesiones")
    public Map<String, Object> sesiones(@RequestParam(defaultValue = "10") int limit,
                                        @RequestParam(defaultValue = "0") int offset) {
        int tamano = Math.max(1, Math.min(100, limit));
        Page<CashSession> page = sessionRepository.findByStatusOrderByClosedAtDesc(
                CashSession.CERRADA, PageRequest.of(offset / tamano, tamano));

        List<Map<String, Object>> data = page.getContent().stream()
                .map(cashSessionService::resumen).toList();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", data);
        resp.put("total", page.getTotalElements());
        return resp;
    }

    private static Long usuarioId() {
        return CurrentUser.get().map(u -> u.id()).orElse(null);
    }

    private static String usuarioNombre() {
        return CurrentUser.get().map(u -> u.email()).orElse(null);
    }

    /**
     * El id del turno sobre el que se actúa.
     *
     * Si falta o no es un número se rechaza en vez de caer a null: el
     * servicio lo tomaría por "falta decir de qué caja", que es cierto pero
     * no dice que el dato venía mal.
     */
    private static Integer idDeSesion(Object v) {
        Long id = entero(v);
        if (id == null) throw new CashSessionService.CajaException("Falta decir de qué caja se trata");
        return id.intValue();
    }

    /** Lo contado por forma de pago: {"efectivo": 495.5, "tarjeta": 300}. */
    private static Map<String, Double> contado(Object v) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                try {
                    out.put(e.getKey().toString(), Double.parseDouble(e.getValue().toString()));
                } catch (NumberFormatException ignorado) {
                    throw new CashSessionService.CajaException(
                            "Lo contado en " + e.getKey() + " no es un número");
                }
            }
        }
        return out;
    }

    private static Long entero(Object v) {
        if (v == null) return null;
        try {
            return Long.valueOf(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double decimal(Object v) {
        if (v == null) return 0d;
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    private static ResponseEntity<Map<String, Object>> error(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("statusCode", 409, "message", e.getMessage()));
    }
}
