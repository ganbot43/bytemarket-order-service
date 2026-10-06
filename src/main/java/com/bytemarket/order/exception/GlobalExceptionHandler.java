package com.bytemarket.order.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import com.bytemarket.order.service.CartService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Da una sola forma a los errores que escapan de los controladores.
 *
 * Todos responden {"statusCode": N, "message": "..."}, que es lo que ya
 * devuelven los controladores a mano y lo que el frontend lee en
 * e.data.message. Antes la versión genérica devolvía {"error", "message"} y
 * convertía CUALQUIER excepción en 500: una ruta inexistente daba 500 en vez
 * de 404, y un método equivocado también.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Ruta que no existe. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noEncontrado(NoResourceFoundException ex) {
        return respuesta(HttpStatus.NOT_FOUND, "La ruta solicitada no existe");
    }

    /** GET donde se esperaba POST, y parecidos. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> metodoNoPermitido(HttpRequestMethodNotSupportedException ex) {
        return respuesta(HttpStatus.METHOD_NOT_ALLOWED,
                "El método " + ex.getMethod() + " no está permitido en esta ruta");
    }

    /** Falta un parámetro obligatorio en la query. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> faltaParametro(MissingServletRequestParameterException ex) {
        return respuesta(HttpStatus.BAD_REQUEST, "Falta el parámetro '" + ex.getParameterName() + "'");
    }

    /** Un id que debía ser número y llegó como texto. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> tipoInvalido(MethodArgumentTypeMismatchException ex) {
        return respuesta(HttpStatus.BAD_REQUEST, "El valor de '" + ex.getName() + "' no es válido");
    }

    /** Cuerpo JSON mal formado. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> cuerpoIlegible(HttpMessageNotReadableException ex) {
        return respuesta(HttpStatus.BAD_REQUEST, "El cuerpo de la petición no es un JSON válido");
    }

    /** Validación de @Valid: se añaden los campos sin perder el "message". */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validacion(MethodArgumentNotValidException ex) {
        Map<String, String> campos = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField,
                        e -> e.getDefaultMessage() == null ? "valor no válido" : e.getDefaultMessage(),
                        (a, b) -> a));

        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("statusCode", HttpStatus.BAD_REQUEST.value());
        cuerpo.put("message", "Revisa los datos enviados");
        cuerpo.put("errors", campos);
        return ResponseEntity.badRequest().body(cuerpo);
    }

    /**
     * Error de negocio del carrito: cupón vencido, producto sin stock, precio
     * cambiado. Los controladores ya lo capturan uno a uno; esto cubre el que
     * se escape, para que siga siendo un 400 y no un 500.
     */
    @ExceptionHandler(CartService.CartException.class)
    public ResponseEntity<Map<String, Object>> carrito(CartService.CartException ex) {
        return respuesta(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * Lo que no encaja en nada de lo anterior.
     *
     * El detalle va al log, no a la respuesta: ex.getMessage() de un fallo de
     * SQL o un NullPointer expone nombres de tablas y rutas internas a quien
     * llame a la API.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> inesperado(Exception ex) {
        log.error("Error no controlado", ex);
        return respuesta(HttpStatus.INTERNAL_SERVER_ERROR, "Ocurrió un error interno. Inténtalo de nuevo.");
    }

    private ResponseEntity<Map<String, Object>> respuesta(HttpStatus estado, String mensaje) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("statusCode", estado.value());
        cuerpo.put("message", mensaje);
        return ResponseEntity.status(estado).body(cuerpo);
    }
}
