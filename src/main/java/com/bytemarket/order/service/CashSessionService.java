package com.bytemarket.order.service;

import com.bytemarket.order.model.CashCount;
import com.bytemarket.order.model.CashMovement;
import com.bytemarket.order.model.CashSession;
import com.bytemarket.order.repository.ICashCountRepository;
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
 * Aperturar la caja, moverla y cuadrarla.
 *
 * Quién hace qué es la mitad del diseño:
 *
 *   el jefe de ventas  apertura con el fondo, mueve el efectivo y arquea
 *   el cajero          cobra, y nada más
 *
 * El cajero no se abre su propia caja ni declara lo que hay dentro. Cuando
 * las dos cosas eran de la misma persona, el arqueo lo firmaba justamente
 * a quien vigila: salía un papel, pero no era un control. Esa separación se
 * sostiene en SecurityConfig, que solo le deja al cajero leer su turno.
 *
 * El cuadre es una resta, y toda la dificultad está en qué entra en ella.
 * Por forma de pago:
 *
 *   efectivo        esperado = fondo + ventas en efectivo + ingresos − retiros − gastos
 *   resto de formas esperado = ventas del turno por esa forma
 *
 * El fondo y los movimientos solo cuentan en el efectivo porque es el único
 * dinero que pasa por el cajón. Sumar Yape al esperado del efectivo es el
 * error típico al montar esto, y hace que la caja descuadre siempre por la
 * misma cantidad sin que nadie sepa por qué.
 *
 * Pero las demás formas tampoco se quedan sin arqueo: cada una se cuenta
 * contra su propio comprobante —el cierre de lote del POS, los abonos de
 * Yape y Plin—. Sin eso, una venta cobrada mal por Yape no aparece hasta
 * que alguien revise el banco a fin de mes.
 *
 * Todo lo del arqueo se congela al cerrar. Recalcularlo al leer el
 * histórico daría otra cifra en cuanto se anule una venta de ese turno, y
 * un arqueo firmado no puede cambiar a posteriori.
 */
@Service
@RequiredArgsConstructor
public class CashSessionService {

    /** Lo que impide aperturar, cerrar o vender. */
    public static class CajaException extends RuntimeException {
        public CajaException(String mensaje) {
            super(mensaje);
        }
    }

    /** Una forma de pago con lo que el sistema dice que entró por ella. */
    public record Esperado(String method, String label, double amount, int sales) {}

    private final ICashSessionRepository sessionRepository;
    private final ICashMovementRepository movementRepository;
    private final ICashCountRepository countRepository;

    /** La caja abierta de un cajero, si la tiene. */
    public CashSession abiertaDe(Long cajeroId) {
        if (cajeroId == null) return null;
        return sessionRepository.findFirstByCashierIdAndStatus(cajeroId, CashSession.ABIERTA).orElse(null);
    }

    /** Todas las cajas abiertas: lo que el encargado tiene pendiente de cuadrar. */
    public List<CashSession> abiertas() {
        return sessionRepository.findByStatusOrderByOpenedAtAsc(CashSession.ABIERTA);
    }

    /**
     * La caja que debe usar una venta de mostrador.
     *
     * Lanza si el cajero no tiene ninguna abierta, y eso es el punto de
     * todo esto: una venta que no pertenece a ningún turno no aparece en
     * ningún arqueo, y con una sola así el cuadre deja de significar nada.
     */
    @Transactional
    public CashSession exigirAbierta(Long cajeroId) {
        // Bloquea la fila: cobrar y cerrar no pueden solaparse, o una venta
        // acabaría dentro de un turno cuyo arqueo ya se calculó sin ella.
        CashSession s = cajeroId == null ? null
                : sessionRepository.bloquearAbierta(cajeroId, CashSession.ABIERTA).orElse(null);
        if (s == null) {
            throw new CajaException(
                    "No tienes una caja abierta. Tu jefe de ventas tiene que aperturarla con el fondo.");
        }
        return s;
    }

    /**
     * Apertura la caja de un cajero.
     *
     * `quienApertura` y `cajeroId` pueden ser la misma persona —el encargado
     * que también atiende— pero son dos datos distintos a propósito: el
     * papel del arqueo tiene que poder decir quién entregó el fondo y quién
     * lo recibió.
     *
     * El nombre del cajero llega del cliente y se guarda como etiqueta para
     * el papel. El permiso no se decide con él nunca: lo que manda es el id.
     */
    @Transactional
    public CashSession abrir(Long quienApertura, String nombreQuienApertura,
                            Long cajeroId, String cajeroNombre, double fondo) {
        if (quienApertura == null) throw new CajaException("No se pudo identificar quién apertura la caja");
        if (cajeroId == null) throw new CajaException("Elige para qué cajero se apertura la caja");
        if (fondo < 0) throw new CajaException("El fondo inicial no puede ser negativo");
        if (abiertaDe(cajeroId) != null) {
            throw new CajaException("Ese cajero ya tiene una caja abierta. Hay que cuadrarla antes de aperturar otra.");
        }

        CashSession s = new CashSession();
        s.setCashierId(cajeroId);
        s.setCashierName(limpio(cajeroNombre));
        s.setOpenedBy(quienApertura);
        s.setOpenedByName(limpio(nombreQuienApertura));
        s.setOpenedAt(LocalDateTime.now());
        s.setOpeningFloat(redondear(fondo));
        s.setStatus(CashSession.ABIERTA);
        return sessionRepository.save(s);
    }

    @Transactional
    public CashMovement registrarMovimiento(Integer sessionId, Long quien, String tipo,
                                            double importe, String motivo) {
        CashSession s = exigirTurno(sessionId);
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
        m.setCreatedBy(quien);
        return movementRepository.save(m);
    }

    /**
     * Cierra con el arqueo completo.
     *
     * `contado` trae lo que el encargado contó de cada forma: el efectivo
     * del cajón, el cierre de lote del POS, los abonos de Yape y de Plin.
     * Se le piden antes de enseñarle los esperados a propósito: quien ve la
     * cifra objetivo deja de contar y la copia, y entonces el arqueo no
     * detecta nada —que es justo para lo que existe—.
     *
     * Exige una cifra por cada forma que tuvo ventas. Dejar una sin declarar
     * daría un arqueo a medias que parece completo, y es peor que no tenerlo:
     * el papel saldría firmado dando por bueno un dinero que nadie contó.
     */
    @Transactional
    public CashSession cerrar(Integer sessionId, Long quienCierra,
                              Map<String, Double> contado, String notas) {
        // exigirTurno bloquea la fila, así que desde aquí hasta el commit
        // ninguna venta puede sumarse a este turno: lo que entre en el
        // esperado es exactamente lo que acabará dentro.
        CashSession s = exigirTurno(sessionId);
        Map<String, Double> declarado = contado == null ? Map.of() : contado;

        List<Esperado> esperados = esperadoPorForma(s);
        for (Esperado e : esperados) {
            Double v = declarado.get(e.method());
            if (v == null) {
                throw new CajaException("Falta contar " + e.label().toLowerCase()
                        + ": hubo ventas por esa forma y el arqueo no puede dejarla en blanco.");
            }
            if (v < 0) throw new CajaException("Lo contado en " + e.label().toLowerCase() + " no puede ser negativo");
        }

        List<CashCount> lineas = new ArrayList<>();
        for (Esperado e : esperados) {
            CashCount c = new CashCount();
            c.setSession(s);
            c.setMethod(e.method());
            c.setLabel(e.label());
            c.setExpected(redondear(e.amount()));
            c.setCounted(redondear(declarado.get(e.method())));
            c.setDifference(redondear(c.getCounted() - c.getExpected()));
            c.setSalesCount(e.sales());
            lineas.add(c);
        }
        countRepository.saveAll(lineas);

        // El efectivo se queda además en el propio turno: es la cifra que
        // manda —la del cajón— y así el histórico la lee sin recorrer el
        // desglose.
        Esperado efectivo = esperados.get(0);
        double contadoEfectivo = redondear(declarado.get(efectivo.method()));

        s.setClosedBy(quienCierra);
        s.setClosedAt(LocalDateTime.now());
        s.setCountedCash(contadoEfectivo);
        s.setExpectedCash(redondear(efectivo.amount()));
        s.setDifference(redondear(contadoEfectivo - efectivo.amount()));
        s.setClosingNotes(notas == null || notas.isBlank() ? null : notas.trim());
        s.setStatus(CashSession.CERRADA);
        return sessionRepository.save(s);
    }

    /**
     * Lo que debería haber de cada forma de pago.
     *
     * El efectivo va siempre primero y siempre está, aunque no se haya
     * cobrado nada en efectivo: el fondo inicial sigue en el cajón y hay que
     * devolverlo. Las demás formas solo aparecen si tuvieron ventas —pedir
     * que cuenten el POS un día que nadie pagó con tarjeta es ruido—.
     */
    public List<Esperado> esperadoPorForma(CashSession s) {
        List<Esperado> out = new ArrayList<>();
        int ventasEnEfectivo = 0;
        List<Esperado> resto = new ArrayList<>();

        for (Map<String, Object> fila : sessionRepository.ventasPorForma(s.getId())) {
            String clave = String.valueOf(fila.get("forma"));
            int ventas = (int) numero(fila.get("ventas"));
            if (EFECTIVO.equals(clave)) {
                ventasEnEfectivo = ventas;
                continue;
            }
            resto.add(new Esperado(clave, etiqueta(clave), redondear(numero(fila.get("importe"))), ventas));
        }

        out.add(new Esperado(EFECTIVO, etiqueta(EFECTIVO), efectivoEnCaja(s), ventasEnEfectivo));
        out.addAll(resto);
        return out;
    }

    /** Lo que debería haber en el cajón ahora mismo. */
    public double efectivoEnCaja(CashSession s) {
        double ventas = sessionRepository.cobradoEnEfectivo(s.getId());
        double movimientos = movementRepository.findBySessionIdOrderByCreatedAtAsc(s.getId())
                .stream().mapToDouble(CashMovement::delta).sum();
        return redondear(s.getOpeningFloat() + ventas + movimientos);
    }

    /**
     * El estado del turno para la pantalla del cajero.
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
        m.put("cashierId", s.getCashierId());
        m.put("cashierName", s.getCashierName());
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
            m.put("counts", countRepository.findBySessionIdOrderByMethodAsc(s.getId())
                    .stream().map(CashSessionService::arqueoDto).toList());
        }
        return m;
    }

    /**
     * El turno como lo ve quien va a arquearlo.
     *
     * Mientras está abierto va SIN importes: ni ventas, ni efectivo, ni
     * desglose. Solo cuántas ventas lleva y de qué formas, para que el
     * encargado sepa qué comprobantes tiene que juntar.
     *
     * Es la misma razón por la que no se enseña el esperado, llevada a su
     * conclusión: contar Yape teniendo delante «Yape: 900» no es contar. Y
     * al cajero sí se le muestra lo que cobró en su propia barra, porque él
     * no es quien cuenta.
     */
    public Map<String, Object> paraArqueo(CashSession s) {
        boolean abierta = CashSession.ABIERTA.equals(s.getStatus());
        if (!abierta) return resumen(s);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("abierta", true);
        m.put("id", s.getId());
        m.put("cashierId", s.getCashierId());
        m.put("cashierName", s.getCashierName());
        m.put("openedByName", s.getOpenedByName());
        m.put("openedAt", s.getOpenedAt());
        m.put("openingFloat", s.getOpeningFloat());

        List<Map<String, Object>> formas = new ArrayList<>();
        int ventas = 0;
        for (Esperado e : esperadoPorForma(s)) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("type", e.method());
            f.put("label", e.label());
            f.put("sales", e.sales());
            formas.add(f);
            ventas += e.sales();
        }
        m.put("toCount", formas);
        m.put("salesCount", ventas);
        m.put("movementsCount", movementRepository.findBySessionIdOrderByCreatedAtAsc(s.getId()).size());
        return m;
    }

    /** Un turno abierto por id, con su fila bloqueada. */
    private CashSession exigirTurno(Integer sessionId) {
        if (sessionId == null) throw new CajaException("Falta decir de qué caja se trata");
        CashSession s = sessionRepository.bloquearPorId(sessionId)
                .orElseThrow(() -> new CajaException("Esa caja no existe"));
        if (!CashSession.ABIERTA.equals(s.getStatus())) {
            throw new CajaException("Esa caja ya está cerrada. Un arqueo cerrado no se vuelve a tocar.");
        }
        return s;
    }

    private List<Map<String, Object>> ventasPorForma(CashSession s) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> fila : sessionRepository.ventasPorForma(s.getId())) {
            Map<String, Object> f = new LinkedHashMap<>();
            String clave = String.valueOf(fila.get("forma"));
            f.put("type", clave);
            f.put("label", etiqueta(clave));
            f.put("amount", redondear(numero(fila.get("importe"))));
            f.put("sales", (int) numero(fila.get("ventas")));
            out.add(f);
        }
        return out;
    }

    private static final String EFECTIVO = "efectivo";

    private static String etiqueta(String forma) {
        return PanelSaleService.FORMAS_DE_PAGO.getOrDefault(forma, forma);
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

    private static Map<String, Object> arqueoDto(CashCount c) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", c.getMethod());
        d.put("label", c.getLabel());
        d.put("expected", c.getExpected());
        d.put("counted", c.getCounted());
        d.put("difference", c.getDifference());
        d.put("sales", c.getSalesCount());
        return d;
    }

    private static String limpio(String v) {
        return v == null || v.isBlank() ? null : v.trim();
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
