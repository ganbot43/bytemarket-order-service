package com.bytemarket.order.messaging;

import com.bytemarket.order.config.KafkaConfig;
import com.bytemarket.order.model.Order;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Publica el ciclo de vida del pedido en Kafka.
 *
 * Conviven dos mensajerías y no se solapan:
 *
 *   RabbitMQ (StockPublisher) → "descuenta 2 unidades del producto 14".
 *   Una orden, un consumidor, hay que ejecutarla exactamente una vez.
 *   Si se pierde, el inventario queda mal.
 *
 *   Kafka (esta clase) → "el pedido BM-… pasó a en_preparación".
 *   Un hecho, cualquier número de consumidores, retenido y releíble.
 *   Si se pierde, el pedido sigue correcto: solo nadie se enteró.
 *
 * La clave del mensaje es el orderCode, no el id: así todos los eventos de
 * un mismo pedido caen en la misma partición y se leen en el orden en que
 * ocurrieron. Sin clave, Kafka reparte en round-robin y "entregado" podría
 * leerse antes que "pago validado".
 *
 * Publicar NO puede tumbar el pedido: igual que en StockPublisher, el fallo
 * se registra y se sigue. La diferencia es que aquí es más llevadero —se
 * pierde un aviso, no una venta—, y por eso ni se reintenta.
 */
@Component
@RequiredArgsConstructor
public class PedidoEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PedidoEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publicarCreado(Order order) {
        publicar(new PedidoEvento(
                PedidoEvento.CREADO,
                order.getOrderCode(),
                order.getId() == null ? null : order.getId().longValue(),
                order.getStatus(),
                null,
                order.getCustomerName(),
                order.getCustomerEmail(),
                order.getTotal(),
                LocalDateTime.now()));
    }

    public void publicarCambioDeEstado(Order order, String estadoAnterior) {
        publicar(new PedidoEvento(
                PedidoEvento.ESTADO_CAMBIADO,
                order.getOrderCode(),
                order.getId() == null ? null : order.getId().longValue(),
                order.getStatus(),
                estadoAnterior,
                order.getCustomerName(),
                order.getCustomerEmail(),
                order.getTotal(),
                LocalDateTime.now()));
    }

    private void publicar(PedidoEvento evento) {
        try {
            kafkaTemplate.send(KafkaConfig.TOPIC_PEDIDOS, evento.orderCode(), evento);
            log.info("Pedido {}: publicado '{}' en {}",
                    evento.orderCode(), evento.tipo(), KafkaConfig.TOPIC_PEDIDOS);
        } catch (Exception e) {
            // Kafka caído no deshace el pedido: ya está guardado y pagado.
            log.error("Pedido {}: no se pudo publicar '{}': {}",
                    evento.orderCode(), evento.tipo(), e.getMessage());
        }
    }
}
