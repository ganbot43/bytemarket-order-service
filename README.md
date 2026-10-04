# bytemarket-order-service

Pedidos, métodos de pago, cupones de descuento y reportes del panel.

Parte del sistema **ByteMarket**, una tienda de repuestos y accesorios para
celulares construida con microservicios Spring Boot y un frontend Nuxt.

## Qué hace

- Checkout y seguimiento de pedidos
- Estados con historial: `pending` → `voucher_sent` → `payment_validated` → `in_preparation` → `delivered`, o `cancelled`
- Métodos de pago y **cupones de descuento**
- Reportes y automatizaciones del panel

### El precio lo pone el catálogo

En el checkout el navegador solo envía **producto y cantidad**. El precio, el
nombre y el stock se consultan a catalog-service por Feign, así que no se
puede comprar a un precio inventado. Al confirmar se descuenta el stock; al
cancelar, se devuelve.

### Cupones

`previewCoupon` valida y calcula el descuento **sin gastarlo**, para que el
checkout pueda mostrarlo antes de confirmar; solo `applyCoupon` lo consume.
Si validar gastara el cupón, con solo mirarlo se perdería uno de un único uso.

Base de datos: `bytemarket_order`

## Cómo levantarlo

Requisitos: **Java 17+**, **MySQL 8** en `localhost:3306` y el
`bytemarket-eureka-server` ya arrancado (salvo que este repo *sea* Eureka).

```bash
cp .env.example .env     # y rellena los valores
./mvnw spring-boot:run
```

Queda escuchando en el puerto **8083**. El esquema de base de datos se crea
solo al arrancar (`createDatabaseIfNotExist=true`).

## Configuración

Las credenciales se leen del `.env`, que **no se versiona**. Los
`application*.yml` solo traen marcadores: si falta el `.env`, los valores
sensibles quedan vacíos. Mira `.env.example` para saber qué rellenar.

> El `JWT_SECRET` debe ser **idéntico** en user, catalog, order y support:
> user-service firma el token y los demás verifican la firma. Si difieren,
> todas las peticiones autenticadas fallan con 401 sin dejar rastro en el log.

## El sistema completo

| Repositorio | Puerto | Función |
|---|---|---|
| `bytemarket-eureka-server` | 8761 | Registro de servicios |
| `bytemarket-api-gateway` | 8085 | Punto de entrada único; enruta a los demás |
| `bytemarket-user-service` | 8081 | Cuentas, autenticación JWT, perfiles |
| `bytemarket-catalog-service` | 8082 | Productos, categorías, banners, inventario |
| `bytemarket-order-service` | 8083 | Pedidos, métodos de pago, cupones, reportes |
| `bytemarket-support-service` | 8084 | Libro de reclamaciones |
| `frontend-bytemarket` | 3000 | Tienda y panel de administración (Nuxt 3) |

Orden de arranque: **Eureka primero**, luego los servicios de negocio, el
gateway al final y el frontend cuando el gateway responda.
