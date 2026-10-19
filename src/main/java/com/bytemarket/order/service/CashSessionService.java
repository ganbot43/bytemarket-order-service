package com.bytemarket.order.service;

import com.bytemarket.order.model.CashAudit;
import com.bytemarket.order.model.CashLog;
import com.bytemarket.order.model.CashCount;
import com.bytemarket.order.model.CashMovement;
import com.bytemarket.order.model.CashSession;
import com.bytemarket.order.repository.ICashAuditRepository;
import com.bytemarket.order.repository.ICashLogRepository;
import com.bytemarket.order.repository.ICashCountRepository;
import com.bytemarket.order.repository.ICashMovementRepository;
import com.bytemarket.order.repository.ICashSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * El turno de caja, de principio a fin.
 *
 *   el jefe asigna el fondo y habilita   →  fund_assigned / enabled
 *   el cajero confirma y firma su ticket →  open
 *   vende; el jefe puede arquear cuando quiera, sin cerrar nada
 *   el cajero cierra al acabar el turno  →  closed
 *   el jefe cuenta, firma y cuadra       →  settled
 *
 * Quién hace qué es la mitad del diseño. El cajero no se asigna el fondo ni
 * declara lo que hay al final; el jefe no cierra la caja por él. Cuando las
 * dos cosas eran de la misma persona, el arqueo lo firmaba exactamente a
 * quien vigila: salía un papel, pero no era un control.
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
 * Yape y Plin— y además contra el número de vouchers. Quince ventas con
 * tarjeta tienen que ser quince papeles en la mano: si los importes cuadran
 * pero faltan dos vouchers, hay algo que explicar.
 *
 * Todo lo del cuadre se congela al cuadrar. Recalcularlo al leer el
 * histórico daría otra cifra en cuanto se anule una venta de ese turno, y
 * un papel firmado no puede cambiar a posteriori.
 */
@Service
@RequiredArgsConstructor
public class CashSessionService {

    /** Lo que impide asignar, abrir, vender, cerrar o cuadrar. */
    public static class CajaException extends RuntimeException {
        public CajaException(String mensaje) {
            super(mensaje);
        }
    }

    /** Una forma de pago con lo que el sistema dice que entró por ella. */
    public record Esperado(String method, String label, double amount, int sales, int vouchers) {}

    private static final String EFECTIVO = "efectivo";

    private final ICashSessionRepository sessionRepository;
    private final ICashMovementRepository movementRepository;
    private final ICashCountRepository countRepository;
    private final ICashAuditRepository auditRepository;
    private final ICashLogRepository logRepository;

    /**
     * Apunta un paso en el historial del turno.
     *
     * Se llama desde cada cosa que mueve la caja, y a propósito no devuelve
     * nada ni lanza: el historial es para poder reconstruir la mañana, no
     * una condición de la operación. Si un día falla la escritura, lo que
     * no puede pasar es que se caiga el cobro por eso.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void apuntar(Integer sessionId, String accion, Long quien, String nombre, String detalle) {
        if (sessionId == null) return;
        CashLog l = new CashLog();
        l.setSessionId(sessionId);
        l.setAction(accion);
        l.setUserId(quien);
        l.setUserName(limpio(nombre));
        l.setDetail(limpio(detalle));
        logRepository.save(l);
    }

    // ─── El turno de cada quien ─────────────────────────────────

    /** El turno vivo de un cajero, en cualquier estado menos cuadrado. */
    public CashSession turnoDe(Long cajeroId) {
        if (cajeroId == null) return null;
        return sessionRepository.findFirstByCashierIdAndStatusIn(cajeroId, CashSession.VIVOS).orElse(null);
    }

    /** Solo si además está abierta. Lo que mira la pantalla de venta. */
    public CashSession abiertaDe(Long cajeroId) {
        CashSession s = turnoDe(cajeroId);
        return s != null && CashSession.ABIERTA.equals(s.getStatus()) ? s : null;
    }

    /** Todos los turnos vivos: lo que el encargado tiene entre manos. */
    public List<CashSession> vivos() {
        return sessionRepository.findByStatusInOrderByAssignedAtAsc(CashSession.VIVOS);
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
        // acabaría dentro de un turno cuyo cuadre ya se calculó sin ella.
        CashSession s = cajeroId == null ? null
                : sessionRepository.bloquearAbierta(cajeroId, CashSession.ABIERTA).orElse(null);
        if (s == null) {
            CashSession vivo = turnoDe(cajeroId);
            if (vivo != null && CashSession.HABILITADA.equals(vivo.getStatus())) {
                throw new CajaException("Tu caja está habilitada con "
                        + money(vivo.getOpeningFloat()) + ". Ábrela para empezar a cobrar.");
            }
            if (vivo != null && CashSession.FONDO_ASIGNADO.equals(vivo.getStatus())) {
                throw new CajaException("Tu jefe de ventas registró el fondo pero todavía no te habilitó.");
            }
            if (vivo != null && CashSession.CERRADA.equals(vivo.getStatus())) {
                throw new CajaException("Ya cerraste tu caja. Cuádrala con tu jefe antes de volver a vender.");
            }
            throw new CajaException(
                    "No tienes una caja abierta. Tu jefe de ventas tiene que asignarte el fondo.");
        }
        return s;
    }

    // ─── 1. El jefe asigna el fondo ─────────────────────────────

    /**
     * Registra el fondo de un cajero y, salvo que se pida lo contrario, lo
     * habilita para abrir.
     *
     * `quienAsigna` y `cajeroId` pueden ser la misma persona —el encargado
     * que también atiende— pero son dos datos distintos a propósito: el
     * papel de caja tiene que poder decir quién entregó el dinero y quién
     * lo recibió.
     *
     * El nombre y el DNI del cajero llegan del cliente y se guardan como
     * etiquetas para los papeles. El permiso no se decide con ellos nunca:
     * lo que manda es el id.
     */
    @Transactional
    public CashSession asignarFondo(Long quienAsigna, String nombreQuienAsigna,
                                    Long cajeroId, String cajeroNombre, String cajeroDni,
                                    double fondo, boolean habilitar) {
        if (quienAsigna == null) throw new CajaException("No se pudo identificar quién asigna el fondo");
        if (cajeroId == null) throw new CajaException("Elige a qué cajero le asignas el fondo");
        if (fondo < 0) throw new CajaException("El fondo inicial no puede ser negativo");

        CashSession vivo = turnoDe(cajeroId);
        if (vivo != null) {
            throw new CajaException("Ese cajero ya tiene un turno sin terminar ("
                    + enCastellano(vivo.getStatus()) + "). Hay que cuadrarlo antes de empezar otro.");
        }

        LocalDateTime ahora = LocalDateTime.now();
        CashSession s = new CashSession();
        s.setCashierId(cajeroId);
        s.setCashierName(limpio(cajeroNombre));
        s.setCashierDni(limpio(cajeroDni));
        s.setAssignedBy(quienAsigna);
        s.setAssignedByName(limpio(nombreQuienAsigna));
        s.setAssignedAt(ahora);
        s.setOpeningFloat(redondear(fondo));
        s.setStatus(habilitar ? CashSession.HABILITADA : CashSession.FONDO_ASIGNADO);
        if (habilitar) s.setEnabledAt(ahora);
        CashSession guardado = sessionRepository.save(s);

        apuntar(guardado.getId(), CashLog.ASIGNADO, quienAsigna, nombreQuienAsigna,
                "Fondo de " + money(guardado.getOpeningFloat()) + " para " + guardado.getCashierName());
        if (habilitar) {
            apuntar(guardado.getId(), CashLog.HABILITADO, quienAsigna, nombreQuienAsigna,
                    "Habilitado para abrir");
        }
        return guardado;
    }

    /** Habilita un turno al que ya se le había asignado el fondo. */
    @Transactional
    public CashSession habilitar(Integer sessionId, Long quien) {
        CashSession s = exigirEstado(sessionId, CashSession.FONDO_ASIGNADO,
                "Ese turno ya está habilitado o más adelantado.");
        s.setEnabledAt(LocalDateTime.now());
        s.setStatus(CashSession.HABILITADA);
        if (s.getAssignedBy() == null) s.setAssignedBy(quien);
        apuntar(s.getId(), CashLog.HABILITADO, quien, s.getAssignedByName(), "Habilitado para abrir");
        return sessionRepository.save(s);
    }

    // ─── 2. El cajero abre ──────────────────────────────────────

    /**
     * El cajero confirma la apertura y acepta el fondo.
     *
     * Es él quien abre, no el jefe, y no es un detalle: esa confirmación es
     * lo que firma en el ticket de apertura, y es lo que convierte el dinero
     * del cajón en responsabilidad suya. Si lo abriera el jefe, el cajero
     * podría encontrarse un turno empezado con un fondo que nunca contó.
     *
     * El fondo no se toca aquí. Lo asignó el jefe y el cajero no lo cambia:
     * si no coincide con lo que tiene en la mano, es una conversación, no
     * un campo editable.
     */
    @Transactional
    public CashSession abrir(Long cajeroId) {
        CashSession s = turnoDe(cajeroId);
        if (s == null) {
            throw new CajaException("No tienes ningún fondo asignado. Pídeselo a tu jefe de ventas.");
        }
        if (CashSession.FONDO_ASIGNADO.equals(s.getStatus())) {
            throw new CajaException("Tu jefe registró el fondo pero todavía no te habilitó para abrir.");
        }
        if (!CashSession.HABILITADA.equals(s.getStatus())) {
            throw new CajaException("Tu caja ya está " + enCastellano(s.getStatus()) + ".");
        }
        s.setOpenedBy(cajeroId);
        s.setOpenedAt(LocalDateTime.now());
        s.setStatus(CashSession.ABIERTA);
        apuntar(s.getId(), CashLog.ABIERTO, cajeroId, s.getCashierName(),
                "Aceptó el fondo de " + money(s.getOpeningFloat()));
        return sessionRepository.save(s);
    }

    // ─── 3. Movimientos de efectivo ─────────────────────────────

    @Transactional
    public CashMovement registrarMovimiento(Integer sessionId, Long quien, String nombreQuien,
                                            String tipo, double importe, String motivo) {
        CashSession s = exigirEstado(sessionId, CashSession.ABIERTA,
                "Solo se mueve el efectivo de una caja abierta.");
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
        CashMovement guardado = movementRepository.save(m);
        apuntar(s.getId(), CashLog.MOVIMIENTO, quien, nombreQuien,
                etiquetaMovimiento(tipo) + " de " + money(importe) + " · " + motivo.trim());
        return guardado;
    }

    // ─── 4. Arqueo sorpresivo ───────────────────────────────────

    /**
     * El encargado cuenta el cajón en mitad del turno.
     *
     * No cambia el estado: en cuanto termina, el cajero sigue vendiendo. Si
     * para contar hubiera que cerrar el turno, nadie lo haría a media mañana
     * y dejaría de ser sorpresivo.
     *
     * Aquí el esperado SÍ se le enseña a quien cuenta, al revés que en el
     * cuadre del cierre. Es deliberado: el encargado mira en su pantalla
     * cuánto lleva vendido el cajero en efectivo y con esa cifra se pone a
     * contar billetes. Quien vigila es él; el vigilado es el cajero, que no
     * ve esta pantalla.
     */
    @Transactional
    public CashAudit arquear(Integer sessionId, Long quien, String nombreQuien,
                             double contado, String observacion) {
        CashSession s = exigirEstado(sessionId, CashSession.ABIERTA,
                "Un arqueo sorpresivo se hace con la caja abierta.");
        if (contado < 0) throw new CajaException("Lo contado no puede ser negativo");

        double esperado = efectivoEnCaja(s);
        double diferencia = redondear(contado - esperado);
        if (Math.abs(diferencia) >= 0.005 && (observacion == null || observacion.isBlank())) {
            throw new CajaException("Hay una diferencia de " + money(diferencia)
                    + ". Escribe una observación antes de guardar el arqueo.");
        }

        CashAudit a = new CashAudit();
        a.setSession(s);
        a.setPerformedBy(quien);
        a.setPerformedByName(limpio(nombreQuien));
        a.setExpectedCash(redondear(esperado));
        a.setCountedCash(redondear(contado));
        a.setDifference(diferencia);
        a.setObservation(limpio(observacion));
        CashAudit guardado = auditRepository.save(a);
        apuntar(s.getId(), CashLog.ARQUEO, quien, nombreQuien,
                "Contó " + money(contado) + " de " + money(esperado) + " esperados"
                        + (Math.abs(diferencia) < 0.005 ? " · cuadra" : " · " + money(diferencia)));
        return guardado;
    }

    // ─── 5. El cajero cierra ────────────────────────────────────

    /**
     * El cajero termina su turno. No admite más ventas.
     *
     * No declara ninguna cifra: cerrar es «he acabado», no «esto es lo que
     * hay». Contar es del encargado, y ocurre después, delante de él.
     */
    @Transactional
    public CashSession cerrar(Integer sessionId, Long cajeroId) {
        /*
         * Cierra el cajero y nadie más. Ni siquiera quien le entregó el
         * fondo: si el jefe pudiera cerrar la caja, podría darla por
         * terminada sin que el cajero lo supiera y cuadrarla él solo a
         * continuación, que es exactamente lo que los cinco estados
         * existen para impedir.
         *
         * Y sin identificar a quien llama no se cierra nada. Dejarlo pasar
         * cuando `cajeroId` viene a null —como estaba— convierte un fallo
         * de autenticación en permiso para cerrar cualquier caja.
         */
        if (cajeroId == null) {
            throw new CajaException("No se pudo identificar quién cierra la caja");
        }
        CashSession s = exigirEstado(sessionId, CashSession.ABIERTA,
                "Esa caja no está abierta.");
        if (!cajeroId.equals(s.getCashierId())) {
            throw new CajaException("Esa caja no es tuya: la cierra quien cobra con ella.");
        }
        s.setClosedBy(cajeroId);
        s.setClosedAt(LocalDateTime.now());
        s.setStatus(CashSession.CERRADA);
        apuntar(s.getId(), CashLog.CERRADO, cajeroId, s.getCashierName(), "Terminó su turno");
        return sessionRepository.save(s);
    }

    // ─── 6. El jefe cuadra ──────────────────────────────────────

    /**
     * Cuadra el turno con el conteo del encargado.
     *
     * `contado` trae lo que contó de cada forma: el efectivo del cajón, el
     * cierre de lote del POS, los abonos de Yape y Plin. `vouchers` trae
     * cuántos papeles tiene en la mano de cada forma que los lleva.
     *
     * Exige una cifra por cada forma que tuvo ventas. Dejar una sin declarar
     * daría un cuadre a medias que parece completo, y es peor que no
     * tenerlo: el papel saldría firmado dando por bueno un dinero que nadie
     * contó.
     *
     * Si hay cualquier diferencia —de importe o de vouchers— la observación
     * es obligatoria.
     */
    @Transactional
    public CashSession cuadrar(Integer sessionId, Long quien, String nombreQuien,
                               Map<String, Double> contado, Map<String, Integer> vouchers,
                               String observacion) {
        CashSession s = exigirEstado(sessionId, CashSession.CERRADA,
                "Solo se cuadra una caja cerrada. El cajero tiene que cerrarla primero.");
        Map<String, Double> declarado = contado == null ? Map.of() : contado;
        Map<String, Integer> papeles = vouchers == null ? Map.of() : vouchers;

        List<Esperado> esperados = esperadoPorForma(s);
        for (Esperado e : esperados) {
            Double v = declarado.get(e.method());
            if (v == null) {
                throw new CajaException("Falta contar " + e.label().toLowerCase()
                        + ": hubo ventas por esa forma y el cuadre no puede dejarla en blanco.");
            }
            if (v < 0) throw new CajaException("Lo contado en " + e.label().toLowerCase() + " no puede ser negativo");
        }

        List<CashCount> lineas = new ArrayList<>();
        boolean hayDiferencia = false;
        for (Esperado e : esperados) {
            CashCount c = new CashCount();
            c.setSession(s);
            c.setMethod(e.method());
            c.setLabel(e.label());
            c.setExpected(redondear(e.amount()));
            c.setCounted(redondear(declarado.get(e.method())));
            c.setDifference(redondear(c.getCounted() - c.getExpected()));
            c.setSalesCount(e.sales());
            // En efectivo no hay vouchers que contar: el comprobante del
            // cajero es el propio dinero.
            if (!EFECTIVO.equals(e.method())) {
                Integer contados = papeles.get(e.method());
                c.setVouchersCounted(contados);
                if (contados != null && contados != e.sales()) hayDiferencia = true;
            }
            if (Math.abs(c.getDifference()) >= 0.005) hayDiferencia = true;
            lineas.add(c);
        }

        if (hayDiferencia && (observacion == null || observacion.isBlank())) {
            throw new CajaException("Hay diferencias en el cuadre. "
                    + "Escribe una observación explicándolas antes de firmar el papel.");
        }

        countRepository.saveAll(lineas);

        // El efectivo se queda además en el propio turno: es la cifra que
        // manda —la del cajón— y así el histórico la lee sin recorrer el
        // desglose.
        Esperado efectivo = esperados.get(0);
        double contadoEfectivo = redondear(declarado.get(efectivo.method()));

        s.setSettledBy(quien);
        s.setSettledByName(limpio(nombreQuien));
        s.setSettledAt(LocalDateTime.now());
        s.setCountedCash(contadoEfectivo);
        s.setExpectedCash(redondear(efectivo.amount()));
        s.setDifference(redondear(contadoEfectivo - efectivo.amount()));
        s.setClosingNotes(limpio(observacion));
        s.setStatus(CashSession.CUADRADA);
        apuntar(s.getId(), CashLog.CUADRADO, quien, nombreQuien,
                "Contó " + money(contadoEfectivo) + " en efectivo de "
                        + money(efectivo.amount()) + " esperados"
                        + (Math.abs(s.getDifference()) < 0.005 ? " · cuadra" : " · " + money(s.getDifference())));
        return sessionRepository.save(s);
    }

    // ─── Las cuentas ────────────────────────────────────────────

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
            resto.add(new Esperado(clave, etiqueta(clave), redondear(numero(fila.get("importe"))),
                    ventas, (int) numero(fila.get("vouchers"))));
        }

        out.add(new Esperado(EFECTIVO, etiqueta(EFECTIVO), efectivoEnCaja(s), ventasEnEfectivo, 0));
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

    // ─── Lo que ve cada pantalla ────────────────────────────────

    /**
     * El turno como lo ve el cajero.
     *
     * Lleva lo que cobró —lo necesita para dar vuelto y para saber si hará
     * falta un retiro— pero no el efectivo esperado ni el cuadre hasta que
     * el encargado lo firma: el cajero no es quien cuenta.
     */
    public Map<String, Object> resumen(CashSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (s == null) {
            m.put("abierta", false);
            m.put("estado", null);
            return m;
        }
        boolean abierta = CashSession.ABIERTA.equals(s.getStatus());
        m.put("abierta", abierta);
        m.put("estado", s.getStatus());
        m.put("estadoTexto", enCastellano(s.getStatus()));
        m.put("id", s.getId());
        m.put("cashierId", s.getCashierId());
        m.put("cashierName", s.getCashierName());
        m.put("cashierDni", s.getCashierDni());
        m.put("assignedByName", s.getAssignedByName());
        m.put("assignedAt", s.getAssignedAt());
        m.put("openedAt", s.getOpenedAt());
        m.put("openingFloat", s.getOpeningFloat());
        m.put("cashSales", redondear(sessionRepository.cobradoEnEfectivo(s.getId())));
        m.put("byPaymentType", ventasPorForma(s));

        List<CashMovement> movimientos = movementRepository.findBySessionIdOrderByCreatedAtAsc(s.getId());
        m.put("movements", movimientos.stream().map(CashSessionService::movimientoDto).toList());
        m.put("movementsTotal", redondear(movimientos.stream().mapToDouble(CashMovement::delta).sum()));
        m.put("audits", auditRepository.findBySessionIdOrderByCreatedAtAsc(s.getId())
                .stream().map(CashSessionService::arqueoDto).toList());
        // El detalle de las ventas que dejaron papel: es el historial que
        // lleva la boleta de cierre del cajero. Con solo el total, un
        // voucher de menos se sabe que falta pero no cuál.
        m.put("voucherSales", sessionRepository.ventasConVoucher(s.getId()).stream()
                .map(CashSessionService::ventaConVoucherDto).toList());
        m.put("historial", logRepository.findBySessionIdOrderByCreatedAtAsc(s.getId())
                .stream().map(CashSessionService::historialDto).toList());

        if (CashSession.CERRADA.equals(s.getStatus()) || CashSession.CUADRADA.equals(s.getStatus())) {
            m.put("closedAt", s.getClosedAt());
        }
        if (CashSession.CUADRADA.equals(s.getStatus())) {
            m.put("settledAt", s.getSettledAt());
            m.put("settledByName", s.getSettledByName());
            m.put("countedCash", s.getCountedCash());
            m.put("expectedCash", s.getExpectedCash());
            m.put("difference", s.getDifference());
            m.put("closingNotes", s.getClosingNotes());
            m.put("counts", countRepository.findBySessionIdOrderByMethodAsc(s.getId())
                    .stream().map(CashSessionService::cuadreDto).toList());
        }
        return m;
    }

    /**
     * El turno como lo ve el encargado, con los importes a la vista.
     *
     * Mientras la caja está abierta esto es la pantalla del arqueo
     * sorpresivo: el encargado mira cuánto lleva vendido el cajero en
     * efectivo y con esa cifra se pone a contar billetes. Enseñárselo es el
     * procedimiento, no un descuido —es él quien vigila—.
     */
    public Map<String, Object> paraElJefe(CashSession s) {
        Map<String, Object> m = resumen(s);
        if (!CashSession.CUADRADA.equals(s.getStatus())) {
            // El esperado en vivo, que es con lo que cuenta.
            m.put("expectedCash", redondear(efectivoEnCaja(s)));
            m.put("toCount", esperadoPorForma(s).stream().map(e -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("type", e.method());
                f.put("label", e.label());
                f.put("amount", e.amount());
                f.put("sales", e.sales());
                f.put("vouchers", e.vouchers());
                return f;
            }).toList());
        }
        return m;
    }

    // ─── Fontanería ────────────────────────────────────────────

    /** Un turno en el estado que toca, con su fila bloqueada. */
    private CashSession exigirEstado(Integer sessionId, String estado, String siNo) {
        if (sessionId == null) throw new CajaException("Falta decir de qué caja se trata");
        CashSession s = sessionRepository.bloquearPorId(sessionId)
                .orElseThrow(() -> new CajaException("Esa caja no existe"));
        if (!estado.equals(s.getStatus())) {
            throw new CajaException(siNo + " (está " + enCastellano(s.getStatus()) + ")");
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
            f.put("vouchers", (int) numero(fila.get("vouchers")));
            out.add(f);
        }
        return out;
    }

    /** El estado en palabras, que es como se lee en pantalla. */
    public static String enCastellano(String estado) {
        if (estado == null) return "sin empezar";
        return switch (estado) {
            case CashSession.FONDO_ASIGNADO -> "con el fondo asignado";
            case CashSession.HABILITADA -> "habilitada";
            case CashSession.ABIERTA -> "abierta";
            case CashSession.CERRADA -> "cerrada, pendiente de cuadrar";
            case CashSession.CUADRADA -> "cuadrada";
            default -> estado;
        };
    }

    /** El tipo de movimiento en palabras, para el historial. */
    private static String etiquetaMovimiento(String tipo) {
        return switch (tipo) {
            case CashMovement.INGRESO -> "Entrada";
            case CashMovement.RETIRO -> "Retiro";
            case CashMovement.GASTO -> "Gasto";
            default -> tipo;
        };
    }

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

    private static Map<String, Object> ventaConVoucherDto(Map<String, Object> fila) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("at", fila.get("hora"));
        d.put("orderCode", fila.get("codigo"));
        Object serie = fila.get("serie");
        Object numero = fila.get("numero");
        d.put("boleta", serie == null || numero == null
                ? null
                : serie + "-" + String.format("%08d", (int) numero(numero)));
        d.put("label", fila.get("forma"));
        d.put("amount", redondear(numero(fila.get("importe"))));
        d.put("voucher", fila.get("voucher"));
        return d;
    }

    private static Map<String, Object> historialDto(CashLog l) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", l.getId());
        d.put("action", l.getAction());
        d.put("userName", l.getUserName());
        d.put("detail", l.getDetail());
        d.put("createdAt", l.getCreatedAt());
        return d;
    }

    private static Map<String, Object> arqueoDto(CashAudit a) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", a.getId());
        d.put("performedByName", a.getPerformedByName());
        d.put("expectedCash", a.getExpectedCash());
        d.put("countedCash", a.getCountedCash());
        d.put("difference", a.getDifference());
        d.put("observation", a.getObservation());
        d.put("createdAt", a.getCreatedAt());
        return d;
    }

    private static Map<String, Object> cuadreDto(CashCount c) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", c.getMethod());
        d.put("label", c.getLabel());
        d.put("expected", c.getExpected());
        d.put("counted", c.getCounted());
        d.put("difference", c.getDifference());
        d.put("sales", c.getSalesCount());
        d.put("vouchersCounted", c.getVouchersCounted());
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
