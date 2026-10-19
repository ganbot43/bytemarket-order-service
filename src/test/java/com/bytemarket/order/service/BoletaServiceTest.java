package com.bytemarket.order.service;

import com.bytemarket.order.model.BoletaSerie;
import com.bytemarket.order.model.Order;
import com.bytemarket.order.repository.IBoletaSerieRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Los números de boleta.
 *
 * Un correlativo no es un id cualquiera: no se repite y no se reutiliza.
 * Eso es lo que convierte el papel del mostrador en un comprobante.
 */
@DisplayName("Boletas — serie y correlativo")
class BoletaServiceTest {

    private IBoletaSerieRepository serieRepository;
    private BoletaService servicio;
    private BoletaSerie contador;

    @BeforeEach
    void setUp() {
        serieRepository = mock(IBoletaSerieRepository.class);
        servicio = new BoletaService(serieRepository);
        ReflectionTestUtils.setField(servicio, "serie", "B001");

        contador = new BoletaSerie();
        contador.setSerie("B001");
        contador.setLastNumber(0);
        when(serieRepository.bloquear("B001")).thenReturn(Optional.of(contador));
        when(serieRepository.save(any(BoletaSerie.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Order venta() {
        return new Order();
    }

    @Test
    @DisplayName("la primera boleta es la número 1")
    void empiezaEnUno() {
        Order o = venta();
        servicio.asignar(o);

        assertEquals("B001", o.getBoletaSerie());
        assertEquals(1, o.getBoletaNumero());
        // El contador guarda el último emitido, no el siguiente: así se lee
        // sin tener que acordarse de por qué estaba inicializado a 1.
        assertEquals(1, contador.getLastNumber());
    }

    @Test
    @DisplayName("se imprime con la serie y ocho dígitos")
    void seImprimeConCeros() {
        Order o = venta();
        servicio.asignar(o);
        assertEquals("B001-00000001", o.numeroDeBoleta());

        contador.setLastNumber(1233);
        Order otra = venta();
        servicio.asignar(otra);
        assertEquals("B001-00001234", otra.numeroDeBoleta());
    }

    @Test
    @DisplayName("cada venta se lleva un número distinto")
    void nuncaSeRepite() {
        Order a = venta(), b = venta(), c = venta();
        servicio.asignar(a);
        servicio.asignar(b);
        servicio.asignar(c);

        assertEquals(1, a.getBoletaNumero());
        assertEquals(2, b.getBoletaNumero());
        assertEquals(3, c.getBoletaNumero());
        // El número sale del contador bloqueado y no de un MAX()+1 sobre
        // las ventas: dos cobros a la vez leerían el mismo máximo.
        verify(serieRepository, times(3)).bloquear("B001");
    }

    @Test
    @DisplayName("una venta que ya tiene boleta no gasta otro número")
    void noSeNumeraDosVeces() {
        Order o = venta();
        servicio.asignar(o);
        servicio.asignar(o);

        assertEquals(1, o.getBoletaNumero());
        assertEquals(1, contador.getLastNumber(), "el segundo intento no gastó correlativo");
    }

    @Test
    @DisplayName("si la serie no existe todavía, se crea")
    void creaLaSerieSiFalta() {
        when(serieRepository.bloquear("B001")).thenReturn(Optional.empty());

        Order o = venta();
        servicio.asignar(o);

        assertEquals(1, o.getBoletaNumero());
    }

    @Test
    @DisplayName("sin venta no hace nada")
    void sinVentaNoHaceNada() {
        assertDoesNotThrow(() -> servicio.asignar(null));
        verify(serieRepository, never()).bloquear(any());
    }

    @Test
    @DisplayName("una venta sin boleta no finge tener una")
    void sinBoletaNoHayNumero() {
        // Un pedido por cobrar no lleva comprobante: el papel acompaña al
        // dinero. Y no debe imprimirse "B001-null" ni "-00000000".
        assertNull(venta().numeroDeBoleta());
    }
}
