package com.bytemarket.order.service;

import com.bytemarket.order.model.BoletaSerie;
import com.bytemarket.order.model.Order;
import com.bytemarket.order.repository.IBoletaSerieRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reparte los números de boleta.
 *
 * Una boleta de venta se identifica por su serie y su correlativo —B001 y
 * 00000123—, y el correlativo tiene dos reglas que lo hacen distinto de un
 * id cualquiera: no se repite y no se reutiliza.
 *
 * Lo primero lo garantiza el bloqueo de la fila del contador. Lo segundo
 * es una decisión: si una venta se anula, su número se queda con ella. Lo
 * que corresponde entonces es una nota de crédito; reciclar el correlativo
 * dejaría dos papeles distintos diciendo ser el mismo comprobante.
 *
 * Esto NO emite a SUNAT. Es la numeración interna del negocio, que es lo
 * que hace falta para que el papel del mostrador sea rastreable y para que
 * el día que se conecte la facturación electrónica haya algo que enviar.
 */
@Service
@RequiredArgsConstructor
public class BoletaService {

    /**
     * La serie con la que se emite.
     *
     * Configurable porque en una tienda con varias cajas cada punto de
     * venta suele llevar la suya: con una sola compartida, dos cajeros se
     * pelean por el mismo contador y uno de los dos espera en cada cobro.
     */
    @Value("${bytemarket.boleta.serie:B001}")
    private String serie;

    private final IBoletaSerieRepository serieRepository;

    /**
     * Le pone número a una venta cobrada.
     *
     * Transacción propia y obligatoria: va dentro de la de la venta, así
     * que si la venta se cae el número no se gasta. Reservar el correlativo
     * y no llegar a guardar la venta dejaría un hueco en la numeración que
     * nadie sabría explicar.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void asignar(Order order) {
        if (order == null || order.getBoletaNumero() != null) return;

        // Bloquea la fila del contador: dos cobros a la vez se ponen en
        // cola y cada uno se lleva su número. Con un MAX(numero)+1 los dos
        // leerían lo mismo y saldrían dos boletas iguales.
        BoletaSerie contador = serieRepository.bloquear(serie).orElseGet(() -> {
            BoletaSerie nueva = new BoletaSerie();
            nueva.setSerie(serie);
            nueva.setLastNumber(0);
            return serieRepository.save(nueva);
        });

        contador.setLastNumber(contador.getLastNumber() + 1);
        serieRepository.save(contador);

        order.setBoletaSerie(serie);
        order.setBoletaNumero(contador.getLastNumber());
    }
}
