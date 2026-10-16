package com.bytemarket.order.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;

/**
 * El token con el que este servicio se identifica ante catalog-service.
 *
 * Hacía falta porque /api/internal/** en catalog estaba abierto a cualquiera:
 * los Feign de aquí no mandaban ninguna credencial, así que la única forma de
 * que la llamada pasara era dejar esos endpoints en permitAll(). El problema
 * es que /api/internal/products/stock-decrement cambia el inventario, y el
 * puerto 8082 está publicado en docker-compose: con un curl desde el host se
 * podía descontar stock sin ser nadie.
 *
 * No se inventa un mecanismo nuevo —ni cabecera secreta ni mTLS—: se firma un
 * JWT con el MISMO secreto que ya comparten los cuatro servicios, con rol
 * SERVICE. Así el JwtAuthenticationFilter que catalog ya tiene lo valida sin
 * un solo cambio, y basta una regla en su SecurityConfig.
 *
 * Es un token de servicio, no de persona: el subject es el nombre del
 * servicio, no un correo, y no lleva uid. Dura cinco minutos porque solo
 * tiene que sobrevivir a la llamada que lo usa; un token de servicio con la
 * caducidad de 24 h de los de usuario sería un pase robable mucho más
 * valioso.
 */
@Component
public class ServiceTokenProvider {

    private static final String SUBJECT = "bytemarket-order-service";
    private static final long VIGENCIA_MS = 5 * 60 * 1000L;

    /** Margen para no reutilizar un token a punto de caducar a mitad de vuelo. */
    private static final long MARGEN_MS = 30 * 1000L;

    @Value("${jwt.secret}")
    private String secret;

    private String cacheToken;
    private long cacheCaduca;

    /**
     * Se cachea en memoria en vez de firmar uno por llamada: el checkout pega
     * varias veces a catalog y firmar es trabajo de CPU repetido para nada.
     * synchronized porque varias peticiones entran a la vez.
     */
    public synchronized String token() {
        long ahora = System.currentTimeMillis();
        if (cacheToken == null || ahora > cacheCaduca - MARGEN_MS) {
            cacheToken = firmar(ahora);
            cacheCaduca = ahora + VIGENCIA_MS;
        }
        return cacheToken;
    }

    private String firmar(long ahora) {
        return Jwts.builder()
                .setClaims(Map.of("role", "SERVICE"))
                .setSubject(SUBJECT)
                .setIssuedAt(new Date(ahora))
                .setExpiration(new Date(ahora + VIGENCIA_MS))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes()), SignatureAlgorithm.HS256)
                .compact();
    }
}
