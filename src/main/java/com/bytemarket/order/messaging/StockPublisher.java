package com.bytemarket.order.messaging;

import com.bytemarket.order.config.RabbitConfig;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publica el movimiento de stock en la cola.
 *
 * Antes esto era una llamada Feign directa a catalog-service dentro de un
 * try/catch: si catalog estaba caído, el pedido se confirmaba igual y el
 * descuento se perdía dejando solo un log.error. Había incluso una
 * herramienta —/api/admin/inventory/backfill— para reconstruir a mano lo
 * perdido.
 *
 * Con la cola ya no hay que elegir entre perder la venta o perder el
 * descuento: el mensaje espera en RabbitMQ hasta que catalog vuelva.
 *
 * Importante: esto cubre el DESCUENTO, no la validación. Comprobar que hay
 * stock sigue siendo síncrono en CartService, porque el cliente tiene que
 * saber antes de pagar si quedan unidades.
 */
@Component
@RequiredArgsConstructor
public class StockPublisher {

    private static final Logger log = LoggerFactory.getLogger(StockPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public void publicarSalida(Long orderId, String orderCode, List<Map<String, Object>> items) {
        publicar("exit", orderId, orderCode, items);
    }

    public void publicarEntrada(Long orderId, String orderCode, List<Map<String, Object>> items) {
        publicar("entry", orderId, orderCode, items);
    }

    private void publicar(String tipo, Long orderId, String orderCode, List<Map<String, Object>> items) {
        Map<String, Object> evento = new LinkedHashMap<>();
        evento.put("tipo", tipo);
        evento.put("orderId", orderId);
        evento.put("orderCode", orderCode);
        evento.put("items", items);

        try {
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.RK_STOCK, evento);
            log.info("Pedido {}: movimiento de stock '{}' encolado", orderCode, tipo);
        } catch (Exception e) {
            // Si RabbitMQ tampoco está, se vuelve al comportamiento anterior:
            // el pedido queda en pie y esto se avisa para ajustarlo a mano.
            // La cola traslada la dependencia a un componente más estable,
            // no la elimina.
            log.error("Pedido {}: no se pudo encolar el movimiento '{}': {}",
                    orderCode, tipo, e.getMessage());
        }
    }
}
