package com.bytemarket.order.service;

import com.bytemarket.order.model.CashMovement;
import com.bytemarket.order.model.CashSession;
import com.bytemarket.order.repository.ICashMovementRepository;
import com.bytemarket.order.repository.ICashSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * El cuadre de caja: qué entra en la resta y qué no.
 *
 * Toda la dificultad de un arqueo está en eso. Lo demás es aritmética.
 */
@DisplayName("Caja — apertura, movimientos y cuadre")
class CashSessionServiceTest {

    private ICashSessionRepository sessionRepository;
    private ICashMovementRepository movementRepository;
    private CashSessionService servicio;

    private static final Long CAJERO = 7L;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ICashSessionRepository.class);
        movementRepository = mock(ICashMovementRepository.class);
        servicio = new CashSessionService(sessionRepository, movementRepository);

        when(sessionRepository.save(any(CashSession.class))).thenAnswer(i -> {
            CashSession s = i.getArgument(0);
            if (s.getId() == null) s.setId(1);
            return s;
        });
        when(movementRepository.save(any(CashMovement.class))).thenAnswer(i -> i.getArgument(0));
        when(movementRepository.findBySessionIdOrderByCreatedAtAsc(anyInt())).thenReturn(List.of());
        when(sessionRepository.cobradoEnEfectivo(anyInt())).thenReturn(0d);
    }

    private CashSession abierta(double fondo) {
        CashSession s = new CashSession();
        s.setId(1);
        s.setOpenedBy(CAJERO);
        s.setOpeningFloat(fondo);
        s.setStatus(CashSession.ABIERTA);
        when(sessionRepository.findFirstByOpenedByAndStatus(CAJERO, CashSession.ABIERTA))
                .thenReturn(Optional.of(s));
        return s;
    }

    private CashMovement mov(String tipo, double importe) {
        CashMovement m = new CashMovement();
        m.setType(tipo);
        m.setAmount(importe);
        return m;
    }

    @Test
    @DisplayName("no se puede vender sin caja abierta")
    void sinCajaNoSeVende() {
        when(sessionRepository.findFirstByOpenedByAndStatus(CAJERO, CashSession.ABIERTA))
                .thenReturn(Optional.empty());

        CashSessionService.CajaException e = assertThrows(
                CashSessionService.CajaException.class, () -> servicio.exigirAbierta(CAJERO));

        assertTrue(e.getMessage().contains("fondo"), "el mensaje debe decir qué hacer: " + e.getMessage());
    }

    @Test
    @DisplayName("no se abren dos cajas a la vez")
    void unaCajaPorPersona() {
        abierta(100);
        assertThrows(CashSessionService.CajaException.class, () -> servicio.abrir(CAJERO, "Ana", 50));
    }

    @Test
    @DisplayName("el esperado es fondo + efectivo + ingresos - retiros - gastos")
    void elEsperadoSuma() {
        CashSession s = abierta(100);
        when(sessionRepository.cobradoEnEfectivo(1)).thenReturn(450d);
        when(movementRepository.findBySessionIdOrderByCreatedAtAsc(1)).thenReturn(List.of(
                mov(CashMovement.INGRESO, 50),
                mov(CashMovement.RETIRO, 200),
                mov(CashMovement.GASTO, 30)));

        // 100 + 450 + 50 - 200 - 30
        assertEquals(370d, servicio.efectivoEnCaja(s), 0.001);
    }

    @Test
    @DisplayName("Yape y tarjeta NO entran en el cuadre")
    void soloElEfectivoCuadra() {
        CashSession s = abierta(100);
        // cobradoEnEfectivo ya filtra por forma de pago; aquí se comprueba
        // que el servicio no suma nada más por su cuenta.
        when(sessionRepository.cobradoEnEfectivo(1)).thenReturn(200d);
        when(sessionRepository.ventasPorForma(1)).thenReturn(List.of(
                Map.of("forma", "efectivo", "importe", 200, "ventas", 2),
                Map.of("forma", "yape", "importe", 900, "ventas", 5)));

        assertEquals(300d, servicio.efectivoEnCaja(s), 0.001,
                "los 900 de Yape no pasaron por el cajón");

        Map<String, Object> r = servicio.resumen(s);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> formas = (List<Map<String, Object>>) r.get("byPaymentType");
        assertEquals(2, formas.size(), "pero sí se informan, para ver el total del turno");
    }

    @Test
    @DisplayName("el arqueo guarda la diferencia, sobre o falte")
    void elArqueoGuardaLaDiferencia() {
        abierta(100);
        when(sessionRepository.cobradoEnEfectivo(1)).thenReturn(400d);

        CashSession cerrada = servicio.cerrar(CAJERO, 495, "faltaron 5");

        assertEquals(500d, cerrada.getExpectedCash(), 0.001);
        assertEquals(495d, cerrada.getCountedCash(), 0.001);
        assertEquals(-5d, cerrada.getDifference(), 0.001, "negativo = falta");
        assertEquals(CashSession.CERRADA, cerrada.getStatus());
    }

    @Test
    @DisplayName("mientras está abierta no se enseña el esperado")
    void elEsperadoNoSeFiltraAntesDelArqueo() {
        CashSession s = abierta(100);
        when(sessionRepository.cobradoEnEfectivo(1)).thenReturn(400d);

        Map<String, Object> r = servicio.resumen(s);

        // Quien ve la cifra objetivo deja de contar y la copia: el arqueo
        // dejaría de detectar nada.
        assertFalse(r.containsKey("expectedCash"),
                "enseñar el esperado antes de contar invalida el arqueo");
        assertEquals(400d, r.get("cashSales"), "las ventas sí se ven: no son el objetivo");
    }

    @Test
    @DisplayName("no se puede retirar más de lo que hay")
    void noSeRetiraDeMas() {
        abierta(100);
        when(sessionRepository.cobradoEnEfectivo(1)).thenReturn(50d);

        CashSessionService.CajaException e = assertThrows(CashSessionService.CajaException.class,
                () -> servicio.registrarMovimiento(CAJERO, CashMovement.RETIRO, 500, "al banco"));

        assertTrue(e.getMessage().contains("150"), "debe decir cuánto hay: " + e.getMessage());
    }

    @Test
    @DisplayName("un movimiento sin motivo se rechaza")
    void elMotivoEsObligatorio() {
        abierta(100);
        assertThrows(CashSessionService.CajaException.class,
                () -> servicio.registrarMovimiento(CAJERO, CashMovement.RETIRO, 10, "  "));
    }

    @Test
    @DisplayName("los retiros y los gastos restan; los ingresos suman")
    void elSignoLoPoneElTipo() {
        assertEquals(50d, mov(CashMovement.INGRESO, 50).delta(), 0.001);
        assertEquals(-50d, mov(CashMovement.RETIRO, 50).delta(), 0.001);
        assertEquals(-50d, mov(CashMovement.GASTO, 50).delta(), 0.001);
    }
}
