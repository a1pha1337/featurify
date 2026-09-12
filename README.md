# Featurify MVP

Сервис feature toggles с изоляцией конфигурации по namespace'ам. Конфигурация не кэшируется: каждый запрос читает актуальное состояние из PostgreSQL, поэтому REST API можно безопасно масштабировать горизонтально.

Ключи namespace, групп и фич поддерживают `camelCase`, `PascalCase`, `kebab-case` и
составные имена с точками, например `checkout.payment-provider`.
Разрешены латинские буквы, цифры после первого символа и одиночные `-`/`.` внутри ключа.
Ключ всегда начинается с буквы; `_`, пробелы, повторные/концевые разделители и другие
спецсимволы запрещены.

## Что реализовано

- типы `BOOLEAN` и `ENUM`;
- независимые ключи и значения в каждом namespace'е;
- read-only REST API для бекендов с namespace-токенами;
- административный REST API и Vaadin UI;
- optimistic locking по обязательному полю `version`;
- полное удаление фич, групп и namespace с каскадным удалением вложенных данных;
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
Системный namespace `default` с названием `Default` создаётся миграцией и всегда остаётся
единственным default namespace. Создание других namespace не меняет его; параметра
`defaultNamespace` в запросе создания и переключателя в UI нет.

Для MVP вся схема, включая группы фич, создаётся одной миграцией
`V1__create_feature_toggle_schema.sql`. Она предназначена для новой базы.
База с ранее применёнными версиями `V1` / `V2` потребует пересоздания перед запуском
обновлённой сборки; при необходимости предварительно сохраните данные.

### Работа с группами в UI

- `New group` создаёт пустую группу и сразу выбирает её в фильтре.
- Фильтр `Group` показывает все группы, только `Global` или конкретную группу.
- Для переноса выберите строку фичи → `Edit` → поле `Group` → `Save`.
  `Global` переносит фичу из группы в глобальную область; чтобы оставить группу прежней, не меняйте поле.
- Группа, описание и значение в `Edit` сохраняются одной транзакцией.
- Значения можно менять прямо в таблице: тумблер для `BOOLEAN`, dropdown для `ENUM`.
  Изменения сохраняются сразу, с проверкой версии и аудитом. При ошибке отображается прежнее значение.
- При создании `ENUM` вводите варианты в `Allowed values` и нажимайте Enter.
  Каждый вариант отображается тегом с крестиком для удаления. Начальное значение выбирается
  из добавленных вариантов в `Current enum value`.
- `Delete` полностью удаляет выбранную фичу, её enum-опции и историю.
- `Manage groups` позволяет удалить группу вместе со всеми её фичами.
- `Delete namespace` удаляет namespace, все его группы и фичи, включая `Global`.
  Для системного `default` действие недоступно; удаление также запрещено сервисом и DB-триггером.
- Удаление требует подтверждения в UI и необратимо.
- Поиск и группа применяются совместно до пагинации. `Reset filters` возвращает
  все фичи namespace. Ошибки ввода сохраняют открытой форму для исправления.

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

Read-only endpoints (`PublicFeatureController`) требуют namespace-токен в
`Authorization: Bearer <token>`, тот же, что используется для gRPC:

```text
GET /api/v1/features?namespace={namespaceKey}&page=0&size=20&query=checkout
GET /api/v1/features/{key}?namespace={namespaceKey}&group={groupKey}
GET /api/v1/features:resolve?namespace={namespaceKey}&group={groupKey}&keys=a,b,c
```

Namespace определяется токеном. Параметр `namespace` можно опустить; если он указан,
он должен совпадать с ключом namespace токена, иначе ответ — `403 Forbidden`.
Запросы к БД ограничены UUID namespace токена. Отсутствующий, неверный или отозванный
токен, удалённый или неактивный namespace дают `401 Unauthorized`.
Keycloak JWT и browser session сами по себе не дают доступа к этим endpoints.
Правило также действует для HEAD. Токены передаются только в заголовке, не в URL.

| Интерфейс | Авторизация | Область доступа |
|---|---|---|
| `AccessTokenController` | Keycloak JWT или OAuth2 login session | Управление токенами |
| `AdminFeatureController` (включая историю, группы и namespace) | Keycloak JWT или OAuth2 login session | Администрирование |
| `PublicFeatureController` | Namespace-токен | Только чтение в namespace токена |
| gRPC `FeatureService` | Namespace-токен в metadata `authorization` | Только чтение в namespace токена |

Namespace-токены не предоставляют административных прав и не сохраняются в HTTP-сессии.

Admin endpoints принимают Keycloak Bearer token либо browser OAuth2 session:

```text
POST  /api/v1/namespaces
GET   /api/v1/namespaces
DELETE /api/v1/namespaces/{namespaceKey}
POST  /api/v1/groups?namespace={namespaceKey}
GET   /api/v1/groups?namespace={namespaceKey}
DELETE /api/v1/groups/{groupKey}?namespace={namespaceKey}
POST  /api/v1/features?namespace={namespaceKey}
PATCH /api/v1/features/{key}?namespace={namespaceKey}&group={groupKey}
PATCH /api/v1/features/{key}/group?namespace={namespaceKey}&group={currentGroupKey}
DELETE /api/v1/features/{key}?namespace={namespaceKey}&group={groupKey}
GET   /api/v1/features/{key}/history?namespace={namespaceKey}&group={groupKey}
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
фичу в глобальную область namespace. DELETE фичи и группы требует тело `{"version":1}`
с текущей версией; DELETE namespace не требует тела. Успешное удаление возвращает `204 No Content`.
Каскады БД удаляют вложенные фичи, enum-опции и историю одной транзакцией.
Удалённые ключи можно использовать повторно. DELETE системного `default` возвращает `409 Conflict`.
DB-триггеры также запрещают его изменение, прямой DELETE и TRUNCATE таблицы namespace.

Все REST-ошибки, включая ошибки авторизации и стандартные ошибки Spring MVC,
возвращаются как `application/problem+json` по [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html).
HTTP status совпадает с полем `status`. Успешные ответы сохраняют существующие DTO,
списки и страницы: RFC 9457 описывает только ошибки.

```json
{
  "type": "urn:featurify:problem:validation-error",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/v1/features",
  "code": "VALIDATION_ERROR",
  "details": [{"field":"enumValue","message":"must be present in enumOptions"}]
}
```

`type` — стабильный идентификатор вида ошибки; `code` — машинный код расширения.
`details` присутствует при ошибках валидации, `instance` содержит путь запроса без query.
Клиенты должны ориентироваться на HTTP status и `type`/`code`, а не разбирать `detail`.
Старое поле `message` заменено на `detail`. Ошибки не содержат секретов и stack trace.

| HTTP status | `code` |
|---|---|
| 400 | `VALIDATION_ERROR`, `MALFORMED_JSON` |
| 401 / 403 | `UNAUTHORIZED` / `FORBIDDEN` |
| 404 / 409 | `NOT_FOUND` / `CONFLICT` |
| 405 / 406 / 415 | `METHOD_NOT_ALLOWED` / `NOT_ACCEPTABLE` / `UNSUPPORTED_MEDIA_TYPE` |
| 500 / 503 | `INTERNAL_ERROR` / `SERVICE_UNAVAILABLE` |

Ответы 401 содержат `WWW-Authenticate: Bearer`; ответы с ошибками имеют `Cache-Control: no-store`.

Устаревшая версия возвращает `409 Conflict`; неизвестная или удалённая фича в публичном API — `404 Not Found`.
Список фич возвращается как Spring Data `Page`: элементы находятся в `content`, рядом передаются метаданные страницы и общее количество элементов.
Номер страницы начинается с нуля, размер страницы по умолчанию равен 20; сортировка по умолчанию выполняется по `key`.
В административных feature endpoints без `namespace` используется default namespace.
В read-only endpoints без `namespace` используется namespace предъявленного токена.
Поле `group` при создании и query-параметр `group` в одиночных feature endpoints необязательны.
Без группы фича считается глобальной в namespace; уникальность обеспечивается по `(namespace, group, key)`,
причём для глобальных фич — отдельно по `(namespace, key)`.
Поиск по `query` доступен начиная с трёх символов и использует триграммный индекс PostgreSQL.

## gRPC для бекендов и токены namespace

В UI выберите namespace → `Access tokens` → укажите имя → `Generate token`.
Скопируйте токен: секрет показывается только после создания. Можно создать несколько
независимых токенов и отозвать любой из них кнопкой `Revoke`. Удаление namespace удаляет
все его токены. Системный `default` также поддерживает токены.

Административный REST API использует существующую авторизацию Keycloak:

```text
POST   /api/v1/namespaces/{namespaceKey}/tokens
GET    /api/v1/namespaces/{namespaceKey}/tokens
DELETE /api/v1/namespaces/{namespaceKey}/tokens/{tokenId}
```

POST принимает `{"name":"checkout-backend"}` и возвращает `201` с полями `id`, `name`,
`createdAt`, `token` и заголовком `Cache-Control: no-store`. GET возвращает только метаданные;
получить секрет повторно нельзя. DELETE возвращает `204` и отзывает токен.
Как и остальные административные операции MVP, эти ручки доступны любому
аутентифицированному пользователю Keycloak. Токен бекенда не даёт административного доступа.

Формат токена — `ft_<UUID>.<secret>`. Секрет генерируется `SecureRandom` (256 бит),
кодируется Base64URL и хранится в БД только как bcrypt-хеш с cost 12 и случайной солью.
UUID используется для поиска записи; проверяется именно секрет, без перебора хешей.
Хешируется 43-символьный секрет, что укладывается в ограничение bcrypt в 72 байта.
Таблица токенов добавлена в V1; для уже применённой V1 потребуется новая БД.

Контракт: `src/main/proto/feature_service.proto`. gRPC слушает порт `9090`.

Сервер запускает официальный `org.springframework.boot:spring-boot-starter-grpc-server`.
Spring регистрирует `FeatureGrpcService` через `@GrpcService` и подключает к нему
`TokenAuthenticationInterceptor`. Жизненным циклом сервера управляет Spring; время
graceful shutdown — 5 секунд, максимальный размер входящего сообщения — 16 КБ.
Версии gRPC, Protobuf и генераторов кода согласованы dependency management Spring Boot.
Автоматическая OAuth2-авторизация gRPC отключена: Keycloak используется только для HTTP,
а gRPC продолжает проверять namespace-токены в PostgreSQL.

```text
featurify.v1.FeatureService/GetBooleanFeature
featurify.v1.FeatureService/GetEnumFeature
```

Оба метода принимают `{"group":"checkout","key":"enabled"}`. `group` — ключ группы,
не display name; пустая или пропущенная группа означает `Global`. Namespace в запросе
отсутствует и определяется только токеном. Ответ содержит типизированное `value`
(`bool` или `string`) и `version`.

Каждый RPC требует metadata `authorization: Bearer <token>`. Нет токена, неверный,
отозванный токен, удалённый или неактивный namespace → `UNAUTHENTICATED`.
Неизвестная группа/фича → `NOT_FOUND`; некорректный ключ → `INVALID_ARGUMENT`;
вызов метода для другого типа фичи → `FAILED_PRECONDITION`.
Проверка токена и чтение значения выполняются на каждом вызове без кэша.

Пример локального вызова через grpcurl (контракт передаётся явно, reflection не включён):

```shell
grpcurl -plaintext -import-path src/main/proto -proto feature_service.proto \
  -H "authorization: Bearer $FEATURE_TOKEN" \
  -d '{"group":"checkout","key":"enabled"}' \
  localhost:9090 featurify.v1.FeatureService/GetBooleanFeature
```

`GRPC_PORT` задаёт порт (в Compose — порт хоста), `GRPC_ADDRESS` — адрес привязки,
`GRPC_ENABLED=false` отключает сервер. Настройки теперь находятся в `spring.grpc.server.*`;
старые свойства `grpc.server.*` больше не используются. Переменные `GRPC_PORT`,
`GRPC_ADDRESS` и `GRPC_ENABLED` сохранены.

TLS настраивается через Spring SSL bundle. Включите профиль `grpc-tls`
(`SPRING_PROFILES_ACTIVE=grpc-tls`, либо добавьте его к существующим профилям) и задайте
вместе `GRPC_TLS_CERTIFICATE` и `GRPC_TLS_PRIVATE_KEY`: ресурсы PEM-сертификата и ключа,
например `file:/certs/server.crt` и `file:/certs/server.key`. После перехода на стартер
для этих двух переменных требуется профиль `grpc-tls`. Без сертификата или ключа
TLS-профиль не позволит приложению запуститься.
В контейнере смонтируйте файлы и передайте профиль и переменные через Compose override.
Локальная конфигурация использует plaintext; вне доверенной локальной среды используйте
TLS на gRPC-сервере либо на доверенном прокси, а для выдачи токенов через REST/UI — HTTPS.
Внешний TLS-прокси должен поддерживать gRPC/HTTP2 и передавать `authorization`.

REST-чтение фич и gRPC используют одни namespace-токены. Административный REST API
продолжает использовать Keycloak.

### Ошибки gRPC и будущий клиентский стартер

Для gRPC применяется [стандартная расширенная модель ошибок](https://grpc.io/docs/guides/error/):
код gRPC в trailers и `google.rpc.Status` в `grpc-status-details-bin`.
Ошибки приложения содержат `google.rpc.ErrorInfo` с `domain: featurify` и стабильным `reason`;
ошибки валидации дополнительно содержат `google.rpc.BadRequest.field_violations`.

| gRPC code | `ErrorInfo.reason` |
|---|---|
| `UNAUTHENTICATED` | `UNAUTHORIZED` |
| `INVALID_ARGUMENT` | `VALIDATION_ERROR` |
| `NOT_FOUND` | `NOT_FOUND` |
| `FAILED_PRECONDITION` | `FEATURE_TYPE_MISMATCH` |
| `UNAVAILABLE` | `SERVICE_UNAVAILABLE` |
| `INTERNAL` | `INTERNAL_ERROR` |

Успешные protobuf-ответы остаются `value` и `version`, без обёртки success/error.
В Java/Kotlin детали извлекаются через `StatusProto.fromThrowable(exception)` и распаковку
`ErrorInfo` / `BadRequest`. Транспортные ошибки (`DEADLINE_EXCEEDED`, `CANCELLED`,
`RESOURCE_EXHAUSTED` и другие) могут не содержать расширенных деталей — клиент должен
уметь обрабатывать один стандартный status code. Наличие `UNAVAILABLE` не предписывает
безусловные повторы: deadline и политику повторов определяет клиент.

Клиентский стартер запланирован на следующую итерацию и пока не реализован. Для него
зафиксированы metadata `authorization: Bearer <token>`, текущий `.proto` и этот контракт
ошибок; namespace в запросы gRPC передавать не требуется.

Сборка генерирует Java gRPC/Protobuf классы из `.proto`. Build-стадия Docker использует
JDK на Ubuntu для совместимости с бинарниками protoc; runtime остаётся на Alpine.

## Проверка

```shell
./gradlew test
./gradlew bootJar
```

Тесты проверяют UI, административный REST, gRPC-вызовы, bcrypt и изоляцию namespace.
Для интеграционной проверки с PostgreSQL включите `FEATURIFY_DB_TESTS=true` и запустите
`./gradlew test`. Используйте отдельную тестовую БД через `DB_URL` / `DB_USERNAME` /
`DB_PASSWORD`: Flyway применяет V1 автоматически, а тестовые данные откатываются.
Этот режим также проверяет цепочку Spring Security, хранение токенов, отзыв и каскады БД.
Дополнительно запускается настоящий сервер Spring gRPC на случайном порту: проверяются
автоматическая регистрация сервиса и авторизации, изоляция namespace, отзыв токенов
и ограничение размера сообщения. Те же RPC-проверки выполняются через TLS с проверкой
сертификата и профилем `grpc-tls`. Тестовые namespace удаляются после этих RPC-тестов.
