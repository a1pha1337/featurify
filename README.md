# Featurify MVP

Сервис feature toggles с изоляцией конфигурации по tenant'ам. Конфигурация не кэшируется: каждый запрос читает актуальное состояние из PostgreSQL, поэтому REST API можно безопасно масштабировать горизонтально.

## Что реализовано

- типы `BOOLEAN` и `ENUM`;
- независимые ключи и значения в каждом tenant'е;
- публичный read-only REST API только для активных фич;
- административный REST API и Vaadin UI;
- optimistic locking по обязательному полю `version`;
- архивирование без физического удаления;
- аудит смены значения и архивирования с `preferred_username` из Keycloak;
- OAuth2 Login для UI и JWT Bearer authentication для admin API;
- Flyway-схема с уникальностями, check constraints и DB-триггером неизменяемости типа;
- health probes и graceful shutdown.

В исходной постановке одновременно указаны Liquibase и Flyway. Использован Flyway, потому что требование создать именно Flyway-миграции сформулировано отдельно и конкретно.

## Локальный запуск всего окружения

Требуется только Docker с Compose:

```shell
docker compose up --build -d
docker compose ps
```

После готовности контейнеров:

- Vaadin UI: `http://localhost:8080`;
- Keycloak: `http://localhost:8081`;
- Keycloak Admin Console: пользователь `admin`, пароль `admin`;
- пользователь Featurify UI: `featurify`, пароль `featurify`;
- PostgreSQL: `localhost:5432`, база/пользователь/пароль `featurify`.

Все пароли в compose предназначены только для локальной разработки.

Realm `featurify`, confidential client и локальный пользователь импортируются автоматически из
`docker/keycloak/featurify-realm.json`. При первом запуске PostgreSQL также создаёт отдельную базу
`keycloak`. Остановка с сохранением данных: `docker compose down`. Полный сброс окружения:
`docker compose down -v`.

Для запуска приложения из IDE можно поднять только зависимости:

```shell
docker compose up -d postgres keycloak
./gradlew bootRun
```

Переменные окружения:

| Переменная | Значение по умолчанию |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/featurify` |
| `DB_USERNAME` / `DB_PASSWORD` | `featurify` / `featurify` |
| `KEYCLOAK_BASE_URL` | общий URL Keycloak, по умолчанию `http://localhost:8081` |
| `KEYCLOAK_PUBLIC_URL` | URL для browser redirect; переопределяет общий URL |
| `KEYCLOAK_INTERNAL_URL` | URL для server-to-server запросов; переопределяет общий URL |
| `KEYCLOAK_REALM` | `featurify` |
| `KEYCLOAK_CLIENT_ID` | `featurify` |
| `KEYCLOAK_CLIENT_SECRET` | `change-me` |

В поставляемом compose redirect URI уже настроен. Любой аутентифицированный пользователь имеет административный доступ в рамках MVP. Если изменить `APP_PORT`, нужно также изменить redirect URI и web origin в realm JSON.

## REST API

Публичные endpoints не требуют токена:

```text
GET /api/v1/tenants/{tenantKey}/features
GET /api/v1/tenants/{tenantKey}/features/{key}
GET /api/v1/tenants/{tenantKey}/features:resolve?keys=a,b,c
```

Admin endpoints принимают Keycloak Bearer token либо browser OAuth2 session:

```text
POST  /api/v1/tenants
GET   /api/v1/tenants
POST  /api/v1/tenants/{tenantKey}/features
PATCH /api/v1/tenants/{tenantKey}/features/{key}
POST  /api/v1/tenants/{tenantKey}/features/{key}/archive
GET   /api/v1/tenants/{tenantKey}/features/{key}/history
```

Примеры тел запросов:

```json
{"key":"blue","displayName":"Blue environment"}
```

```json
{
  "key": "checkout.payment-provider",
  "type": "ENUM",
  "description": "Payment routing",
  "enumValue": "CAT",
  "enumOptions": ["MONKEY", "CAT", "DOG"]
}
```

```json
{"version":0,"enumValue":"DOG","description":"Updated routing"}
```

```json
{"version":1}
```

Все REST-ошибки имеют единый вид:

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "details": [{"field":"enumValue","message":"must be present in enumOptions"}]
}
```

Устаревшая версия возвращает `409 Conflict`; неизвестная или архивная фича в публичном API — `404 Not Found`.

## Проверка

```shell
./gradlew test
./gradlew bootJar
```
