# Featurify MVP

Сервис feature toggles с изоляцией конфигурации по namespace'ам. Конфигурация не кэшируется: каждый запрос читает
актуальное состояние из PostgreSQL, поэтому REST API можно безопасно масштабировать горизонтально.

Ключи namespace, групп и фич поддерживают `camelCase`, `PascalCase`, `kebab-case` и
составные имена с точками, например `checkout.payment-provider`.
Разрешены латинские буквы, цифры после первого символа и одиночные `-`/`.` внутри ключа.
Ключ всегда начинается с буквы; `_`, пробелы, повторные/концевые разделители и другие
спецсимволы запрещены.

## Что реализовано

- типы `BOOLEAN`, `ENUM`, `VECTOR` (независимые состояния элементов) и `PAYLOAD` (JSON-конфигурация);
- независимые ключи и значения в каждом namespace'е;
- read-only REST API для бекендов с namespace-токенами;
- административный REST API и Vaadin UI;
- optimistic locking по обязательному полю `version`;
- полное удаление фич, групп и namespace с каскадным удалением вложенных данных;
- аудит смены значения с `preferred_username` из Keycloak;
- OAuth2 Login для UI и JWT Bearer authentication для admin API;
- Flyway-схема с уникальностями, check constraints и DB-триггером неизменяемости типа;
- health probes и graceful shutdown.

## Модули

Проект собирается как Gradle multi-project на JDK 25. Сервер использует Spring Boot 4.1.1,
а три клиентских gRPC-модуля компилируются с `--release 8` и работают начиная с Java 8:

| Модуль                   | Содержимое                                                                                   |
|--------------------------|----------------------------------------------------------------------------------------------|
| `featurify-api`          | REST DTO request/response, Jakarta Validation и общие enum для сервера                       |
| `featurify-service`      | Spring Boot приложение: REST, gRPC-сервер, Vaadin UI, безопасность, JDBC и Flyway            |
| `featurify-grpc-api`     | `.proto` и сгенерированные Java gRPC/Protobuf классы без Spring, Kotlin и Jakarta Validation |
| `featurify-grpc-client`  | Java-клиент без Spring: авторизация, соединение, TLS, deadline                               |
| `featurify-grpc-starter` | Общая автоконфигурация клиента для Spring Boot 2, 3 и 4                                      |
| `featurify-demo`         | Demo-приложение на Spring Boot 4: HTTP-диагностика gRPC-клиента, кеша и групп                 |
| `featurify-k8s-operator` | Java Operator SDK: декларативное управление namespace через Kubernetes CR |

Оператор устанавливается отдельно; CRD, Deployment/RBAC, пример и описание всех сценариев —
в [featurify-k8s-operator/README.md](featurify-k8s-operator/README.md).
Он поддерживает `Exclusive/Shared`, `Managed/InitialOnly`, явное принятие существующих
ресурсов и `Retain/Delete` при удалении CR. Владение проверяется сервером и отображается в UI.
Административные ответы namespace/групп/фич дополнены полями управления; публичный REST/gRPC не меняется.

Сервис зависит от `featurify-api` и `featurify-grpc-api`.
Клиентская цепочка: `featurify-grpc-starter` → `featurify-grpc-client` → `featurify-grpc-api`.
Клиенты не подключают сервер, REST DTO, Jakarta Validation, Kotlin runtime, Vaadin или JDBC.
Библиотеки собираются в обычные JAR с публикацией Maven и исходниками.
Пакеты существующих DTO и protobuf-классов сохранены; protobuf-классы генерируются
только в `featurify-grpc-api`. Версии gRPC и Protobuf определяются в `gradle.properties`,
независимо от версии Spring Boot сервера.
SQL-миграция находится в `featurify-service/src/main/resources/db/migration`.

Для проверки клиента через HTTP запустите [featurify-demo](featurify-demo/README.md).
Demo слушает `127.0.0.1:8082`, поддерживает чтение boolean/enum/vector/payload с кешем или напрямую
и запускается через `:featurify-demo:bootRun` либо Compose-профиль `demo`.

В исходной постановке одновременно указаны Liquibase и Flyway. Использован Flyway, потому что требование создать именно
Flyway-миграции сформулировано отдельно и конкретно.

## Локальный запуск всего окружения

Для запуска Featurify вместе с оператором, PostgreSQL и Keycloak **в Kubernetes**
есть отдельный [Compose-стенд на kind](docker/local-k8s/README.md):

```shell
docker compose -f docker-compose.k8s.yaml up --build -d --wait --wait-timeout 1200
```

UI этого стенда доступен на `http://localhost:18080`; обычный Compose ниже остаётся отдельным окружением.

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
- При создании `VECTOR` добавьте имена в `Elements` и выберите включённые в `Enabled elements`.
  Остальные элементы создаются выключенными. В таблице у каждого элемента своя кнопка,
  а в `Edit` можно изменить несколько состояний одновременно.
- Для `PAYLOAD` введите JSON в поле `JSON value`. В таблице документ доступен для чтения,
  в `Edit` — для полной замены с проверкой версии и аудитом.
- `Delete` полностью удаляет выбранную фичу, её enum-опции, vector-элементы и историю.
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
./gradlew :featurify-service:bootRun
```

Переменные окружения:

| Переменная                    | Значение по умолчанию                                       |
|-------------------------------|-------------------------------------------------------------|
| `DB_URL`                      | `jdbc:postgresql://localhost:5432/featurify`                |
| `DB_USERNAME` / `DB_PASSWORD` | `featurify` / `featurify`                                   |
| `KEYCLOAK_BASE_URL`           | общий URL Keycloak, по умолчанию `http://localhost:8081`    |
| `KEYCLOAK_PUBLIC_URL`         | URL для browser redirect; переопределяет общий URL          |
| `KEYCLOAK_INTERNAL_URL`       | URL для server-to-server запросов; переопределяет общий URL |
| `KEYCLOAK_REALM`              | `featurify`                                                 |
| `KEYCLOAK_CLIENT_ID`          | `featurify`                                                 |
| `KEYCLOAK_CLIENT_SECRET`      | `change-me`                                                 |

В поставляемом compose redirect URI уже настроен. Любой аутентифицированный пользователь имеет административный доступ в
рамках MVP. Если изменить `APP_PORT`, нужно также изменить redirect URI и web origin в realm JSON.

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

| Интерфейс                                                      | Авторизация                                | Область доступа                  |
|----------------------------------------------------------------|--------------------------------------------|----------------------------------|
| `AccessTokenController`                                        | Keycloak JWT или OAuth2 login session      | Управление токенами              |
| `AdminFeatureController` (включая историю, группы и namespace) | Keycloak JWT или OAuth2 login session      | Администрирование                |
| `PublicFeatureController`                                      | Namespace-токен                            | Только чтение в namespace токена |
| gRPC `FeatureService`                                          | Namespace-токен в metadata `authorization` | Только чтение в namespace токена |

Endpoints `/api/v1/operator/namespaces/{key}` (PUT) и `/{key}/cleanup` (POST)
требуют Keycloak JWT со scope `featurify.operator`. Оператор может работать с любым
ключом namespace, переданным в манифесте, кроме системного `default`; предварительная
регистрация ключей в claims не требуется. Такие JWT не допускаются к остальным
административным API. Секреты оператора не являются namespace-токенами; настройки
client credentials описаны в README модуля оператора.

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

| HTTP status     | `code`                                                             |
|-----------------|--------------------------------------------------------------------|
| 400             | `VALIDATION_ERROR`, `MALFORMED_JSON`                               |
| 401 / 403       | `UNAUTHORIZED` / `FORBIDDEN`                                       |
| 404 / 409       | `NOT_FOUND` / `CONFLICT`                                           |
| 405 / 406 / 415 | `METHOD_NOT_ALLOWED` / `NOT_ACCEPTABLE` / `UNSUPPORTED_MEDIA_TYPE` |
| 500 / 503       | `INTERNAL_ERROR` / `SERVICE_UNAVAILABLE`                           |

Ответы 401 содержат `WWW-Authenticate: Bearer`; ответы с ошибками имеют `Cache-Control: no-store`.

Устаревшая версия возвращает `409 Conflict`; неизвестная или удалённая фича в публичном API — `404 Not Found`.
Список фич возвращается как Spring Data `Page`: элементы находятся в `content`, рядом передаются метаданные страницы и
общее количество элементов.
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

Контракт: `featurify-grpc-api/src/main/proto/feature_service.proto`. gRPC слушает порт `9090`.

Сервер запускает официальный `org.springframework.boot:spring-boot-starter-grpc-server`.
Spring регистрирует `FeatureGrpcService` через `@GrpcService` и подключает к нему
`TokenAuthenticationInterceptor`. Жизненным циклом сервера управляет Spring; время
graceful shutdown — 5 секунд, максимальный размер входящего сообщения — 16 КБ.
Версии gRPC, Protobuf и генераторов кода согласованы с сервером; клиентские зависимости
публикуются отдельно от dependency management Spring Boot.
Автоматическая OAuth2-авторизация gRPC отключена: Keycloak используется только для HTTP,
а gRPC продолжает проверять namespace-токены в PostgreSQL.

```text
featurify.v1.FeatureService/GetBooleanFeature
featurify.v1.FeatureService/GetEnumFeature
featurify.v1.FeatureService/GetPayloadFeature
featurify.v1.FeatureService/GetVectorFeature
```

BOOLEAN, ENUM и PAYLOAD методы принимают `{"group":"checkout","key":"enabled"}`. `group` — ключ группы,
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
grpcurl -plaintext -import-path featurify-grpc-api/src/main/proto -proto feature_service.proto \
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

### Ошибки gRPC

Для gRPC применяется [стандартная расширенная модель ошибок](https://grpc.io/docs/guides/error/):
код gRPC в trailers и `google.rpc.Status` в `grpc-status-details-bin`.
Ошибки приложения содержат `google.rpc.ErrorInfo` с `domain: featurify` и стабильным `reason`;
ошибки валидации дополнительно содержат `google.rpc.BadRequest.field_violations`.

| gRPC code             | `ErrorInfo.reason`      |
|-----------------------|-------------------------|
| `UNAUTHENTICATED`     | `UNAUTHORIZED`          |
| `INVALID_ARGUMENT`    | `VALIDATION_ERROR`      |
| `NOT_FOUND`           | `NOT_FOUND`             |
| `FAILED_PRECONDITION` | `FEATURE_TYPE_MISMATCH` |
| `UNAVAILABLE`         | `SERVICE_UNAVAILABLE`   |
| `INTERNAL`            | `INTERNAL_ERROR`        |

Успешные protobuf-ответы остаются `value` и `version`, без обёртки success/error.
В Java/Kotlin детали извлекаются через `StatusProto.fromThrowable(exception)` и распаковку
`ErrorInfo` / `BadRequest`. Транспортные ошибки (`DEADLINE_EXCEEDED`, `CANCELLED`,
`RESOURCE_EXHAUSTED` и другие) могут не содержать расширенных деталей — клиент должен
уметь обрабатывать один стандартный status code. Наличие `UNAVAILABLE` не предписывает
безусловные повторы: deadline и политику повторов определяет клиент.

### Подключение клиентского стартера

Опубликуйте три клиентские библиотеки в локальный Maven-репозиторий:

```shell
./gradlew :featurify-grpc-api:publishToMavenLocal :featurify-grpc-client:publishToMavenLocal :featurify-grpc-starter:publishToMavenLocal
```

В существующем Spring Boot приложении добавьте зависимость. Для SB2 и SB3 используется
один и тот же артефакт; зависимости Spring Boot предоставляет само приложение:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("ru.a1pha1337:featurify-grpc-starter:0.0.1-SNAPSHOT")
}
```

`featurify-grpc-client` и `featurify-grpc-api` подключаются транзитивно; генерировать
protobuf-классы в приложении не нужно. Стартер не приносит Spring Boot 4 и не обновляет
версию Spring в приложении. Для локального сервера из Compose настройте `application.yaml`:

```yaml
featurify:
  grpc:
    host: localhost
    port: 9090
    token: ${FEATURE_TOKEN}
    timeout: 2s
    tls: false
```

Токен передаётся без префикса `Bearer`; namespace определяется токеном.
Для клиентского кода стартер предоставляет четыре сервиса с кешированием ответов:

```kotlin
import org.springframework.stereotype.Service
import ru.a1pha1337.featurify.client.service.BooleanFeatureService
import ru.a1pha1337.featurify.client.service.EnumFeatureService
import ru.a1pha1337.featurify.client.service.PayloadFeatureService
import ru.a1pha1337.featurify.client.service.VectorFeatureService

@Service
class CheckoutFeatures(
    private val booleans: BooleanFeatureService,
    private val enums: EnumFeatureService,
    private val payloads: PayloadFeatureService,
    private val vectors: VectorFeatureService,
) {
    fun enabled(): Boolean = booleans.isEnabled("enabled", "checkout")
    fun provider(): String = enums.getValue("provider", "checkout")
    fun config(): String = payloads.getValue("checkout-config")
    fun dogEnabled(): Boolean = vectors.isEnabled("animals", "DOG")
    fun version(): Long = booleans.getFeature("enabled", "checkout").version
}
```

У каждого сервиса есть `getFeature(...)`, возвращающий полный protobuf-ответ с `version`,
и перегрузки без группы для Java и Kotlin. Они используют `featurify.grpc.default-group`
(`defaultGroup` в `FeaturifyGrpcProperties`): по умолчанию `null`, то есть Global.
Например, при `featurify.grpc.default-group: checkout` вызов `booleans.isEnabled("enabled")`
читает фичу из `checkout`. Это также относится к `enums.getValue(...)`,
`vectors.isEnabled(...)`, `payloads.getValue(...)` и `getFeature(...)` без аргумента группы.
Явно переданная группа имеет приоритет; явные `null` и `""` выбирают Global.
Кеш Caffeine хранит успешные ответы, включая
`false` и пустую строку, по типу фичи, ключу, группе и элементу vector. `null` и пустая
группа используют одну запись Global. Параллельные запросы одной записи объединяются
в одну загрузку. Ошибки RPC передаются без изменений и не кешируются.

TTL отсчитывается с получения ответа и не продлевается при чтении; после истечения
следующий вызов синхронно получает свежий ответ. Настройки через `FeaturifyGrpcProperties`:

```yaml
featurify:
  grpc:
    cache:
      ttl: 1s
      maximum-size: 10000
```

`ttl: 0s` отключает кеширование. Лимит размера применяется отдельно к каждому сервису.
Сервисы потокобезопасны, кеши локальны для экземпляра сервиса. Caffeine 2.9.3 сохраняет
поддержку Java 8; используются API, совместимые также с Caffeine 3.x в современных Boot.

Низкоуровневый клиент также доступен через constructor injection:

```kotlin
import org.springframework.stereotype.Service
import ru.a1pha1337.featurify.client.FeaturifyClient

@Service
class RawCheckoutFeatures(private val features: FeaturifyClient) {
    fun enabled(): Boolean = features.getBooleanFeature("enabled", "checkout").value

    fun provider(): String = features.getEnumFeature("provider", "checkout").value

    fun dogEnabled(): Boolean = features.getVectorFeature("animals", "DOG").value
}
```

Все ответы также содержат `version`. Необязательная `group` (`null` или пустая строка)
означает Global. В Java также доступны перегрузки без группы, например
`features.getBooleanFeature("enabled").getValue()`.

Клиент синхронный и потокобезопасный, использует одно соединение, не кэширует значения
и не включает автоматические повторы. Каждый вызов получает новый deadline.
Ошибки передаются как `StatusRuntimeException` со статусом и исходными trailers:
`StatusProto.fromThrowable(exception)` позволяет прочитать `ErrorInfo` и `BadRequest`.
Ошибки не подменяются значениями `false` или пустой строкой.

| Свойство                            | По умолчанию | Назначение                                                                   |
|-------------------------------------|--------------|------------------------------------------------------------------------------|
| `featurify.grpc.enabled`            | `true`       | `false` отключает автоконфигурацию                                           |
| `featurify.grpc.host`               | `localhost`  | Хост gRPC-сервера                                                            |
| `featurify.grpc.port`               | `9090`       | Порт gRPC-сервера                                                            |
| `featurify.grpc.token`              | обязательное | Namespace-токен без `Bearer`                                                 |
| `featurify.grpc.default-group`      | `null` (Global) | Группа для вызовов сервисов без аргумента группы                           |
| `featurify.grpc.timeout`            | `2s`         | Положительный deadline каждого RPC, максимум `1d`                            |
| `featurify.grpc.cache.ttl`          | `1s`         | TTL ответов в сервисах; `0s` отключает кеш, отрицательные значения запрещены |
| `featurify.grpc.cache.maximum-size` | `10000`      | Максимум записей в кеше каждого сервиса; положительное число                 |
| `featurify.grpc.tls`                | `true`       | TLS с проверкой сертификата и имени сервера                                  |
| `featurify.grpc.trust-certificate`  | системные CA | PEM-ресурс доверенного CA, например `file:/certs/ca.crt`                     |

Для TLS-сервера оставьте `tls: true`; при собственном CA задайте `trust-certificate`.
Указывать сертификат при `tls: false` запрещено. Отсутствующий токен и неверные настройки
прерывают запуск приложения. Соединение создаётся лениво: доступность сервера проверяется
при RPC. При остановке Spring контекста стартер закрывает принадлежащее ему соединение.
Собственный bean `FeaturifyClient` заменяет стандартный клиент: транспортные настройки
для него не проверяются, сервисные бины используют этот клиент и общие настройки кеша.
Каждый сервис можно заменить своим bean соответствующего типа. `featurify.grpc.enabled=false`
отключает создание и стандартного клиента, и сервисов.
Для раннего SB2 регистрация выполняется через `META-INF/spring.factories`, для SB3/SB4 —
через `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
Используются обычная `@Configuration` и JavaBean binding, доступные с SB2.0; зависимости
от `@AutoConfiguration`, Kotlin reflection, `javax.validation` или `jakarta.validation` нет.
SB2.7 поддерживает оба файла и убирает повторные записи — см.
[официальное руководство Spring](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide#auto-configuration-files).
Расширять component scan приложения не требуется.

Автоматическая проверка совместимости использует опубликованные Maven JAR в отдельных
приложениях с SB2.0.9, SB2.6.15, SB2.7.18, SB3.5.16 и SB4.1.1. SB2 запускается на Java 8,
SB3 — на Java 21, SB4 — на Java 25. Это проверенные комбинации; конкретные другие версии
приложения можно добавить в `compatibility-tests/build.gradle.kts`.

Стартер использует gRPC `1.83.1` и Protobuf `4.35.1`. Если приложение уже переопределяет
эти библиотеки своим BOM или dependency management, согласуйте версии с клиентом:
понижение Protobuf ниже версии генератора может приводить к ошибке при загрузке классов.

Для приложения без Spring подключайте только `featurify-grpc-client` и закрывайте его:

```java
try (GrpcFeaturifyClient client = GrpcFeaturifyClient.connect(
        "localhost", 9090, token, Duration.ofSeconds(2), false, null)) {
    boolean enabled = client.getBooleanFeature("enabled").getValue();
}
```

Сборка генерирует Java gRPC/Protobuf классы из `.proto`. Build-стадия Docker использует
JDK на Ubuntu для совместимости с бинарниками protoc; runtime остаётся на Alpine.

## Проверка

```shell
./gradlew test
./gradlew :featurify-service:bootJar
```

Тесты проверяют UI, административный REST, gRPC-вызовы, bcrypt и изоляцию namespace.
Тесты API, клиента и стартера входят в общий `test`; клиентские тесты проверяют metadata,
все типы RPC, ошибки, deadline, автоконфигурацию, plaintext/TLS и закрытие соединения
без PostgreSQL. `./gradlew build` собирает и проверяет все пять модулей.
Проверка совместимости с разными Spring Boot запускается отдельно:

```shell
./gradlew verifyClientCompatibility
```

Требуются установленные JDK 8, 21 и 25, обнаруживаемые Gradle toolchains.
Задача публикует клиентские JAR в `build/compatibility-repository` и проверяет их
как внешние зависимости без доступа к серверному classpath. Проверяются оба механизма
регистрации, отсутствие лишних зависимостей, binding, отключение/переопределение клиента,
валидация конфигурации, все четыре RPC с токеном, TLS, deadline и закрытие соединения.
Для интеграционной проверки с PostgreSQL включите `FEATURIFY_DB_TESTS=true` и запустите
`./gradlew test`. Используйте отдельную тестовую БД через `DB_URL` / `DB_USERNAME` /
`DB_PASSWORD`: Flyway применяет V1 автоматически, а тестовые данные откатываются.
Этот режим также проверяет цепочку Spring Security, хранение токенов, отзыв и каскады БД.
Дополнительно запускается настоящий сервер Spring gRPC на случайном порту: проверяются
автоматическая регистрация сервиса и авторизации, изоляция namespace, отзыв токенов
и ограничение размера сообщения. Те же RPC-проверки выполняются через TLS с проверкой
сертификата и профилем `grpc-tls`. Тестовые namespace удаляются после этих RPC-тестов.

## Хранение значений фич

`feature` содержит только общие данные: namespace, группу, ключ, тип, описание,
версию и даты. Значения хранятся отдельно:

| Таблица                  | Назначение                                      |
|--------------------------|-------------------------------------------------|
| `feature_boolean_value`  | Единственное boolean-значение фичи              |
| `feature_enum_value`     | Единственный выбранный enum-вариант             |
| `feature_enum_option`    | Упорядоченные допустимые enum-варианты          |
| `feature_vector_element` | Имена и независимые состояния элементов вектора |

Составные внешние ключи с автоматически вычисляемым типом запрещают привязывать
значения к фичам другого типа. Выбранный enum-вариант ссылается на допустимый вариант.
Отложенные ограничения проверяют наличие обязательного значения в конце транзакции:
это позволяет атомарно сохранять общую запись и дочерние строки. Удаление фичи каскадно
удаляет её значения, варианты и аудит.

В Kotlin `Feature.value` имеет тип `FeatureValue`: `BooleanValue`, `EnumValue`
(включая варианты) или `VectorValue`. Тип фичи вычисляется из значения.
`FeatureRepository` преобразует доменную модель в JDBC-модель `FeatureRecord`;
все дочерние значения сохраняются одним агрегатом. Общая `feature.version` защищает
весь агрегат: конфликт версии откатывает и изменения дочерних строк.
Контракты REST/gRPC и представление значений в UI сохранены.

Изменения схемы внесены в V1; она по-прежнему предназначена для новой базы.

## Vector toggle

`VECTOR` хранит набор именованных элементов с независимыми boolean-состояниями.
Имена элементов чувствительны к регистру, содержат 1–255 символов без пробелов по краям.
Набор содержит от 1 до 100 элементов и задаётся при создании, как список вариантов у `ENUM`.

Создание через административный API: `POST /api/v1/features?namespace=blue`:

```json
{
  "key": "feat",
  "type": "VECTOR",
  "vectorValues": {"CAT": true, "DOG": true, "SHIP": false}
}
```

Чтение статуса элемента с namespace-токеном:
`GET /api/v1/features/feat/elements/DOG` (для группы добавьте `?group=animals`).
Ответ содержит `key`, `group`, `element`, boolean `value` и `version`.
Namespace определяется токеном, как при чтении обычной фичи.
Обычные GET/list/resolve возвращают в `value` всю карту состояний.

Чтобы выключить только `DOG`, отправьте
`PATCH /api/v1/features/feat?namespace=blue` с актуальной версией:

```json
{"version": 0, "vectorValues": {"DOG": false}}
```

`CAT` и `SHIP` сохраняют свои состояния. PATCH меняет только переданные элементы;
неизвестные имена отклоняются, добавление и удаление элементов через PATCH не поддерживается.
Изменения защищены optimistic locking на уровне всей фичи и записываются в аудит.

В gRPC вызовите `FeatureService/GetVectorFeature`:

```json
{"group": "", "key": "feat", "element": "DOG"}
```

Ответ — `BooleanFeatureResponse` с `value` и `version`.
Выключенный элемент возвращает `false`, отсутствующий — `NOT_FOUND` (REST: 404).
Запрос VECTOR-элемента у другого типа возвращает `FAILED_PRECONDITION` (REST: 409).
Пустое имя элемента в gRPC возвращает `INVALID_ARGUMENT`.

Схема элементов и расширение полей аудита включены в миграцию V1.
Для базы с уже применённой прежней V1 действуют описанные выше требования пересоздания.

## Payload toggles (JSON)

`PAYLOAD` хранит JSON-конфигурацию: объект, массив, строку, число, boolean или JSON `null`.
Смысл соответствует [JSON-фичам FeatureHub](https://docs.featurehub.io/featurehub/latest/features.html).
При создании обязательно поле `payloadValue` — **строка с JSON**, максимум 65536 символов
до и после нормализации. Пустые документы, повторяющиеся ключи, комментарии и несколько
документов подряд отклоняются. Числа сохраняются без округления через `double`.

Создание через `POST /api/v1/features?namespace=blue`:

```json
{
  "key": "checkout-config",
  "type": "PAYLOAD",
  "payloadValue": "{\"timeoutMs\":1500,\"theme\":{\"accent\":\"blue\"},\"methods\":[\"card\",\"wire\"]}"
}
```

REST чтение (включая list и resolve) возвращает **разобранный JSON** в `value`:

```json
{
  "group": null,
  "key": "checkout-config",
  "type": "PAYLOAD",
  "value": {"timeoutMs":1500,"theme":{"accent":"blue"},"methods":["card","wire"]},
  "enumOptions": null,
  "version": 0
}
```

PATCH заменяет документ целиком; объединения объектов нет:

```json
{"version":0,"payloadValue":"{\"timeoutMs\":3000}"}
```

Пропущенное поле / `payloadValue: null` не изменяет значение при PATCH;
`payloadValue: "null"` устанавливает JSON `null`. Отдельного состояния «не задано» нет.
Поля `booleanValue`, `enumValue`, `enumOptions` и `vectorValues` не задаются для PAYLOAD.
Тип существующей фичи неизменяем. Версия, транзакции, аудит, namespace и права управления
сохраняют общие правила. Пробелы и порядок ключей объектов нормализуются, поэтому
их изменение не создаёт записи об изменении значения в аудите.

gRPC `GetPayloadFeature(GetFeatureRequest)` возвращает `PayloadFeatureResponse`:
`value` — строка с JSON, `version` — версия всей фичи. Несовпадение типа возвращает
`FAILED_PRECONDITION`; авторизация и ошибки соответствуют остальным RPC.

```java
PayloadFeatureResponse response = client.getPayloadFeature("checkout-config", "checkout");
String json = response.getValue();
```

Starter автоматически создаёт `ru.a1pha1337.featurify.client.service.PayloadFeatureService`
с методами `getValue(key[, group])` и `getFeature(key[, group])`. Кеш, TTL, default group,
обход ошибочных загрузок и возможность переопределения bean работают по общему контракту.
JSON разбирается выбранной библиотекой приложения; новые зависимости для этого в клиент не добавлены.
Demo: `/diagnostics/features/payload/checkout-config?cached=false`.

Оператор поддерживает PAYLOAD в Global и в группах, включая `Managed` / `InitialOnly`;
пример YAML и порядок обновления CRD — в [README оператора](featurify-k8s-operator/README.md).
Схема `feature_payload_value` включена в существующую MVP-миграцию V1 для новой базы.
Для уже применённой V1 требуется описанное выше пересоздание базы; обновление существующих
данных этой миграцией не выполняется.
