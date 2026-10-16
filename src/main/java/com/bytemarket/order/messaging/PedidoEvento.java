package com.bytemarket.order.messaging;

import java.time.LocalDateTime;

/**
 * Lo que se publica en bytemarket.pedidos cuando un pedido nace o cambia.
 *
 * Va con los datos que el consumidor necesita DENTRO del evento —nombre,
 * correo, total— y no solo con el id. Así support-service puede avisar al
 * cliente sin llamar de vuelta a order-service: si tuviera que preguntar,
 * un order-service caído dejaría los correos sin enviar y habríamos cambiado
 * una dependencia síncrona por otra.
 *
 * El contrato se repite a propósito en el consumidor en vez de compartir un
 * jar: es el mismo criterio que ya se siguió con la cola de stock. Un
 * esquema compartido significa que ningún servicio puede evolucionar su
 * copia sin recompilar los demás, y entonces no son servicios independientes.
 */
public record PedidoEvento(
        String tipo,
        String orderCode,
        Long orderId,
        String estado,
        String estadoAnterior,
        String customerName,
        String customerEmail,
        Double total,
        LocalDateTime ocurridoEn) {

    /** Pedido recién confirmado por el cliente. */
    public static final String CREADO = "PEDIDO_CREADO";

    /** El panel movió el pedido a otro estado. */
    public static final String ESTADO_CAMBIADO = "PEDIDO_ESTADO_CAMBIADO";
}
