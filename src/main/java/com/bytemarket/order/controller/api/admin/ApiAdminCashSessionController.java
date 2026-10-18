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
 * El turno de caja: abrirlo, moverlo, cuadrarlo y consultarlo.
 *
 * Todo cuelga del usuario del token y no de un id que venga en el cuerpo:
 * la caja es de quien la abre, y dejar elegir de quién es el turno
 * permitiría cerrar la de otro —y cargarle su descuadre—.
 */
@RestController
@RequestMapping("/api/admin/caja")
@RequiredArgsConstructor
public class ApiAdminCashSessionController {

    private final CashSessionService cashSessionService;
    private final ICashSessionRepository sessionRepository;

    /** Lo que la pantalla pregunta al cargar: ¿tengo caja abierta? */
    @GetMapping("/sesion")
    public Map<String, Object> sesionActual() {
        return cashSessionService.resumen(cashSessionService.abiertaDe(usuarioId()));
    }

    @PostMapping("/abrir")
    public ResponseEntity<?> abrir(@RequestBody Map<String, Object> body) {
        try {
            CashSession s = cashSessionService.abrir(usuarioId(), usuarioNombre(), decimal(body.get("openingFloat")));
            return ResponseEntity.status(HttpStatus.CREATED).body(cashSessionService.resumen(s));
        } catch (CashSessionService.CajaException e) {
            return error(e);
        }
    }

    @PostMapping("/movimientos")
    public ResponseEntity<?> movimiento(@RequestBody Map<String, Object> body) {
        try {
            cashSessionService.registrarMovimiento(
                    usuarioId(),
                    String.valueOf(body.get("type")),
                    decimal(body.get("amount")),
                    body.get("reason") == null ? null : body.get("reason").toString());
            return ResponseEntity.ok(cashSessionService.resumen(cashSessionService.abiertaDe(usuarioId())));
        } catch (CashSessionService.CajaException e) {
            return error(e);
        }
    }

    /**
     * Cierra con el arqueo. El cuerpo trae lo CONTADO, no lo esperado: la
     * pantalla no enseña el esperado hasta después de esta llamada.
     */
    @PostMapping("/cerrar")
    public ResponseEntity<?> cerrar(@RequestBody Map<String, Object> body) {
        try {
            CashSession s = cashSessionService.cerrar(
                    usuarioId(),
                    decimal(body.get("countedCash")),
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
