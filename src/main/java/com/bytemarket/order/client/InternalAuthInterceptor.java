package com.bytemarket.order.client;

import com.bytemarket.order.security.ServiceTokenProvider;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Pone el token de servicio en cada llamada Feign al catálogo.
 *
 * Al ser un @Bean de configuración global, lo cogen los tres clientes
 * (pricing, inventory, reporting) sin que haya que acordarse en cada uno:
 * olvidarlo en uno solo daría un 403 difícil de ver, justo en el checkout.
 *
 * No se reenvía el token del cliente que hizo la petición original a
 * propósito. Esas llamadas son del servicio, no del usuario: /resolve lee
 * precios de productos que el cliente no tiene por qué poder consultar, y
 * stock-decrement lo dispara el consumidor de una cola, donde no hay ningún
 * usuario en el contexto del que copiar nada.
 */
@Configuration
@RequiredArgsConstructor
public class InternalAuthInterceptor {

    private final ServiceTokenProvider serviceTokenProvider;

    @Bean
    RequestInterceptor internalAuthRequestInterceptor() {
        return (RequestTemplate template) ->
                template.header("Authorization", "Bearer " + serviceTokenProvider.token());
    }
}
