package com.bytemarket.order.service;

import com.bytemarket.order.model.CashMovement;
import com.bytemarket.order.model.CashSession;
import com.bytemarket.order.repository.ICashMovementRepository;
import com.bytemarket.order.repository.ICashSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Abrir la caja, moverla y cuadrarla.
 *
 * El cuadre es una resta, y toda la dificultad está en qué entra en ella:
 *
 *   esperado = fondo + ventas en efectivo + ingresos − retiros − gastos
 *   diferencia = contado − esperado
 *
 * Solo efectivo. Yape, tarjeta y transferencia se devuelven aparte para
 * que el encargado vea el total del turno, pero NO entran en el esperado:
 * ese dinero no pasó por el cajón. Sumarlo es el error típico, y hace que
 * la caja descuadre siempre por la misma cantidad sin que nadie sepa por
 * qué.
 *
 * El esperado se congela al cerrar. Recalcularlo después al leer el
 * histórico daría una cifra distinta en cuanto se anule una venta de ese
 * turno, y un arqueo firmado no puede cambiar a posteriori.
 */
@Service
@RequiredArgsConstructor
public class CashSessionService {

    /** Lo que impide abrir, cerrar o vender. */
    public static class CajaException extends RuntimeException {
        public CajaException(String mensaje) {
            super(mensaje);
        }
    }

    private final ICashSessionRepository sessionRepository;
    private final ICashMovementRepository movementRepository;

    /** La caja abierta de alguien, si la tiene. */
    public CashSession abiertaDe(Long userId) {
        if (userId == null) return null;
        return sessionRepository.findFirstByOpenedByAndStatus(userId, CashSession.ABIERTA).orElse(null);
    }

    /**
     * La caja que debe usar una venta de mostrador.
     *
     * Lanza si no hay ninguna abierta, y eso es el punto de todo esto: una
     * venta que no pertenece a ningún turno no aparece en ningún arqueo, y
     * con una sola así el cuadre deja de significar nada.
     */
    public CashSession exigirAbierta(Long userId) {
        CashSession s = abiertaDe(userId);
        if (s == null) {
            throw new CajaException(
                    "No tienes una caja abierta. Ábrela con el fondo inicial antes de cobrar.");
        }
        return s;
    }

    @Transactional
    public CashSession abrir(Long userId, String userName, double fondo) {
        if (userId == null) throw new CajaException("No se pudo identificar quién abre la caja");
        if (fondo < 0) throw new CajaException("El fondo inicial no puede ser negativo");
        if (abiertaDe(userId) != null) {
            throw new CajaException("Ya tienes una caja abierta. Ciérrala antes de abrir otra.");
        }

        CashSession s = new CashSession();
        s.setOpenedBy(userId);
        s.setOpenedByName(userName);
        s.setOpenedAt(LocalDateTime.now());
        s.setOpeningFloat(redondear(fondo));
        s.setStatus(CashSession.ABIERTA);
        return sessionRepository.save(s);
    }

    @Transactional
    public CashMovement registrarMovimiento(Long userId, String tipo, double importe, String motivo) {
        CashSession s = exigirAbierta(userId);
        if (!List.of(CashMovement.INGRESO, CashMovement.RETIRO, CashMovement.GASTO).contains(tipo)) {
            throw new CajaException("Tipo de movimiento no válido");
        }
        if (importe <= 0) throw new CajaException("El importe debe ser mayor que cero");
        if (motivo == null || motivo.isBlank()) {
            // Un retiro de 200 soles sin explicar es indistinguible de un
            // faltante cuando alguien revise el turno dentro de un mes.
            throw new CajaException("Escribe el motivo: un movimiento sin explicación no sirve para nada");
        }

        double enCaja = efectivoEnCaja(s);
        if (!CashMovement.INGRESO.equals(tipo) && importe > enCaja) {
            throw new CajaException("No puedes sacar " + money(importe)
                    + ": en la caja hay " + money(enCaja) + ".");
        }

        CashMovement m = new CashMovement();
        m.setSession(s);
        m.setType(tipo);
        m.setAmount(redondear(importe));
        m.setReason(motivo.trim());
        m.setCreatedBy(userId);
        return movementRepository.save(m);
    }

    /**
     * Cierra con el arqueo.
     *
     * `contado` es lo que la persona contó físicamente. Se le pide antes de
     * enseñarle el esperado a propósito: quien ve la cifra objetivo deja de
     * contar y la copia, y entonces el arqueo no detecta nada.
     */
    @Transactional
    public CashSession cerrar(Long userId, double contado, String notas) {
        CashSession s = exigirAbierta(userId);
        if (contado < 0) throw new CajaException("Lo contado no puede ser negativo");

        double esperado = efectivoEnCaja(s);
        double diferencia = redondear(contado - esperado);

        s.setClosedBy(userId);
        s.setClosedAt(LocalDateTime.now());
        s.setCountedCash(redondear(contado));
        s.setExpectedCash(redondear(esperado));
        s.setDifference(diferencia);
        s.setClosingNotes(notas == null || notas.isBlank() ? null : notas.trim());
        s.setStatus(CashSession.CERRADA);
        return sessionRepository.save(s);
    }

    /** Lo que debería haber en el cajón ahora mismo. */
    public double efectivoEnCaja(CashSession s) {
        double ventas = sessionRepository.cobradoEnEfectivo(s.getId());
        double movimientos = movementRepository.findBySessionIdOrderByCreatedAtAsc(s.getId())
                .stream().mapToDouble(CashMovement::delta).sum();
        return redondear(s.getOpeningFloat() + ventas + movimientos);
    }

    /**
     * El estado del turno para la pantalla.
     *
     * `expectedCash` solo va cuando la caja ya está cerrada. Mientras está
     * abierta se omite: si se enseñara, el arqueo sería copiar un número.
     */
    public Map<String, Object> resumen(CashSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (s == null) {
            m.put("abierta", false);
            return m;
        }
        boolean abierta = CashSession.ABIERTA.equals(s.getStatus());
        m.put("abierta", abierta);
        m.put("id", s.getId());
        m.put("openedByName", s.getOpenedByName());
        m.put("openedAt", s.getOpenedAt());
        m.put("openingFloat", s.getOpeningFloat());
        m.put("cashSales", redondear(sessionRepository.cobradoEnEfectivo(s.getId())));
        m.put("byPaymentType", ventasPorForma(s));

        List<CashMovement> movimientos = movementRepository.findBySessionIdOrderByCreatedAtAsc(s.getId());
        m.put("movements", movimientos.stream().map(CashSessionService::movimientoDto).toList());
        m.put("movementsTotal", redondear(movimientos.stream().mapToDouble(CashMovement::delta).sum()));

        if (!abierta) {
            m.put("closedAt", s.getClosedAt());
            m.put("countedCash", s.getCountedCash());
            m.put("expectedCash", s.getExpectedCash());
            m.put("difference", s.getDifference());
            m.put("closingNotes", s.getClosingNotes());
        }
        return m;
    }

    private List<Map<String, Object>> ventasPorForma(CashSession s) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> fila : sessionRepository.ventasPorForma(s.getId())) {
            Map<String, Object> f = new LinkedHashMap<>();
            String clave = String.valueOf(fila.get("forma"));
            f.put("type", clave);
            f.put("label", PanelSaleService.FORMAS_DE_PAGO.getOrDefault(clave, clave));
            f.put("amount", redondear(numero(fila.get("importe"))));
            f.put("sales", (int) numero(fila.get("ventas")));
            out.add(f);
        }
        return out;
    }

    private static Map<String, Object> movimientoDto(CashMovement m) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", m.getId());
        d.put("type", m.getType());
        d.put("amount", m.getAmount());
        d.put("delta", m.delta());
        d.put("reason", m.getReason());
        d.put("createdAt", m.getCreatedAt());
        return d;
    }

    private static double numero(Object v) {
        return v == null ? 0d : Double.parseDouble(v.toString());
    }

    /** Dos decimales: es dinero, y los flotantes arrastran colas. */
    private static double redondear(double v) {
        return Math.round(v * 100d) / 100d;
    }

    private static String money(double v) {
        return "S/ " + String.format("%.2f", v);
    }
}
