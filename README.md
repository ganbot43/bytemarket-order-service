# bytemarket-order-service

Pedidos, métodos de pago, cupones de descuento y reportes del panel.

Parte del sistema **ByteMarket**, una tienda de repuestos y accesorios para
celulares construida con microservicios Spring Boot y un frontend Nuxt.

**Puerto 8083** · Base de datos `bytemarket_order`

## API

Todo entra por el gateway (`http://localhost:8085`), no directamente al 8083.

### Compra — requiere sesión

| Método | Ruta | Qué hace |
|---|---|---|
| `POST` | `/api/orders` | Crea el pedido. Ver el cuerpo más abajo |
| `GET` | `/api/orders/{code}` | Seguimiento por código. Solo el dueño o staff |
| `GET` | `/api/my-orders` | Pedidos de la cuenta |
| `GET` | `/api/my-orders/{code}` | Detalle de uno propio |

El cuerpo del checkout:

```json
{ "customerName": "Cliente Demo",
  "customerPhone": "987654321",
  "customerAddress": "Av. Javier Prado Este 1234",
  "customerReference": "Piso 3",
  "paymentMethodId": 1,
  "couponCode": "BIENVENIDO10",
  "items": [ { "productId": 1, "quantity": 2 } ] }
```

Pedir un pedido ajeno responde **404**, no 403: un 403 confirmaría que el
código existe.

### Pagos y cupones — público

| Método | Ruta | Qué hace |
|---|---|---|
| `GET` | `/api/payment-methods?active=true` | Métodos de pago disponibles |
| `POST` | `/api/coupons/validate` | Calcula el descuento **sin gastar** el cupón. Body: `code`, `subtotal` |

`/validate` responde `200` con `valid: false` y un mensaje cuando el cupón no
sirve: que un código sea inválido no es un error de la petición, y así el
checkout solo tiene que pintar el mensaje.

### Panel — requiere rol `admin` o `superadmin`

| Método | Ruta | Qué hace |
|---|---|---|
| `GET` | `/api/admin/orders` | Lista paginada. Filtros `status`, `limit`, `offset`. Incluye `totalAll`, `totalPending`, `totalDelivered` |
| `GET` | `/api/admin/orders/{id}` | Detalle con líneas e historial de estados |
| `PUT` `PATCH` | `/api/admin/orders/{id}/status` | Cambia el estado. Body: `status`, `note?` |
| `GET` `POST` | `/api/admin/payment-methods` | Métodos de pago |
| `PUT` `DELETE` | `/api/admin/payment-methods/{id}` | |
| `GET` `POST` | `/api/admin/coupons` | Cupones |
| `PUT` `DELETE` | `/api/admin/coupons/{id}` | Un cupón ya usado no se borra (409): se desactiva |
| `POST` | `/api/admin/inventory/backfill` | Reconstruye los movimientos de pedidos antiguos |

### Reportes del panel

| Método | Ruta | Devuelve |
|---|---|---|
| `GET` | `/api/admin/automation/reports` | `totals` (ventas y pedidos de hoy y la semana), `topProducts`, `lowStock` |
| `GET` | `/api/admin/automation/stock-alerts?threshold=` | Productos bajo el umbral |
| `GET` | `/api/admin/automation/follow-ups?hours=` | Pedidos abiertos fuera de plazo |
| `GET` | `/api/admin/reports/sales-chart?days=` | Ventas por día |
| `GET` | `/api/admin/reports/export` | Pedidos y productos para Excel/PDF |

### Estados del pedido

```
pending → voucher_sent → payment_validated → in_preparation → delivered
                                                            ↘ cancelled
```

Cada cambio deja su registro en el historial con el autor y una nota
opcional. Cancelar **devuelve el stock al catálogo**, y solo una vez.

## El precio lo pone el catálogo

En el checkout el navegador solo envía **producto y cantidad**. El precio, el
nombre y el stock se consultan a catalog-service por Feign, así que no se
puede comprar a un precio inventado. Al confirmar se descuenta el stock; al
cancelar, se devuelve.

## Cupones

`previewCoupon` valida y calcula el descuento **sin gastarlo**, para que el
checkout pueda mostrarlo antes de confirmar; solo `applyCoupon` lo consume.
Si validar gastara el cupón, con solo mirarlo se perdería uno de un único uso.

El descuento nunca deja el total en negativo: se recorta al subtotal.

## Cómo levantarlo

### Con Docker (recomendado)

Este servicio es una pieza del sistema; lo normal es levantarlo junto a los
demás desde la carpeta padre, que trae el `docker-compose.yml`:

```bash
cd ..
docker compose up -d
```

Para ver solo su log o reiniciarlo:

```bash
docker compose logs -f order-service
docker compose restart order-service
```

El `Dockerfile` de este repo es multietapa: compila con Maven y la imagen
final solo lleva el JRE y el jar. No hace falta empaquetar antes.

### A mano

Requisitos: **Java 17+**, **MySQL 8** en `localhost:3306` y el
`bytemarket-eureka-server` ya arrancado (salvo que este repo *sea* Eureka).

```bash
./mvnw spring-boot:run
```

> La configuración sale del `.env` de la **carpeta padre**, uno solo para
> todos los servicios. Lo carga `spring-dotenv` gracias a
> `src/main/resources/.env.properties`, que apunta a `..`; al arrancar desde
> Eclipse el directorio de trabajo es esta carpeta, así que lo encuentra. Si
> falta, `JWT_SECRET` queda vacío: el servicio levanta igual pero rechaza
> cualquier token con 401 y sin dejar rastro en el log.


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
