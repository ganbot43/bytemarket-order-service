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
 * El turno de caja: asignar el fondo, abrir, mover, arquear, cerrar y
 * cuadrar.
 *
 * Quién puede llamar a qué lo decide SecurityConfig, y la lista es corta.
 * El cajero solo tiene tres cosas: ver su turno, abrirlo y cerrarlo. Todo
 * lo demás —entregar el fondo, mover el efectivo, arquear y cuadrar— es del
 * jefe de ventas o del encargado, porque quien vende no cuenta su propio
 * cajón.
 *
 * Por eso /sesion, /abrir y /cerrar cuelgan del usuario del token —la caja
 * es de quien cobra con ella— mientras que lo del jefe lleva siempre a qué
 * turno se refiere: él no actúa sobre su caja sino sobre la de otro.
 */
@RestController
@RequestMapping("/api/admin/caja")
@RequiredArgsConstructor
public class ApiAdminCashSessionController {

    private final CashSessionService cashSessionService;
    private final ICashSessionRepository sessionRepository;

    // ─── El cajero ──────────────────────────────────────────────

    /** Su turno, en el estado que esté. Es lo que mira su pantalla. */
    @GetMapping("/sesion")
    public Map<String, Object> sesionActual() {
        return cashSessionService.resumen(cashSessionService.turnoDe(usuarioId()));
    }

    /** Confirma la apertura y acepta el fondo. El ticket lo firma él. */
    @PostMapping("/abrir")
    public ResponseEntity<?> abrir() {
        try {
            CashSession s = cashSessionService.abrir(usuarioId());
            return ResponseEntity.status(HttpStatus.CREATED).body(cashSessionService.resumen(s));
        } catch (CashSessionService.CajaException e) {
            return error(e);
        }
    }

    /**
     * Cierra su turno al acabar. No declara ninguna cifra: cerrar es «he
     * acabado», no «esto es lo que hay». Contar es del encargado.
     */
    @PostMapping("/cerrar")
    public ResponseEntity<?> cerrar() {
        try {
            /*
             * Sin cuerpo: cierra SU turno, el que encuentra por su id de
             * usuario. Aceptaba un `sessionId` opcional y no servía para
             * nada bueno —el servicio rechaza el de otro igualmente— pero
             * invitaba a probar con ids ajenos y dejaba la ruta dependiendo
             * de dos fuentes de verdad para decir de qué caja se habla.
             */
            Long yo = usuarioId();
            CashSession mio = cashSessionService.turnoDe(yo);
            CashSession s = cashSessionService.cerrar(mio == null ? null : mio.getId(), yo);
            return ResponseEntity.ok(cashSessionService.resumen(s));
        } catch (CashSessionService.CajaException e) {
            return error(e);
        }
    }

    // ─── El jefe de ventas ──────────────────────────────────────

    /**
     * Los turnos vivos, con sus importes.
     *
     * Aquí sí van las cifras: es la pantalla con la que el encargado hace
     * el arqueo sorpresivo —mira cuánto lleva vendido el cajero en efectivo
     * y con eso se pone a contar billetes—. El vigilado es el cajero, y el
     * cajero no ve esta pantalla.
     */
    @GetMapping("/sesiones/activas")
    public Map<String, Object> activas() {
        List<Map<String, Object>> data = cashSessionService.vivos().stream()
                .map(cashSessionService::paraElJefe).toList();
        return Map.of("data", data, "total", data.size());
    }

    /** Alias del anterior: la pantalla antigua lo pedía con este nombre. */
    @GetMapping("/sesiones/abiertas")
    public Map<String, Object> abiertas() {
        return activas();
    }

    /**
     * Registra el fondo de un cajero y lo habilita para abrir.
     *
     * `cashierName` y `cashierDni` son solo las etiquetas que se imprimen
     * en los papeles; lo que identifica al cajero es `cashierId`, y ningún
     * permiso se decide con el nombre.
     */
    @PostMapping("/asignar-fondo")
    public ResponseEntity<?> asignarFondo(@RequestBody Map<String, Object> body) {
        try {
            CashSession s = cashSessionService.asignarFondo(
                    usuarioId(),
                    usuarioNombre(),
                    entero(body.get("cashierId")),
                    texto(body.get("cashierName")),
                    texto(body.get("cashierDni")),
                    decimal(body.get("openingFloat")),
                    !Boolean.FALSE.equals(body.get("habilitar")));
            return ResponseEntity.status(HttpStatus.CREATED).body(cashSessionService.paraElJefe(s));
        } catch (CashSessionService.CajaException e) {
            return error(e);
        }
    }

    @PostMapping("/{id}/habilitar")
    public ResponseEntity<?> habilitar(@PathVariable Integer id) {
        try {
            return ResponseEntity.ok(cashSessionService.paraElJefe(
                    cashSessionService.habilitar(id, usuarioId())));
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
                    texto(body.get("reason")));
            return ResponseEntity.ok(cashSessionService.paraElJefe(
                    sessionRepository.findById(sessionId).orElseThrow()));
        } catch (CashSessionService.CajaException e) {
            return error(e);
        }
    }

    /** Arqueo sorpresivo. No cierra la caja: el cajero sigue vendiendo. */
    @PostMapping("/{id}/arqueo")
    public ResponseEntity<?> arqueo(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        try {
            cashSessionService.arquear(id, usuarioId(), usuarioNombre(),
                    decimal(body.get("countedCash")), texto(body.get("observation")));
            return ResponseEntity.ok(cashSessionService.paraElJefe(
                    sessionRepository.findById(id).orElseThrow()));
        } catch (CashSessionService.CajaException e) {
            return error(e);
        }
    }

    /** Lo que el jefe necesita para cuadrar: esperados y vouchers. */
    @GetMapping("/{id}/cuadre")
    public ResponseEntity<?> datosDeCuadre(@PathVariable Integer id) {
        return sessionRepository.findById(id)
                .<ResponseEntity<?>>map(s -> ResponseEntity.ok(cashSessionService.paraElJefe(s)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("statusCode", 404, "message", "Esa caja no existe")));
    }

    /**
     * Cuadra el turno y deja el papel listo para firmar.
     *
     * El cuerpo trae lo CONTADO de cada forma y cuántos vouchers hay de
     * cada una; si algo no cuadra, la observación es obligatoria.
     */
    @PostMapping("/{id}/cuadrar")
    public ResponseEntity<?> cuadrar(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        try {
            CashSession s = cashSessionService.cuadrar(
                    id,
                    usuarioId(),
                    usuarioNombre(),
                    contado(body.get("counted")),
                    vouchers(body.get("vouchers")),
                    texto(body.get("observation")));
            return ResponseEntity.ok(cashSessionService.paraElJefe(s));
        } catch (CashSessionService.CajaException e) {
            return error(e);
        }
    }

    /** El histórico de turnos cuadrados, para el reporte. */
    @GetMapping("/sesiones")
    public Map<String, Object> sesiones(@RequestParam(defaultValue = "10") int limit,
                                        @RequestParam(defaultValue = "0") int offset) {
        int tamano = Math.max(1, Math.min(100, limit));
        Page<CashSession> page = sessionRepository.findByStatusOrderByClosedAtDesc(
                CashSession.CUADRADA, PageRequest.of(offset / tamano, tamano));

        List<Map<String, Object>> data = page.getContent().stream()
                .map(cashSessionService::resumen).toList();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", data);
        resp.put("total", page.getTotalElements());
        return resp;
    }

    // ─── Fontanería ────────────────────────────────────────────

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
     * servicio lo tomaría por «falta decir de qué caja», que es cierto pero
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

    /** Cuántos vouchers hay de cada forma: {"tarjeta": 15, "yape": 4}. */
    private static Map<String, Integer> vouchers(Object v) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                try {
                    out.put(e.getKey().toString(), Integer.parseInt(e.getValue().toString().trim()));
                } catch (NumberFormatException ignorado) {
                    throw new CashSessionService.CajaException(
                            "El número de vouchers de " + e.getKey() + " no es un número entero");
                }
            }
        }
        return out;
    }

    private static String texto(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
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
