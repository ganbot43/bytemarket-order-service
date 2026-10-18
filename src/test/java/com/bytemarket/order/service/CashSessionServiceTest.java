package com.bytemarket.order.service;

import com.bytemarket.order.model.CashCount;
import com.bytemarket.order.model.CashMovement;
import com.bytemarket.order.model.CashSession;
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
 * El cuadre de caja: qué entra en la resta, y quién la firma.
 *
 * Toda la dificultad de un arqueo está en esas dos cosas. Lo demás es
 * aritmética.
 */
@DisplayName("Caja — apertura, movimientos y arqueo")
class CashSessionServiceTest {

    private ICashSessionRepository sessionRepository;
    private ICashMovementRepository movementRepository;
    private ICashCountRepository countRepository;
    private CashSessionService servicio;

    /** Quien cobra. */
    private static final Long CAJERO = 7L;
    /** Quien apertura y arquea. */
    private static final Long JEFE = 3L;
    private static final Integer TURNO = 1;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ICashSessionRepository.class);
        movementRepository = mock(ICashMovementRepository.class);
        countRepository = mock(ICashCountRepository.class);
        servicio = new CashSessionService(sessionRepository, movementRepository, countRepository);

        when(sessionRepository.save(any(CashSession.class))).thenAnswer(i -> {
            CashSession s = i.getArgument(0);
            if (s.getId() == null) s.setId(TURNO);
            return s;
        });
        when(movementRepository.save(any(CashMovement.class))).thenAnswer(i -> i.getArgument(0));
        when(movementRepository.findBySessionIdOrderByCreatedAtAsc(anyInt())).thenReturn(List.of());
        when(countRepository.findBySessionIdOrderByMethodAsc(anyInt())).thenReturn(List.of());
        when(sessionRepository.cobradoEnEfectivo(anyInt())).thenReturn(0d);
        when(sessionRepository.ventasPorForma(anyInt())).thenReturn(List.of());
    }

    private CashSession abierta(double fondo) {
        CashSession s = new CashSession();
        s.setId(TURNO);
        s.setCashierId(CAJERO);
        s.setCashierName("Ana");
        s.setOpenedBy(JEFE);
        s.setOpenedByName("jefe@bytemarket.com");
        s.setOpeningFloat(fondo);
        s.setStatus(CashSession.ABIERTA);
        when(sessionRepository.findFirstByCashierIdAndStatus(CAJERO, CashSession.ABIERTA))
                .thenReturn(Optional.of(s));
        // exigirAbierta pasa por el finder con bloqueo, no por el de lectura.
        when(sessionRepository.bloquearAbierta(CAJERO, CashSession.ABIERTA))
                .thenReturn(Optional.of(s));
        // Y el encargado llega por id, porque no arquea su propia caja.
        when(sessionRepository.bloquearPorId(TURNO)).thenReturn(Optional.of(s));
        when(sessionRepository.findById(TURNO)).thenReturn(Optional.of(s));
        return s;
    }

    private CashMovement mov(String tipo, double importe) {
        CashMovement m = new CashMovement();
        m.setType(tipo);
        m.setAmount(importe);
        return m;
    }

    private static Map<String, Object> venta(String forma, double importe, int ventas) {
        return Map.of("forma", forma, "importe", importe, "ventas", ventas);
    }

    private static Map<String, Double> contando(Object... pares) {
        Map<String, Double> m = new HashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            m.put((String) pares[i], ((Number) pares[i + 1]).doubleValue());
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<CashCount> lineasGuardadas() {
        ArgumentCaptor<List<CashCount>> captor = ArgumentCaptor.forClass(List.class);
        verify(countRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    // ── Quién hace qué ──────────────────────────────────────────

    @Test
    @DisplayName("la caja es del cajero, la apertura el jefe")
    void dosPersonasNoUna() {
        when(sessionRepository.findFirstByCashierIdAndStatus(CAJERO, CashSession.ABIERTA))
                .thenReturn(Optional.empty());

        CashSession s = servicio.abrir(JEFE, "jefe@bytemarket.com", CAJERO, "Ana", 100);

        assertEquals(CAJERO, s.getCashierId(), "el cajón es de quien cobra con él");
        assertEquals(JEFE, s.getOpenedBy(), "pero el fondo lo entregó el jefe");
        assertEquals("Ana", s.getCashierName());
        // Sin esta separación el arqueo lo firmaba la misma persona a la que
        // vigila, y entonces no vigilaba nada.
        assertNotEquals(s.getCashierId(), s.getOpenedBy());
    }

    @Test
    @DisplayName("no se apertura una caja sin decir de qué cajero es")
    void sinCajeroNoHayCaja() {
        CashSessionService.CajaException e = assertThrows(CashSessionService.CajaException.class,
                () -> servicio.abrir(JEFE, "jefe@bytemarket.com", null, null, 100));
        assertTrue(e.getMessage().toLowerCase().contains("cajero"), e.getMessage());
    }

    @Test
    @DisplayName("el cajero encuentra su caja por ser el cajero, no por haberla abierto")
    void elCajeroEncuentraLaSuya() {
        abierta(100);
        assertNotNull(servicio.abiertaDe(CAJERO));
        // El jefe la aperturó, pero no es suya: él no cobra con ese cajón.
        assertNull(servicio.abiertaDe(JEFE));
    }

    @Test
    @DisplayName("no se vende sin caja aperturada")
    void sinCajaNoSeVende() {
        when(sessionRepository.bloquearAbierta(CAJERO, CashSession.ABIERTA))
                .thenReturn(Optional.empty());

        CashSessionService.CajaException e = assertThrows(
                CashSessionService.CajaException.class, () -> servicio.exigirAbierta(CAJERO));

        assertTrue(e.getMessage().contains("jefe"), "el mensaje debe decir a quién pedirlo: " + e.getMessage());
    }

    @Test
    @DisplayName("un cajero no tiene dos cajas a la vez")
    void unaCajaPorCajero() {
        abierta(100);
        assertThrows(CashSessionService.CajaException.class,
                () -> servicio.abrir(JEFE, "jefe@bytemarket.com", CAJERO, "Ana", 50));
    }

    @Test
    @DisplayName("una caja cerrada no se vuelve a arquear")
    void loCerradoNoSeToca() {
        CashSession s = abierta(100);
        s.setStatus(CashSession.CERRADA);

        CashSessionService.CajaException e = assertThrows(CashSessionService.CajaException.class,
                () -> servicio.cerrar(TURNO, JEFE, contando("efectivo", 100), null));
        assertTrue(e.getMessage().contains("cerrada"), e.getMessage());
    }

    // ── Qué entra en la resta ───────────────────────────────────

    @Test
    @DisplayName("el esperado es fondo + efectivo + ingresos - retiros - gastos")
    void elEsperadoSuma() {
        CashSession s = abierta(100);
        when(sessionRepository.cobradoEnEfectivo(TURNO)).thenReturn(450d);
        when(movementRepository.findBySessionIdOrderByCreatedAtAsc(TURNO)).thenReturn(List.of(
                mov(CashMovement.INGRESO, 50),
                mov(CashMovement.RETIRO, 200),
                mov(CashMovement.GASTO, 30)));

        // 100 + 450 + 50 - 200 - 30
        assertEquals(370d, servicio.efectivoEnCaja(s), 0.001);
    }

    @Test
    @DisplayName("Yape y tarjeta NO entran en el esperado del efectivo")
    void yapeNoPasaPorElCajon() {
        CashSession s = abierta(100);
        when(sessionRepository.cobradoEnEfectivo(TURNO)).thenReturn(200d);
        when(sessionRepository.ventasPorForma(TURNO)).thenReturn(List.of(
                venta("efectivo", 200, 2),
                venta("yape", 900, 5)));

        assertEquals(300d, servicio.efectivoEnCaja(s), 0.001,
                "los 900 de Yape no pasaron por el cajón");

        // Pero sí tienen su propia línea de arqueo, contra sus abonos.
        List<CashSessionService.Esperado> esperados = servicio.esperadoPorForma(s);
        assertEquals("efectivo", esperados.get(0).method(), "el efectivo va primero y siempre");
        assertEquals(300d, esperados.get(0).amount(), 0.001, "con fondo y movimientos");
        assertEquals("yape", esperados.get(1).method());
        assertEquals(900d, esperados.get(1).amount(), 0.001, "Yape, solo sus ventas: ni fondo ni movimientos");
    }

    @Test
    @DisplayName("el efectivo se arquea aunque no se haya cobrado nada en efectivo")
    void elFondoSigueEnElCajon() {
        CashSession s = abierta(100);
        when(sessionRepository.ventasPorForma(TURNO)).thenReturn(List.of(venta("yape", 300, 2)));

        List<CashSessionService.Esperado> esperados = servicio.esperadoPorForma(s);

        assertEquals("efectivo", esperados.get(0).method(),
                "el fondo está en el cajón y hay que devolverlo");
        assertEquals(100d, esperados.get(0).amount(), 0.001);
        assertEquals(0, esperados.get(0).sales());
    }

    @Test
    @DisplayName("una forma sin ventas no se pide contar")
    void sinVentasNoSePideContar() {
        CashSession s = abierta(100);
        when(sessionRepository.ventasPorForma(TURNO)).thenReturn(List.of(venta("efectivo", 50, 1)));

        // Pedir el cierre de lote del POS un día que nadie pagó con tarjeta
        // es ruido, y el ruido hace que el arqueo se rellene sin mirar.
        assertEquals(1, servicio.esperadoPorForma(s).size());
    }

    // ── El arqueo ───────────────────────────────────────────────

    @Test
    @DisplayName("el arqueo guarda la diferencia, sobre o falte")
    void elArqueoGuardaLaDiferencia() {
        abierta(100);
        when(sessionRepository.cobradoEnEfectivo(TURNO)).thenReturn(400d);
        when(sessionRepository.ventasPorForma(TURNO)).thenReturn(List.of(venta("efectivo", 400, 4)));

        CashSession cerrada = servicio.cerrar(TURNO, JEFE, contando("efectivo", 495), "faltaron 5");

        assertEquals(500d, cerrada.getExpectedCash(), 0.001);
        assertEquals(495d, cerrada.getCountedCash(), 0.001);
        assertEquals(-5d, cerrada.getDifference(), 0.001, "negativo = falta");
        assertEquals(CashSession.CERRADA, cerrada.getStatus());
        assertEquals(JEFE, cerrada.getClosedBy(), "lo firma quien arquea, no quien cobró");
    }

    @Test
    @DisplayName("cada forma de pago tiene su propia diferencia")
    void cadaFormaSuDiferencia() {
        abierta(100);
        when(sessionRepository.cobradoEnEfectivo(TURNO)).thenReturn(400d);
        when(sessionRepository.ventasPorForma(TURNO)).thenReturn(List.of(
                venta("efectivo", 400, 4),
                venta("tarjeta", 300, 2),
                venta("yape", 150, 1)));

        // El cajón cuadra, el lote del POS cuadra, y en Yape falta una venta.
        servicio.cerrar(TURNO, JEFE, contando("efectivo", 500, "tarjeta", 300, "yape", 100), null);

        Map<String, CashCount> porForma = new HashMap<>();
        lineasGuardadas().forEach(c -> porForma.put(c.getMethod(), c));

        assertEquals(3, porForma.size());
        assertEquals(0d, porForma.get("efectivo").getDifference(), 0.001);
        assertEquals(0d, porForma.get("tarjeta").getDifference(), 0.001);
        assertEquals(-50d, porForma.get("yape").getDifference(), 0.001,
                "faltan 50 de Yape: con un arqueo de solo efectivo esto no se veía");
        assertEquals("Yape", porForma.get("yape").getLabel());
        assertEquals(1, porForma.get("yape").getSalesCount());
    }

    @Test
    @DisplayName("no se cierra dejando una forma sin contar")
    void elArqueoNoSeDejaAMedias() {
        abierta(100);
        when(sessionRepository.ventasPorForma(TURNO)).thenReturn(List.of(
                venta("efectivo", 100, 1),
                venta("tarjeta", 300, 2)));

        CashSessionService.CajaException e = assertThrows(CashSessionService.CajaException.class,
                () -> servicio.cerrar(TURNO, JEFE, contando("efectivo", 200), null));

        assertTrue(e.getMessage().toLowerCase().contains("tarjeta"),
                "tiene que decir qué falta por contar: " + e.getMessage());
        verify(countRepository, never()).saveAll(any());
        verify(sessionRepository, never()).save(any(CashSession.class));
    }

    @Test
    @DisplayName("mientras está abierta no se enseña el esperado")
    void elEsperadoNoSeFiltraAntesDelArqueo() {
        CashSession s = abierta(100);
        when(sessionRepository.cobradoEnEfectivo(TURNO)).thenReturn(400d);

        Map<String, Object> r = servicio.resumen(s);

        // Quien ve la cifra objetivo deja de contar y la copia: el arqueo
        // dejaría de detectar nada.
        assertFalse(r.containsKey("expectedCash"),
                "enseñar el esperado antes de contar invalida el arqueo");
        assertEquals(400d, r.get("cashSales"), "al cajero sí se le dice lo que cobró: él no cuenta");
    }

    @Test
    @DisplayName("a quien va a arquear no se le enseña ningún importe")
    void aQuienCuentaNoSeLeDaLaRespuesta() {
        CashSession s = abierta(100);
        when(sessionRepository.cobradoEnEfectivo(TURNO)).thenReturn(400d);
        when(sessionRepository.ventasPorForma(TURNO)).thenReturn(List.of(
                venta("efectivo", 400, 4),
                venta("yape", 900, 5)));

        Map<String, Object> r = servicio.paraArqueo(s);

        assertFalse(r.containsKey("cashSales"), "contar con el total delante no es contar");
        assertFalse(r.containsKey("byPaymentType"));
        assertFalse(r.containsKey("expectedCash"));

        // Pero sí qué comprobantes tiene que juntar, y cuántas ventas hay.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> aContar = (List<Map<String, Object>>) r.get("toCount");
        assertEquals(List.of("efectivo", "yape"), aContar.stream().map(f -> f.get("type")).toList());
        assertFalse(aContar.get(0).containsKey("amount"), "el qué, no el cuánto");
        assertEquals(9, r.get("salesCount"));
    }

    @Test
    @DisplayName("el arqueo cerrado sí se lee entero")
    void loCerradoSeLeeEntero() {
        CashSession s = abierta(100);
        s.setStatus(CashSession.CERRADA);
        s.setExpectedCash(500d);
        s.setCountedCash(495d);
        s.setDifference(-5d);
        CashCount c = new CashCount();
        c.setMethod("yape");
        c.setLabel("Yape");
        c.setExpected(900d);
        c.setCounted(850d);
        c.setDifference(-50d);
        when(countRepository.findBySessionIdOrderByMethodAsc(TURNO)).thenReturn(List.of(c));

        Map<String, Object> r = servicio.paraArqueo(s);

        assertEquals(500d, r.get("expectedCash"), "ya se contó: ocultarlo ahora no protege nada");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lineas = (List<Map<String, Object>>) r.get("counts");
        assertEquals(-50d, lineas.get(0).get("difference"));
    }

    // ── Movimientos ─────────────────────────────────────────────

    @Test
    @DisplayName("no se puede retirar más de lo que hay")
    void noSeRetiraDeMas() {
        abierta(100);
        when(sessionRepository.cobradoEnEfectivo(TURNO)).thenReturn(50d);

        CashSessionService.CajaException e = assertThrows(CashSessionService.CajaException.class,
                () -> servicio.registrarMovimiento(TURNO, JEFE, CashMovement.RETIRO, 500, "al banco"));

        assertTrue(e.getMessage().contains("150"), "debe decir cuánto hay: " + e.getMessage());
    }

    @Test
    @DisplayName("un movimiento sin motivo se rechaza")
    void elMotivoEsObligatorio() {
        abierta(100);
        assertThrows(CashSessionService.CajaException.class,
                () -> servicio.registrarMovimiento(TURNO, JEFE, CashMovement.RETIRO, 10, "  "));
    }

    @Test
    @DisplayName("el movimiento queda a nombre de quien lo hizo")
    void elMovimientoTieneAutor() {
        abierta(1000);
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
