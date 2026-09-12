# Featurify MVP

Сервис feature toggles с изоляцией конфигурации по tenant'ам. Конфигурация не кэшируется: каждый запрос читает актуальное состояние из PostgreSQL, поэтому REST API можно безопасно масштабировать горизонтально.

## Что реализовано

- типы `BOOLEAN` и `ENUM`;
- независимые ключи и значения в каждом tenant'е;
- публичный read-only REST API;
- административный REST API и Vaadin UI;
- optimistic locking по обязательному полю `version`;
- полное удаление фич, групп и tenant с каскадным удалением вложенных данных;
- аудит смены значения с `preferred_username` из Keycloak;
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

После изменения исходников пересоберите приложение: `docker compose up --build -d app`.
Иначе на `localhost:8080` продолжит работать предыдущая версия UI.
Системный tenant `default` с названием `Default` создаётся миграцией и всегда остаётся
единственным default tenant. Создание других tenant не меняет его; параметра
`defaultTenant` в запросе создания и переключателя в UI нет.

Для MVP вся схема, включая группы фич, создаётся одной миграцией
`V1__create_feature_toggle_schema.sql`. Она предназначена для новой базы.
База с ранее применёнными версиями `V1` / `V2` потребует пересоздания перед запуском
обновлённой сборки; при необходимости предварительно сохраните данные.

### Работа с группами в UI

- `New group` создаёт пустую группу и сразу выбирает её в фильтре.
- Фильтр `Group` показывает все группы, только `Global` или конкретную группу.
- Для переноса выберите строку фичи → `Move to group` → целевую группу → `Move feature`.
  `Global` переносит фичу из группы в глобальную область; текущую группу нельзя выбрать повторно.
- `Delete` полностью удаляет выбранную фичу, её enum-опции и историю.
- `Manage groups` позволяет удалить группу вместе со всеми её фичами.
- `Delete tenant` удаляет tenant, все его группы и фичи, включая `Global`.
  Для системного `default` действие недоступно; удаление также запрещено сервисом и DB-триггером.
- Удаление требует подтверждения в UI и необратимо.
- Поиск и группа применяются совместно до пагинации. `Reset filters` возвращает
  все фичи tenant. Ошибки ввода сохраняют открытой форму для исправления.

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
GET /api/v1/features?tenant={tenantKey}&page=0&size=20&query=checkout
GET /api/v1/features/{key}?tenant={tenantKey}&group={groupKey}
GET /api/v1/features:resolve?tenant={tenantKey}&group={groupKey}&keys=a,b,c
```

Admin endpoints принимают Keycloak Bearer token либо browser OAuth2 session:

```text
POST  /api/v1/tenants
GET   /api/v1/tenants
DELETE /api/v1/tenants/{tenantKey}
POST  /api/v1/groups?tenant={tenantKey}
GET   /api/v1/groups?tenant={tenantKey}
DELETE /api/v1/groups/{groupKey}?tenant={tenantKey}
POST  /api/v1/features?tenant={tenantKey}
PATCH /api/v1/features/{key}?tenant={tenantKey}&group={groupKey}
PATCH /api/v1/features/{key}/group?tenant={tenantKey}&group={currentGroupKey}
DELETE /api/v1/features/{key}?tenant={tenantKey}&group={groupKey}
GET   /api/v1/features/{key}/history?tenant={tenantKey}&group={groupKey}
```

Примеры тел запросов:

```json
{"key":"blue","displayName":"Blue environment"}
```

```json
{
  "key": "checkout.payment-provider",
  "group": "checkout",
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

Группу можно создать телом `{"key":"checkout","displayName":"Checkout"}`. Перенос фичи
выполняется телом `{"version":0,"targetGroup":"checkout"}`; `targetGroup: null` переносит
фичу в глобальную область tenant. DELETE фичи и группы требует тело `{"version":1}`
с текущей версией; DELETE tenant не требует тела. Успешное удаление возвращает `204 No Content`.
Каскады БД удаляют вложенные фичи, enum-опции и историю одной транзакцией.
Удалённые ключи можно использовать повторно. DELETE системного `default` возвращает `409 Conflict`.
DB-триггеры также запрещают его изменение, прямой DELETE и TRUNCATE таблицы tenant.

Все REST-ошибки имеют единый вид:

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "details": [{"field":"enumValue","message":"must be present in enumOptions"}]
}
```

Устаревшая версия возвращает `409 Conflict`; неизвестная или удалённая фича в публичном API — `404 Not Found`.
Список фич возвращается как Spring Data `Page`: элементы находятся в `content`, рядом передаются метаданные страницы и общее количество элементов.
Номер страницы начинается с нуля, размер страницы по умолчанию равен 20; сортировка по умолчанию выполняется по `key`.
Параметр `tenant` необязателен во всех feature endpoints: без него используется default tenant.
Поле `group` при создании и query-параметр `group` в одиночных feature endpoints необязательны.
Без группы фича считается глобальной в tenant; уникальность обеспечивается по `(tenant, group, key)`,
причём для глобальных фич — отдельно по `(tenant, key)`.
Поиск по `query` доступен начиная с трёх символов и использует триграммный индекс PostgreSQL.

## Проверка

```shell
./gradlew test
./gradlew bootJar
```

`MainViewTests` проверяет создание группы, валидацию формы, перенос в другую группу и в
`Global`, конфликт переноса и смену tenant. Для дополнительной проверки с локальным
PostgreSQL включите `FEATURIFY_DB_TESTS=true` и запустите `./gradlew test`. Интеграционный
тест использует настройки `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` приложения и откатывает
созданные тестовые данные после выполнения. База должна быть предварительно мигрирована
запуском приложения.
