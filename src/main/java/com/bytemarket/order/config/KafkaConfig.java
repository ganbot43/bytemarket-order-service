package com.bytemarket.order.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * El topic donde vive el historial de los pedidos.
 *
 * Por qué Kafka y no la cola de RabbitMQ que ya existe: no hacen lo mismo.
 *
 * RabbitMQ lleva una ORDEN —"descuenta este stock"— con un único destinatario
 * que la consume y la borra. Si nadie la procesa, se queda pendiente; una vez
 * procesada, desaparece.
 *
 * Esto es un HECHO ya ocurrido —"el pedido BM-20261015-4821 pasó a
 * en_preparación"— y tiene tres propiedades que una cola de trabajo no da:
 *
 *  1. Varios consumidores independientes leen lo mismo. Hoy support-service
 *     avisa al cliente por correo; mañana un consumidor de métricas puede
 *     leer el MISMO evento sin que order-service se entere ni haya que
 *     declarar otra cola.
 *  2. Los eventos se retienen aunque ya se hayan leído. Un consumidor nuevo
 *     puede rebobinar y reconstruir su estado desde el principio.
 *  3. Orden garantizado por pedido, porque la clave del mensaje es el
 *     orderCode y Kafka manda todas las claves iguales a la misma partición.
 *     Importa: "pagado" antes de "entregado" no puede llegar al revés.
 *
 * Tres particiones para poder repartir la carga entre varias instancias del
 * consumidor; factor de réplica 1 porque el broker de docker-compose es uno
 * solo (en producción esto sería 3).
 */
@Configuration
public class KafkaConfig {

    public static final String TOPIC_PEDIDOS = "bytemarket.pedidos";

    /** Se declara desde el código para no depender de crear el topic a mano. */
    @Bean
    NewTopic topicPedidos() {
        return TopicBuilder.name(TOPIC_PEDIDOS)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
