package com.bytemarket.order.service;

import com.bytemarket.order.model.Order;
import com.bytemarket.order.model.SerieDocumento;
import com.bytemarket.order.repository.ISerieDocumentoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reparte los números de los dos documentos que emite la caja.
 *
 *   boleta de venta      B001-00000123   se la lleva el cliente
 *   voucher de comercio  V001-00000045   se lo queda el cajero
 *
 * Los dos son correlativos, y un correlativo tiene dos reglas que lo hacen
 * distinto de un id cualquiera: no se repite y no se reutiliza.
 *
 * Lo primero lo garantiza el bloqueo de la fila del contador. Lo segundo
 * es una decisión: si una venta se anula, sus números se quedan con ella.
 * Lo que corresponde entonces es una nota de crédito; reciclar el
 * correlativo dejaría dos papeles distintos diciendo ser el mismo
 * documento.
 *
 * El voucher sale solo cuando el pago deja un papel —tarjeta o QR—. En
 * efectivo no hay voucher: el comprobante del cajero es el propio dinero.
 *
 * Esto NO emite a SUNAT ni habla con Niubiz. Es la numeración interna del
 * negocio: lo que hace rastreable el papel del mostrador y lo que permite
 * cuadrar los vouchers contra el sistema al cerrar el turno.
 */
@Service
@RequiredArgsConstructor
public class NumeracionService {

    /**
     * Las series con las que se emite.
     *
     * Configurables porque en una tienda con varias cajas cada punto de
     * venta suele llevar las suyas: con una sola compartida, dos cajeros
     * se pelean por el mismo contador y uno de los dos espera en cada
     * cobro.
     */
    @Value("${bytemarket.boleta.serie:B001}")
    private String serieBoleta;

    @Value("${bytemarket.voucher.serie:V001}")
    private String serieVoucher;

    private final ISerieDocumentoRepository serieRepository;

    /** La boleta que se lleva el cliente. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void asignarBoleta(Order order) {
        if (order == null || order.getBoletaNumero() != null) return;
        int numero = siguiente(serieBoleta);
        order.setBoletaSerie(serieBoleta);
        order.setBoletaNumero(numero);
    }

    /**
     * El voucher de comercio, el papel que se queda el cajero.
     *
     * Solo donde hay algo que cuadrar contra un papel: tarjeta y QR. En
     * efectivo se devuelve sin tocar nada.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void asignarVoucher(Order order) {
        if (order == null || order.getVoucherNumber() != null) return;
        String forma = order.getPaymentMethodType();
        if (forma == null || "efectivo".equals(forma)) return;
        order.setVoucherNumber(serieVoucher + "-" + String.format("%08d", siguiente(serieVoucher)));
    }

    /**
     * Reserva el siguiente número de una serie.
     *
     * Bloquea la fila del contador: dos cobros a la vez se ponen en cola y
     * cada uno se lleva el suyo. Con un MAX(numero)+1 sobre las ventas los
     * dos leerían lo mismo y saldrían dos documentos iguales.
     */
    private int siguiente(String serie) {
        SerieDocumento contador = serieRepository.bloquear(serie).orElseGet(() -> {
            SerieDocumento nueva = new SerieDocumento();
            nueva.setSerie(serie);
            nueva.setLastNumber(0);
            return serieRepository.save(nueva);
        });
        contador.setLastNumber(contador.getLastNumber() + 1);
        serieRepository.save(contador);
        return contador.getLastNumber();
    }
}
