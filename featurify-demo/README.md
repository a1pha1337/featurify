# Featurify demo

Небольшое приложение на Spring Boot 4.1.1 / Java 25, использующее
`featurify-grpc-starter` и транзитивно `featurify-grpc-client`.
HTTP-ручки позволяют проверить авторизацию, чтение фич, группы, TTL и ошибки gRPC.
Demo не требует собственной БД или Keycloak. Для чтения нужен работающий Featurify
и namespace-токен; фичи создаются через основной UI или административный API.

## Запуск

Из корня репозитория, PowerShell:

```powershell
$env:FEATURE_TOKEN = 'ваш-namespace-токен'
.\gradlew.bat :featurify-demo:bootRun
```

Bash:

```shell
FEATURE_TOKEN='ваш-namespace-токен' ./gradlew :featurify-demo:bootRun
```

По умолчанию HTTP слушает `127.0.0.1:8082`, а gRPC-клиент подключается к
`localhost:9090` без TLS — как в локальном Compose-окружении Featurify.
Отсутствующий или некорректный токен останавливает запуск. Соединение с сервером
проверяется при чтении фичи.

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `FEATURE_TOKEN` | обязательна | Namespace-токен без `Bearer` |
| `DEMO_PORT` | `8082` | HTTP-порт |
| `DEMO_ADDRESS` | `127.0.0.1` | Адрес HTTP-сервера |
| `FEATURIFY_GRPC_HOST` | `localhost` | Хост Featurify |
| `FEATURIFY_GRPC_PORT` | `9090` | gRPC-порт |
| `FEATURIFY_GRPC_TLS` | `false` | Включить TLS |
| `FEATURIFY_GRPC_TRUST_CERTIFICATE` | системные CA | PEM-ресурс, например `file:/certs/ca.crt` |
| `FEATURIFY_GRPC_TIMEOUT` | `2s` | Deadline каждого RPC |
| `FEATURIFY_GRPC_DEFAULT_GROUP` | Global | Группа при отсутствии параметра `group` |
| `FEATURIFY_GRPC_CACHE_TTL` | `1s` | TTL ответов; `0s` отключает кеш |
| `FEATURIFY_GRPC_CACHE_MAXIMUM_SIZE` | `10000` | Лимит записей на сервис |

Demo предназначено для локальной диагностики: HTTP-ручки используют один настроенный
namespace-токен и не требуют отдельной HTTP-авторизации.

## Ручки

| Метод и путь | Назначение |
|---|---|
| `GET /actuator/health` | Состояние самого demo-приложения |
| `GET /diagnostics/client` | Хост, порт, TLS, timeout, defaultGroup и настройки кеша; без токена |
| `GET /diagnostics/features/boolean/{key}` | Boolean-фича |
| `GET /diagnostics/features/enum/{key}` | Значение enum-фичи |
| `GET /diagnostics/features/vector/{key}/{element}` | Состояние элемента vector |

Для трёх ручек чтения доступны query-параметры:

- `group=checkout` — конкретная группа; без параметра применяется `defaultGroup`;
- `group=` — явно выбрать Global, даже при настроенной группе по умолчанию;
- `cached=true` (по умолчанию) — чтение через сервисы стартера с кешем Caffeine;
- `cached=false` — прямой вызов `FeaturifyClient`, каждый раз выполняющий RPC.

Примеры для фич `enabled`, `provider` и vector `animals` с элементом `DOG`:

```shell
curl "http://localhost:8082/actuator/health"
curl "http://localhost:8082/diagnostics/client"
curl "http://localhost:8082/diagnostics/features/boolean/enabled?group=checkout"
curl "http://localhost:8082/diagnostics/features/enum/provider?group=checkout&cached=false"
curl "http://localhost:8082/diagnostics/features/vector/animals/DOG?group="
```

В PowerShell при необходимости используйте `curl.exe`. Создайте эти фичи в namespace
вашего токена или замените ключи в примерах на существующие.

Пример успешного ответа:

```json
{
  "type": "BOOLEAN",
  "key": "enabled",
  "group": "checkout",
  "element": null,
  "value": true,
  "version": 3,
  "cacheAllowed": true,
  "elapsedMicros": 120
}
```

`elapsedMicros` — время чтения внутри demo, без HTTP-сериализации и сетевой доставки.
`cacheAllowed` показывает, разрешено ли кеширование для вызова, а не факт попадания
в кеш. Чтобы увидеть эффект TTL, повторите чтение после изменения фичи на сервере
и сравните его с `cached=false`. Прямое чтение не обновляет кеш сервисов.
`group: null` в ответе означает Global.

При ошибке возвращается `application/problem+json` с `grpcCode` и описанием ошибки:
`INVALID_ARGUMENT` → 400, `UNAUTHENTICATED` → 401, `PERMISSION_DENIED` → 403,
`NOT_FOUND` → 404, конфликты/`FAILED_PRECONDITION` → 409,
`RESOURCE_EXHAUSTED` → 429, `UNAVAILABLE` → 503, `DEADLINE_EXCEEDED` → 504,
остальные ошибки RPC → 502. Ошибки не кешируются.

Health и `/diagnostics/client` не выполняют RPC. Для проверки доступности Featurify
и валидности токена используйте любую ручку чтения с `cached=false`.

## Docker Compose

После создания токена в основном Featurify UI задайте `FEATURE_TOKEN` и выполните:

```shell
docker compose --profile demo up --build -d demo
```

Demo подключается к `app:9090` и публикует HTTP только на `127.0.0.1:8082`.
При необходимости можно задать `DEMO_PORT` и `FEATURIFY_GRPC_DEFAULT_GROUP`.
Обычный `docker compose up` не запускает demo; профиль подключается явно.

## Проверка и сборка

```shell
./gradlew :featurify-demo:check :featurify-demo:bootJar
```

Интеграционные тесты запускают HTTP-приложение и настоящий локальный gRPC-сервер
на случайных портах. Проверяются Bearer-токен, три типа фич, группы, обход кеша,
ошибки, deadline и отсутствие токена в диагностике. Внешние сервисы не нужны.
Исполняемый JAR: `featurify-demo/build/libs/featurify-demo-0.0.1-SNAPSHOT.jar`.
