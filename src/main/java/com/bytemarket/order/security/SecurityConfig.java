package com.bytemarket.order.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * - /api/admin/**   solo admin y superadmin (rol tomado del JWT)
 * - /api/internal/** llamadas entre microservicios (no se exponen en el gateway)
 * - resto: público; los endpoints de cliente validan la sesión en el controlador
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * El filtro llega como parámetro del @Bean y no como campo inyectado:
     * una clase @Configuration que además produce beans puede entrar en
     * conflicto de creación si se inyecta por constructor, y de este modo
     * la dependencia queda donde se usa.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthFilter,
                                                   ModuloAuthorizationManager porModulo) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.disable())
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                /*
                 * /api/admin/** deja de ser todo o nada.
                 *
                 * Con una sola regla, el cajero que necesita cobrar podía
                 * también cambiar cupones y leer los reportes de ventas. El
                 * control interno de una caja se apoya justo en lo
                 * contrario: quien vende no cuadra, y quien cuadra no
                 * cambia precios.
                 *
                 * El orden importa: Spring se queda con la PRIMERA regla
                 * que encaja, así que lo específico va antes que el
                 * comodín.
                 */

                /*
                 * Todo /api/admin lo decide la MATRIZ de permisos.
                 *
                 * Aquí había once reglas distinguiendo a mano, verbo por
                 * verbo, cosas como que abrir y cerrar el turno son del
                 * cajero pero contarlo y cuadrarlo son de su jefe. Ese
                 * reparto no se ha perdido: está en RutasDeModulo, que
                 * traduce cada ruta a su módulo —"caja" lo del cajero,
                 * "cajas" lo de su jefe— y deja que el método decida si hace
                 * falta ver o editar.
                 *
                 * El motivo del cambio es que esas once reglas hacían mentir
                 * a la pantalla de Roles y permisos: quitarle Pedidos a
                 * alguien escondía el menú y no cerraba la API, y el 8083
                 * está publicado en el compose.
                 */
                .requestMatchers("/api/admin/**").access(porModulo)
                // La sonda queda abierta porque el healthcheck de Docker la
                // consulta sin credenciales. No filtra nada: con
                // show-details: when-authorized, al anónimo le responde solo
                // {"status":"UP"}.
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // El resto del actuator, solo staff. Aquí viven el estado de
                // los circuitos y las métricas, y el 8083 está publicado en
                // docker-compose: dejarlos abiertos contaría a cualquiera
                // cómo y cuándo falla el servicio.
                .requestMatchers("/actuator/**").hasAnyRole("ADMIN", "SUPERADMIN")
                .anyRequest().permitAll()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> json(res, HttpServletResponse.SC_UNAUTHORIZED, "No autenticado"))
                .accessDeniedHandler((req, res, e) -> json(res, HttpServletResponse.SC_FORBIDDEN, "Acceso denegado"))
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void json(HttpServletResponse res, int status, String message) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"statusCode\":" + status + ",\"message\":\"" + message + "\"}");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
