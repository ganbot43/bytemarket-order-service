package com.bytemarket.order.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cola del movimiento de stock.
 *
 * Los dos servicios declaran lo mismo para que el orden de arranque no
 * importe: si catalog levanta primero, la cola ya existe cuando order
 * publica, y al revés. Declarar una cola que ya existe con los mismos
 * parámetros no da error.
 *
 * Todo es durable y los mensajes persistentes: el sentido de esto es que un
 * descuento de stock sobreviva a que catalog —o el propio RabbitMQ— se caiga.
 * Con una cola en memoria, reiniciar el broker perdería los pedidos pendientes
 * de descontar, que es justo lo que se quiere evitar.
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "bytemarket.inventario";
    public static final String COLA_STOCK = "inventario.stock";
    public static final String RK_STOCK = "inventario.stock.movimiento";

    /** Adonde van los mensajes que fallan incluso tras los reintentos. */
    public static final String EXCHANGE_FALLIDOS = "bytemarket.inventario.fallidos";
    public static final String COLA_FALLIDOS = "inventario.stock.fallidos";

    @Bean
    DirectExchange inventarioExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    DirectExchange inventarioFallidosExchange() {
        return new DirectExchange(EXCHANGE_FALLIDOS, true, false);
    }

    /**
     * Un mensaje que no se puede procesar —un producto borrado, un cuerpo
     * corrupto— no se descarta: se aparta aquí para revisarlo a mano. Sin
     * esta cola, RabbitMQ lo reencolaría sin fin y bloquearía a los demás.
     */
    @Bean
    Queue colaStock() {
        return QueueBuilder.durable(COLA_STOCK)
                .deadLetterExchange(EXCHANGE_FALLIDOS)
                .deadLetterRoutingKey(RK_STOCK)
                .build();
    }

    @Bean
    Queue colaFallidos() {
        return QueueBuilder.durable(COLA_FALLIDOS).build();
    }

    @Bean
    Binding bindStock(Queue colaStock, DirectExchange inventarioExchange) {
        return BindingBuilder.bind(colaStock).to(inventarioExchange).with(RK_STOCK);
    }

    @Bean
    Binding bindFallidos(Queue colaFallidos, DirectExchange inventarioFallidosExchange) {
        return BindingBuilder.bind(colaFallidos).to(inventarioFallidosExchange).with(RK_STOCK);
    }

    /** JSON en vez de serialización Java: así el mensaje se puede leer desde
        el panel de RabbitMQ y no ata los dos servicios a la misma clase. */
    @Bean
    MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
