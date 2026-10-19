package com.bytemarket.order.service;

import com.bytemarket.order.model.SerieDocumento;
import com.bytemarket.order.model.Order;
import com.bytemarket.order.repository.ISerieDocumentoRepository;
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
@DisplayName("Numeración — boletas y vouchers de comercio")
class NumeracionServiceTest {

    private ISerieDocumentoRepository serieRepository;
    private NumeracionService servicio;
    private SerieDocumento contador;

    @BeforeEach
    void setUp() {
        serieRepository = mock(ISerieDocumentoRepository.class);
        servicio = new NumeracionService(serieRepository);
        ReflectionTestUtils.setField(servicio, "serieBoleta", "B001");
        ReflectionTestUtils.setField(servicio, "serieVoucher", "V001");

        contador = new SerieDocumento();
        contador.setSerie("B001");
        contador.setLastNumber(0);
        when(serieRepository.bloquear("B001")).thenReturn(Optional.of(contador));
        when(serieRepository.save(any(SerieDocumento.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Order venta() {
        return new Order();
    }

    /** Una venta con la forma de pago puesta, que es lo que decide el voucher. */
    private Order venta(String forma) {
        Order o = new Order();
        o.setPaymentMethodType(forma);
        return o;
    }

    private void contadorDe(String serie) {
        SerieDocumento c = new SerieDocumento();
        c.setSerie(serie);
        c.setLastNumber(0);
        when(serieRepository.bloquear(serie)).thenReturn(Optional.of(c));
    }

    @Test
    @DisplayName("la primera boleta es la número 1")
    void empiezaEnUno() {
        Order o = venta();
        servicio.asignarBoleta(o);

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
        servicio.asignarBoleta(o);
        assertEquals("B001-00000001", o.numeroDeBoleta());

        contador.setLastNumber(1233);
        Order otra = venta();
        servicio.asignarBoleta(otra);
        assertEquals("B001-00001234", otra.numeroDeBoleta());
    }

    @Test
    @DisplayName("cada venta se lleva un número distinto")
    void nuncaSeRepite() {
        Order a = venta(), b = venta(), c = venta();
        servicio.asignarBoleta(a);
        servicio.asignarBoleta(b);
        servicio.asignarBoleta(c);

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
        servicio.asignarBoleta(o);
        servicio.asignarBoleta(o);

        assertEquals(1, o.getBoletaNumero());
        assertEquals(1, contador.getLastNumber(), "el segundo intento no gastó correlativo");
    }

    @Test
    @DisplayName("si la serie no existe todavía, se crea")
    void creaLaSerieSiFalta() {
        when(serieRepository.bloquear("B001")).thenReturn(Optional.empty());

        Order o = venta();
        servicio.asignarBoleta(o);

        assertEquals(1, o.getBoletaNumero());
    }

    @Test
    @DisplayName("el voucher de comercio sale solo donde hay papel que cuadrar")
    void elVoucherEsDeTarjetaYQr() {
        contadorDe("V001");

        // En efectivo no hay voucher: el comprobante del cajero es el
        // propio dinero, y un papel vacío solo estorba en el cuadre.
        Order enEfectivo = venta("efectivo");
        servicio.asignarVoucher(enEfectivo);
        assertNull(enEfectivo.getVoucherNumber());

        for (String forma : new String[] {"tarjeta", "yape", "plin"}) {
            Order o = venta(forma);
            servicio.asignarVoucher(o);
            assertNotNull(o.getVoucherNumber(), forma + " deja un papel que hay que cuadrar");
            assertTrue(o.getVoucherNumber().startsWith("V001-"), o.getVoucherNumber());
        }
    }

    @Test
    @DisplayName("boletas y vouchers llevan contadores distintos")
    void dosSeriesIndependientes() {
        contadorDe("V001");

        Order a = venta("tarjeta");
        servicio.asignarVoucher(a);
        servicio.asignarBoleta(a);

        // La primera venta con tarjeta del día es la boleta 1 y el voucher
        // 1; la segunda venta, si es en efectivo, gasta boleta y no gasta
        // voucher. Compartir contador los descuadraría para siempre.
        assertEquals("V001-00000001", a.getVoucherNumber());
        assertEquals("B001-00000001", a.numeroDeBoleta());

        Order b = venta("efectivo");
        servicio.asignarVoucher(b);
        servicio.asignarBoleta(b);
        assertNull(b.getVoucherNumber());
        assertEquals("B001-00000002", b.numeroDeBoleta());
    }

    @Test
    @DisplayName("una venta que ya tiene voucher no gasta otro")
    void elVoucherNoSeNumeraDosVeces() {
        contadorDe("V001");
        Order o = venta("tarjeta");
        servicio.asignarVoucher(o);
        servicio.asignarVoucher(o);
        assertEquals("V001-00000001", o.getVoucherNumber());
    }

    @Test
    @DisplayName("sin venta no hace nada")
    void sinVentaNoHaceNada() {
        assertDoesNotThrow(() -> servicio.asignarBoleta(null));
        assertDoesNotThrow(() -> servicio.asignarVoucher(null));
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
