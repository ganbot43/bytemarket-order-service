package com.bytemarket.order.service;

import com.bytemarket.order.model.CashAudit;
import com.bytemarket.order.model.CashCount;
import com.bytemarket.order.model.CashMovement;
import com.bytemarket.order.model.CashSession;
import com.bytemarket.order.repository.ICashAuditRepository;
import com.bytemarket.order.repository.ICashCountRepository;
import com.bytemarket.order.repository.ICashMovementRepository;
import com.bytemarket.order.repository.ICashSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * El turno de caja: quién lo mueve de estado y qué entra en la resta.
 *
 * Las dos cosas son el control. Lo demás es aritmética.
 */
@DisplayName("Caja — fondo, apertura, arqueo, cierre y cuadre")
class CashSessionServiceTest {

    private ICashSessionRepository sessionRepository;
    private ICashMovementRepository movementRepository;
    private ICashCountRepository countRepository;
    private ICashAuditRepository auditRepository;
    private CashSessionService servicio;

    /** Quien cobra. */
    private static final Long CAJERO = 7L;
    /** Quien entrega el fondo, arquea y cuadra. */
    private static final Long JEFE = 3L;
    private static final Integer TURNO = 1;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ICashSessionRepository.class);
        movementRepository = mock(ICashMovementRepository.class);
        countRepository = mock(ICashCountRepository.class);
        auditRepository = mock(ICashAuditRepository.class);
        servicio = new CashSessionService(sessionRepository, movementRepository,
                countRepository, auditRepository);

        when(sessionRepository.save(any(CashSession.class))).thenAnswer(i -> {
            CashSession s = i.getArgument(0);
            if (s.getId() == null) s.setId(TURNO);
            return s;
        });
        when(movementRepository.save(any(CashMovement.class))).thenAnswer(i -> i.getArgument(0));
        when(auditRepository.save(any(CashAudit.class))).thenAnswer(i -> i.getArgument(0));
        when(movementRepository.findBySessionIdOrderByCreatedAtAsc(anyInt())).thenReturn(List.of());
        when(countRepository.findBySessionIdOrderByMethodAsc(anyInt())).thenReturn(List.of());
        when(auditRepository.findBySessionIdOrderByCreatedAtAsc(anyInt())).thenReturn(List.of());
        when(sessionRepository.cobradoEnEfectivo(anyInt())).thenReturn(0d);
        when(sessionRepository.ventasPorForma(anyInt())).thenReturn(List.of());
        when(sessionRepository.findFirstByCashierIdAndStatusIn(any(), any())).thenReturn(Optional.empty());
    }

    /** Un turno en el estado que se pida, enganchado a los mocks. */
    private CashSession turno(String estado, double fondo) {
        CashSession s = new CashSession();
        s.setId(TURNO);
        s.setCashierId(CAJERO);
        s.setCashierName("Ana");
        s.setCashierDni("70123456");
        s.setAssignedBy(JEFE);
        s.setAssignedByName("jefe@bytemarket.com");
        s.setOpeningFloat(fondo);
        s.setStatus(estado);
        when(sessionRepository.findFirstByCashierIdAndStatusIn(eq(CAJERO), any()))
                .thenReturn(Optional.of(s));
        when(sessionRepository.bloquearPorId(TURNO)).thenReturn(Optional.of(s));
        when(sessionRepository.bloquearAbierta(CAJERO, CashSession.ABIERTA))
                .thenReturn(CashSession.ABIERTA.equals(estado) ? Optional.of(s) : Optional.empty());
        return s;
    }

    private CashMovement mov(String tipo, double importe) {
        CashMovement m = new CashMovement();
        m.setType(tipo);
        m.setAmount(importe);
        return m;
    }

    private static Map<String, Object> venta(String forma, double importe, int ventas, int vouchers) {
        return Map.of("forma", forma, "importe", importe, "ventas", ventas, "vouchers", vouchers);
    }

    private static Map<String, Double> contando(Object... pares) {
        Map<String, Double> m = new HashMap<>();
        for (int i = 0; i < pares.length; i += 2) m.put((String) pares[i], ((Number) pares[i + 1]).doubleValue());
        return m;
    }

    private static Map<String, Integer> papeles(Object... pares) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < pares.length; i += 2) m.put((String) pares[i], ((Number) pares[i + 1]).intValue());
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<CashCount> lineasGuardadas() {
        ArgumentCaptor<List<CashCount>> captor = ArgumentCaptor.forClass(List.class);
        verify(countRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    // ── El recorrido completo ───────────────────────────────────

    @Test
    @DisplayName("el turno recorre los cinco estados en orden")
    void elRecorrido() {
        // 1. El jefe asigna el fondo y habilita.
        CashSession s = servicio.asignarFondo(JEFE, "jefe@bytemarket.com",
                CAJERO, "Ana", "70123456", 300, true);
        assertEquals(CashSession.HABILITADA, s.getStatus());
        assertEquals(JEFE, s.getAssignedBy(), "el fondo lo entregó el jefe");
        assertEquals(CAJERO, s.getCashierId(), "pero el cajón es del cajero");
        assertNull(s.getOpenedAt(), "todavía no la ha abierto nadie");

        // 2. El cajero confirma: eso es lo que firma en el ticket.
        turno(CashSession.HABILITADA, 300);
        s = servicio.abrir(CAJERO);
        assertEquals(CashSession.ABIERTA, s.getStatus());
        assertEquals(CAJERO, s.getOpenedBy(), "abre el cajero, no el jefe");

        // 3. Cierra él mismo al acabar, sin declarar ninguna cifra.
        turno(CashSession.ABIERTA, 300);
        s = servicio.cerrar(TURNO, CAJERO);
        assertEquals(CashSession.CERRADA, s.getStatus());
        assertNull(s.getCountedCash(), "cerrar es «he acabado», no «esto es lo que hay»");

        // 4. Y el jefe cuenta y cuadra.
        turno(CashSession.CERRADA, 300);
        when(sessionRepository.cobradoEnEfectivo(TURNO)).thenReturn(200d);
        when(sessionRepository.ventasPorForma(TURNO)).thenReturn(List.of(venta("efectivo", 200, 2, 0)));
        s = servicio.cuadrar(TURNO, JEFE, "jefe@bytemarket.com", contando("efectivo", 500), null, null);
        assertEquals(CashSession.CUADRADA, s.getStatus());
        assertEquals(JEFE, s.getSettledBy(), "lo firma quien contó, no quien cobró");
    }

    @Test
    @DisplayName("la caja la cierra su cajero y nadie más")
    void cierraSuDueno() {
        turno(CashSession.ABIERTA, 300);

        // Ni siquiera quien le entregó el fondo: si el jefe pudiera
        // cerrarla, podría darla por terminada sin que el cajero lo supiera
        // y cuadrarla él solo a continuación.
        CashSessionService.CajaException e = assertThrows(CashSessionService.CajaException.class,
                () -> servicio.cerrar(TURNO, JEFE));
        assertTrue(e.getMessage().contains("no es tuya"), e.getMessage());

        // Y sin identificar a quien llama no se cierra nada: dejarlo pasar
        // convertiría un fallo de autenticación en permiso para cerrar
        // cualquier caja.
        assertThrows(CashSessionService.CajaException.class, () -> servicio.cerrar(TURNO, null));

        assertEquals(CashSession.CERRADA, servicio.cerrar(TURNO, CAJERO).getStatus());
    }

    @Test
    @DisplayName("el cajero no abre hasta que lo habilitan")
    void sinHabilitarNoAbre() {
        turno(CashSession.FONDO_ASIGNADO, 300);
        CashSessionService.CajaException e = assertThrows(CashSessionService.CajaException.class,
                () -> servicio.abrir(CAJERO));
        assertTrue(e.getMessage().contains("habilit"), e.getMessage());
    }

    @Test
    @DisplayName("el cajero no se asigna el fondo: le llega puesto")
    void elFondoNoLoPoneElCajero() {
        CashSession s = servicio.asignarFondo(JEFE, "jefe@bytemarket.com",
                CAJERO, "Ana", null, 300, true);
        turno(CashSession.HABILITADA, 300);

        // abrir() no acepta ninguna cifra: si el fondo no coincide con lo
        // que tiene en la mano, es una conversación, no un campo editable.
        assertEquals(300d, servicio.abrir(CAJERO).getOpeningFloat(), 0.001);
        assertEquals(300d, s.getOpeningFloat(), 0.001);
    }

    @Test
    @DisplayName("un cajero no tiene dos turnos a la vez")
    void unTurnoPorCajero() {
        turno(CashSession.ABIERTA, 100);
        CashSessionService.CajaException e = assertThrows(CashSessionService.CajaException.class,
                () -> servicio.asignarFondo(JEFE, "jefe@bytemarket.com", CAJERO, "Ana", null, 50, true));
        assertTrue(e.getMessage().contains("sin terminar"), e.getMessage());
    }

    @Test
    @DisplayName("no se asigna fondo sin decir a qué cajero")
    void sinCajeroNoHayCaja() {
        CashSessionService.CajaException e = assertThrows(CashSessionService.CajaException.class,
                () -> servicio.asignarFondo(JEFE, "jefe@bytemarket.com", null, null, null, 100, true));
        assertTrue(e.getMessage().toLowerCase().contains("cajero"), e.getMessage());
    }

    @Test
    @DisplayName("no se vende con la caja solo habilitada")
    void habilitadaNoEsAbierta() {
        turno(CashSession.HABILITADA, 300);
        CashSessionService.CajaException e = assertThrows(CashSessionService.CajaException.class,
                () -> servicio.exigirAbierta(CAJERO));
        // El mensaje tiene que decir qué le falta hacer, no solo que no.
        assertTrue(e.getMessage().contains("Ábrela"), e.getMessage());
    }

    @Test
    @DisplayName("no se vende con la caja cerrada esperando cuadre")
    void cerradaNoVende() {
        turno(CashSession.CERRADA, 300);
        CashSessionService.CajaException e = assertThrows(CashSessionService.CajaException.class,
                () -> servicio.exigirAbierta(CAJERO));
        assertTrue(e.getMessage().contains("cuádrala") || e.getMessage().contains("Cuádrala"), e.getMessage());
    }

    @Test
    @DisplayName("no se cuadra una caja que el cajero no ha cerrado")
    void primeroCierraElCajero() {
        turno(CashSession.ABIERTA, 100);
        CashSessionService.CajaException e = assertThrows(CashSessionService.CajaException.class,
                () -> servicio.cuadrar(TURNO, JEFE, "jefe", contando("efectivo", 100), null, null));
        assertTrue(e.getMessage().contains("cerrada"), e.getMessage());
    }

    @Test
    @DisplayName("una caja cuadrada no se vuelve a tocar")
    void loCuadradoNoSeToca() {
        turno(CashSession.CUADRADA, 100);
        assertThrows(CashSessionService.CajaException.class,
                () -> servicio.cuadrar(TURNO, JEFE, "jefe", contando("efectivo", 100), null, null));
        assertThrows(CashSessionService.CajaException.class, () -> servicio.cerrar(TURNO, CAJERO));
        assertThrows(CashSessionService.CajaException.class,
                () -> servicio.arquear(TURNO, JEFE, "jefe", 100, null));
    }

    // ── El arqueo sorpresivo ────────────────────────────────────

    @Test
    @DisplayName("el arqueo sorpresivo no cierra la caja: el cajero sigue vendiendo")
    void elArqueoNoCierraNada() {
        CashSession s = turno(CashSession.ABIERTA, 300);
        when(sessionRepository.cobradoEnEfectivo(TURNO)).thenReturn(1200d);

        CashAudit a = servicio.arquear(TURNO, JEFE, "jefe@bytemarket.com", 1500, null);

        assertEquals(1500d, a.getCountedCash(), 0.001);
        assertEquals(1500d, a.getExpectedCash(), 0.001, "300 de fondo + 1200 vendidos");
        assertEquals(0d, a.getDifference(), 0.001);
        assertEquals(CashSession.ABIERTA, s.getStatus(), "sigue abierta");
        // Si para contar hubiera que cerrar el turno, nadie lo haría a
        // media mañana y dejaría de ser sorpresivo.
        assertNotNull(servicio.exigirAbierta(CAJERO));
    }

    @Test
    @DisplayName("un arqueo con diferencia exige observación")
    void elArqueoConFaltanteSeExplica() {
        turno(CashSession.ABIERTA, 300);
        when(sessionRepository.cobradoEnEfectivo(TURNO)).thenReturn(1200d);

        CashSessionService.CajaException e = assertThrows(CashSessionService.CajaException.class,
                () -> servicio.arquear(TURNO, JEFE, "jefe", 1450, null));
        assertTrue(e.getMessage().contains("observación"), e.getMessage());

        // Con explicación sí entra, y queda guardada.
        CashAudit a = servicio.arquear(TURNO, JEFE, "jefe", 1450, "faltan 50, el cajero fue al banco");
        assertEquals(-50d, a.getDifference(), 0.001);
        assertNotNull(a.getObservation());
    }

    @Test
    @DisplayName("al jefe se le enseña el esperado para que cuente")
    void elJefeVeLoQueDebeHaber() {
        CashSession s = turno(CashSession.ABIERTA, 300);
        when(sessionRepository.cobradoEnEfectivo(TURNO)).thenReturn(1200d);

        // Es el procedimiento, no un descuido: el encargado mira cuánto ha
        // vendido el cajero en efectivo y con esa cifra cuenta billetes.
        // El vigilado es el cajero, que no ve esta pantalla.
        Map<String, Object> delJefe = servicio.paraElJefe(s);
        assertEquals(1500d, delJefe.get("expectedCash"));

        // La del cajero no lo lleva.
        assertFalse(servicio.resumen(s).containsKey("expectedCash"));
    }

    // ── Qué entra en la resta ───────────────────────────────────

    @Test
    @DisplayName("el esperado es fondo + efectivo + ingresos - retiros - gastos")
    void elEsperadoSuma() {
        CashSession s = turno(CashSession.ABIERTA, 100);
        when(sessionRepository.cobradoEnEfectivo(TURNO)).thenReturn(450d);
        when(movementRepository.findBySessionIdOrderByCreatedAtAsc(TURNO)).thenReturn(List.of(
                mov(CashMovement.INGRESO, 50), mov(CashMovement.RETIRO, 200), mov(CashMovement.GASTO, 30)));

        assertEquals(370d, servicio.efectivoEnCaja(s), 0.001);
    }

    @Test
    @DisplayName("Yape y tarjeta NO entran en el esperado del efectivo")
    void yapeNoPasaPorElCajon() {
        CashSession s = turno(CashSession.ABIERTA, 100);
        when(sessionRepository.cobradoEnEfectivo(TURNO)).thenReturn(200d);
        when(sessionRepository.ventasPorForma(TURNO)).thenReturn(List.of(
                venta("efectivo", 200, 2, 0), venta("yape", 900, 5, 5)));

        assertEquals(300d, servicio.efectivoEnCaja(s), 0.001, "los 900 de Yape no pasaron por el cajón");

        List<CashSessionService.Esperado> esperados = servicio.esperadoPorForma(s);
        assertEquals("efectivo", esperados.get(0).method(), "el efectivo va primero y siempre");
        assertEquals(300d, esperados.get(0).amount(), 0.001, "con fondo y movimientos");
        assertEquals(900d, esperados.get(1).amount(), 0.001, "Yape, solo sus ventas");
        assertEquals(5, esperados.get(1).vouchers(), "y sus vouchers, para contarlos");
    }

    @Test
    @DisplayName("el efectivo se cuadra aunque no se haya cobrado nada en efectivo")
    void elFondoSigueEnElCajon() {
        CashSession s = turno(CashSession.ABIERTA, 100);
        when(sessionRepository.ventasPorForma(TURNO)).thenReturn(List.of(venta("yape", 300, 2, 2)));

        List<CashSessionService.Esperado> esperados = servicio.esperadoPorForma(s);
        assertEquals("efectivo", esperados.get(0).method(), "el fondo está en el cajón y hay que devolverlo");
        assertEquals(100d, esperados.get(0).amount(), 0.001);
    }

    @Test
    @DisplayName("una forma sin ventas no se pide contar")
    void sinVentasNoSePideContar() {
        CashSession s = turno(CashSession.ABIERTA, 100);
        when(sessionRepository.ventasPorForma(TURNO)).thenReturn(List.of(venta("efectivo", 50, 1, 0)));
        assertEquals(1, servicio.esperadoPorForma(s).size());
    }

    // ── El cuadre ───────────────────────────────────────────────

    @Test
    @DisplayName("el cuadre guarda la diferencia de cada forma")
    void cadaFormaSuDiferencia() {
        turno(CashSession.CERRADA, 100);
        when(sessionRepository.cobradoEnEfectivo(TURNO)).thenReturn(400d);
        when(sessionRepository.ventasPorForma(TURNO)).thenReturn(List.of(
                venta("efectivo", 400, 4, 0), venta("tarjeta", 300, 2, 2), venta("yape", 150, 1, 1)));

        servicio.cuadrar(TURNO, JEFE, "jefe",
                contando("efectivo", 500, "tarjeta", 300, "yape", 100),
                papeles("tarjeta", 2, "yape", 1),
                "falta un abono de Yape por confirmar");

        Map<String, CashCount> porForma = new HashMap<>();
        lineasGuardadas().forEach(c -> porForma.put(c.getMethod(), c));

        assertEquals(3, porForma.size());
        assertEquals(0d, porForma.get("efectivo").getDifference(), 0.001);
        assertEquals(0d, porForma.get("tarjeta").getDifference(), 0.001);
        assertEquals(-50d, porForma.get("yape").getDifference(), 0.001,
                "faltan 50 de Yape: con un cuadre de solo efectivo esto no se veía");
        assertEquals(2, porForma.get("tarjeta").getVouchersCounted());
        assertNull(porForma.get("efectivo").getVouchersCounted(),
                "en efectivo el comprobante del cajero es el propio dinero");
    }

    @Test
    @DisplayName("si faltan vouchers hay que explicarlo, aunque el dinero cuadre")
    void losPapelesTambienCuentan() {
        turno(CashSession.CERRADA, 0);
        when(sessionRepository.ventasPorForma(TURNO)).thenReturn(List.of(venta("tarjeta", 300, 15, 15)));

        // Quince ventas con tarjeta, catorce papeles en la mano. Los
        // importes cuadran y aun así hay algo que explicar: una venta
        // cobrada dos veces por el mismo total pasa por aquí.
        CashSessionService.CajaException e = assertThrows(CashSessionService.CajaException.class,
                () -> servicio.cuadrar(TURNO, JEFE, "jefe",
                        contando("efectivo", 0, "tarjeta", 300), papeles("tarjeta", 14), null));
        assertTrue(e.getMessage().contains("observación"), e.getMessage());
    }

    @Test
    @DisplayName("una diferencia en el dinero también exige observación")
    void elFaltanteSeExplica() {
        turno(CashSession.CERRADA, 100);
        when(sessionRepository.ventasPorForma(TURNO)).thenReturn(List.of(venta("efectivo", 0, 0, 0)));

        assertThrows(CashSessionService.CajaException.class,
                () -> servicio.cuadrar(TURNO, JEFE, "jefe", contando("efectivo", 95), null, null));
        verify(sessionRepository, never()).save(any(CashSession.class));

        CashSession s = servicio.cuadrar(TURNO, JEFE, "jefe", contando("efectivo", 95), null, "faltó un billete de 5");
        assertEquals(-5d, s.getDifference(), 0.001);
        assertEquals("faltó un billete de 5", s.getClosingNotes());
    }

    @Test
    @DisplayName("si todo cuadra, la observación es opcional")
    void siCuadraNoHayQueEscribirNada() {
        turno(CashSession.CERRADA, 100);
        when(sessionRepository.ventasPorForma(TURNO)).thenReturn(List.of(venta("efectivo", 0, 0, 0)));
        assertEquals(0d, servicio.cuadrar(TURNO, JEFE, "jefe", contando("efectivo", 100), null, null)
                .getDifference(), 0.001);
    }

    @Test
    @DisplayName("no se cuadra dejando una forma sin contar")
    void elCuadreNoSeDejaAMedias() {
        turno(CashSession.CERRADA, 100);
        when(sessionRepository.ventasPorForma(TURNO)).thenReturn(List.of(
                venta("efectivo", 100, 1, 0), venta("tarjeta", 300, 2, 2)));

        CashSessionService.CajaException e = assertThrows(CashSessionService.CajaException.class,
                () -> servicio.cuadrar(TURNO, JEFE, "jefe", contando("efectivo", 200), null, null));

        assertTrue(e.getMessage().toLowerCase().contains("tarjeta"), e.getMessage());
        verify(countRepository, never()).saveAll(any());
        verify(sessionRepository, never()).save(any(CashSession.class));
    }

    // ── Movimientos ─────────────────────────────────────────────

    @Test
    @DisplayName("no se puede retirar más de lo que hay")
    void noSeRetiraDeMas() {
        turno(CashSession.ABIERTA, 100);
        when(sessionRepository.cobradoEnEfectivo(TURNO)).thenReturn(50d);

        CashSessionService.CajaException e = assertThrows(CashSessionService.CajaException.class,
                () -> servicio.registrarMovimiento(TURNO, JEFE, CashMovement.RETIRO, 500, "al banco"));
        assertTrue(e.getMessage().contains("150"), "debe decir cuánto hay: " + e.getMessage());
    }

    @Test
    @DisplayName("un movimiento sin motivo se rechaza")
    void elMotivoEsObligatorio() {
        turno(CashSession.ABIERTA, 100);
        assertThrows(CashSessionService.CajaException.class,
                () -> servicio.registrarMovimiento(TURNO, JEFE, CashMovement.RETIRO, 10, "  "));
    }

    @Test
    @DisplayName("el movimiento queda a nombre de quien lo hizo")
    void elMovimientoTieneAutor() {
        turno(CashSession.ABIERTA, 1000);
        CashMovement m = servicio.registrarMovimiento(TURNO, JEFE, CashMovement.RETIRO, 500, "a la caja fuerte");
        assertEquals(JEFE, m.getCreatedBy(), "el retiro lo hace el jefe, no el cajero");
    }

    @Test
    @DisplayName("los retiros y los gastos restan; los ingresos suman")
    void elSignoLoPoneElTipo() {
        assertEquals(50d, mov(CashMovement.INGRESO, 50).delta(), 0.001);
        assertEquals(-50d, mov(CashMovement.RETIRO, 50).delta(), 0.001);
        assertEquals(-50d, mov(CashMovement.GASTO, 50).delta(), 0.001);
    }
}
