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
                                                   JwtAuthenticationFilter jwtAuthFilter) throws Exception {
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

                // Vender: es lo único que hace un cajero.
                .requestMatchers(HttpMethod.POST, "/api/admin/orders")
                    .hasAnyRole("CAJERO", "JEFE_VENTAS", "ADMIN", "SUPERADMIN")
                /*
                 * Las tres cosas que hace el cajero con su caja: verla,
                 * abrirla y cerrarla.
                 *
                 * Abrir es suyo porque esa confirmación es la que firma en
                 * el ticket de apertura: es lo que convierte el fondo en
                 * responsabilidad suya. Cerrar también, porque cerrar es
                 * «he acabado el turno», no «esto es lo que hay».
                 *
                 * Contar no. Asignar el fondo, mover el efectivo, arquear y
                 * cuadrar son del jefe: quien vende no cuenta su propio
                 * cajón. Esas rutas caen en el comodín de abajo.
                 */
                .requestMatchers(HttpMethod.GET, "/api/admin/caja/sesion")
                    .hasAnyRole("CAJERO", "JEFE_VENTAS", "ADMIN", "SUPERADMIN")
                .requestMatchers(HttpMethod.POST, "/api/admin/caja/abrir", "/api/admin/caja/cerrar")
                    .hasAnyRole("CAJERO", "JEFE_VENTAS", "ADMIN", "SUPERADMIN")
                /*
                 * El cajero NO lista pedidos.
                 *
                 * Se lo había abierto pensando en "que pueda atender a
                 * quien pregunta por el suyo", pero ese listado trae el
                 * nombre, el teléfono, el correo y la dirección de cada
                 * cliente de la tienda: para resolver una consulta puntual
                 * se entregaba la base de datos entera.
                 *
                 * "Vende y atiende" es el mostrador. La consulta de pedidos
                 * web es del jefe o del encargado; si algún día el cajero la
                 * necesita, será con una búsqueda por código exacto, no con
                 * el listado.
                 */
                .requestMatchers(HttpMethod.GET, "/api/admin/orders/**")
                    .hasAnyRole("JEFE_VENTAS", "ADMIN", "SUPERADMIN")

                // Fondos, movimientos, arqueo y cuadre: del jefe de ventas.
                .requestMatchers("/api/admin/caja/**")
                    .hasAnyRole("JEFE_VENTAS", "ADMIN", "SUPERADMIN")
                // Reportes: el jefe los necesita para cuadrar el día.
                .requestMatchers("/api/admin/reports/**", "/api/admin/automation/**")
                    .hasAnyRole("JEFE_VENTAS", "ADMIN", "SUPERADMIN")

                // Lo demás —cupones, métodos de pago, cambiar estados— es
                // gestión de tienda.
                .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "SUPERADMIN")
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
