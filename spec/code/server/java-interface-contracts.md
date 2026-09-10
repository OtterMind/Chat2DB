# Java Server Interface Contracts

## 1. Purpose

Server modules collaborate only through interfaces and domain models. A caller must not bypass an interface and depend directly on an implementation class. Interface contracts must remain stable, replaceable, and reviewable, without leaking web, persistence, or plugin implementation details into upper layers.

## 2. Interface Naming

1. Name every Java interface with an `I` prefix.
2. Name business service interfaces `IXxxService`.
3. Name storage capabilities `IXxxStorage`, `IXxxRepository`, or a more specific capability name.
4. Name plugin extension interfaces with capability suffixes such as `IXxxManager`, `IXxxDialect`, `IXxxPlugin`, or `IXxxProcessor`.
5. Internal callback, listener, and strategy interfaces also use the `I` prefix, for example `IProgressListener` or `IExportStrategy`.

Example:

```java
public interface IDataSourceService {
}

public class DataSourceServiceImpl implements IDataSourceService {
}
```

Business service interfaces must include a business-domain prefix:

```text
I<Domain><Object>Service
I<Domain><Object><Capability>Service
```

Allowed top-level domain prefixes:

| Prefix | Meaning |
| --- | --- |
| `Sys` | System settings, accounts, permissions, OAuth, proxies, and runtime configuration |
| `Db` | Database connections, metadata, SQL, DDL/DML, tables, views, functions, procedures, triggers, and Redis data operations |
| `Ai` | AI chats, models, completion, RAG, embeddings, and AI-assisted schema capabilities |
| `Cli` | CLI and headless capabilities |
| `Mcp` | MCP protocols, tools, resources, and authorization |
| `Task` | Import/export, asynchronous tasks, and long-running workflows |
| `Ops` | Operation history, auditing, saved queries, and history records |
| `Plugin` | Plugin extension capabilities |

`Rdb`, `Redis`, `Database`, `DataSource`, `Table`, `View`, `Function`, `Procedure`, and `Trigger` are not top-level domains. These objects belong to the `Db` domain.

## 3. Interface Ownership

| Contract Type | Owning Module | Notes |
| --- | --- | --- |
| Application business capability | `chat2db-community-domain-api` | Business contracts for datasources, SQL, tasks, workspaces, AI configuration, and related capabilities |
| Business capability implementation | `chat2db-community-domain-core` | Implements interfaces from domain-api |
| Storage contract | `chat2db-community-domain-api` | Defines storage capabilities and domain models only |
| Storage implementation | `chat2db-community-storage` | Implements storage interfaces from domain-api |
| Database plugin extension | `chat2db-community-spi` | Extension points for drivers, metadata, DDL, Redis operations, and related capabilities |
| Database plugin implementation | `chat2db-community-plugins/*` | Implements SPI interfaces |
| HTTP entry point | `chat2db-community-web` | Controllers, request/response DTOs, converters, adapters, and web facades |
| Cross-cutting support | `chat2db-community-tools` | Shared utilities, exceptions, and runtime helpers; not business contracts |
| Agent runtime implementation | `chat2db-community-agent` | Implements shared runtime contracts from tools; no JCEF, business, or storage dependencies |

`domain-api` defines business contracts, contract models, and contract enums only. Do not add support packages such as `exception` or `util`, and do not hide `*Exception` types under business packages such as `model`. Shared exceptions and utilities belong in `chat2db-community-tools`, for example `ai.chat2db.community.tools.exception` and `ai.chat2db.community.tools.util`.

## 4. No Direct Implementation Dependencies

1. Callers inject interfaces, not `XxxImpl` classes.
2. `web` depends only on `domain-api` interfaces, not `domain-core` implementations.
3. A module must not import another module's `impl` package or `XxxImpl` class.
4. Do not bypass interfaces through `ApplicationContext.getBean(Impl.class)`.
5. Do not use reflection, class-name strings, or bean names to locate implementation classes directly.
6. Callers must not instantiate business implementations with `new`.

Allowed exceptions:

1. The startup assembly module may assemble implementation modules but must not contain business call logic.
2. One implementation module may contain private helpers, converters, and strategies, but these types must not become cross-module contracts.

## 5. Service Rules

1. A class with business service responsibilities must have an interface first.
2. Service interfaces belong in `domain-api`.
3. Service implementations belong in `domain-core`.
4. Name implementations `XxxServiceImpl` and explicitly declare `implements IXxxService`.
5. The `web` module must not add business services. It may contain HTTP adapters, DTO converters, and web facades only.
6. Renaming a package or class does not change its responsibility. Business orchestration belongs in `domain-core`.

### 5.1 Web Controller Rules

1. Controller names must include one of the top-level business-domain prefixes: `Sys`, `Db`, `Ai`, `Cli`, `Mcp`, `Task`, `Ops`, or `Plugin`.
2. A controller filename must match its `public class` name exactly.
3. Database-related controllers belong to the `Db` domain, for example `DbTableController`, `DbDmlController`, or `DbRedisKeyController`.
4. `Rdb`, `Redis`, `Database`, `DataSource`, `Table`, `View`, `Function`, `Procedure`, and `Trigger` must not be used as top-level controller-domain prefixes.

## 6. Interface Parameters and Return Values

1. An interface may expose clear business parameters directly. Do not create a field-only `XxxRequest` shell solely to reduce parameter count.
2. Use a request object when it has compound semantics, validation semantics, or cross-layer reuse value.
3. Name interface input objects `XxxRequest`, not `XxxParam`, `XxxCommand`, `XxxQuery`, `XxxDTO`, or `XxxVO`.
4. Name interface output objects `XxxResponse`, not `XxxResult`, `XxxDTO`, or `XxxVO`.
5. Name an `XxxRequest` parameter with the matching lowerCamel form, such as `TableQueryRequest tableQueryRequest` or `CreateDataSourceRequest createDataSourceRequest`. Avoid generic names such as `param`, `queryParam`, or `request`.
6. Request objects must express complete business semantics in the form `<Domain><Object><Action>Request`.
7. Action response objects use `<Domain><Object><Action>Response`. Resource views or domain output models may use `<Domain><Object>Response`, but still require a business-domain prefix.
8. Name CRUD contracts in domain, object, action order:
   - `DbDatasourceCreateRequest` / `DbDatasourceCreateResponse`
   - `DbDatasourceUpdateRequest` / `DbDatasourceUpdateResponse`
   - `DbDatasourceDeleteRequest` / `DbDatasourceDeleteResponse`
   - `DbDatasourceGetRequest` / `DbDatasourceGetResponse`
   - `DbDatasourceListRequest` / `DbDatasourceListResponse`
9. Non-CRUD contracts use their real business action, for example `DbSqlExecuteRequest`, `DbConnectionTestRequest`, `AiChatSendRequest`, or `TaskImportStartRequest`.
10. Request, response, service, and service implementation names for one method must reveal the same `<Domain><Object><Action>` semantic anchor.
11. Simple values may return `void`, JDK primitives and wrappers, `String`, `Long`, collections, or page models directly.
12. Interfaces must not return generic result wrappers such as `ActionResult`, `Result<T>`, `DataResult<T>`, `ListResult<T>`, `PageResult<T>`, `WebPageResult<T>`, or HTTP wrappers.
13. Structured business output requires a specific `XxxResponse`, not a generic success/message/data wrapper.
14. Domain interfaces must not return web requests, web responses, VOs, or HTTP result wrappers.
15. Domain interfaces must not expose Servlet or Spring MVC types, MyBatis mappers or entities, gateway DTOs, or local-file storage implementations.
16. Interface method parameters, return values, and contract-object fields must not expose Java `enum` types.
17. Enums remain implementation details. Contracts expose their actual values, for example `String type`, `String status`, or `Integer code`.
18. An enum exposes its outward value through methods such as `getCode()`, `code()`, or `name()`.
19. Parsing a value into an enum belongs in static enum methods such as `from(String value)` or `from(Integer value)`. Do not scatter `valueOf`, `switch`, or `try-catch` parsing across services, builders, adapters, or controllers.
20. The web layer converts HTTP DTOs and domain contract objects and may call only the enum's own conversion methods.

Example:

```java
public interface IDbDatasourceService {

    DbDatasourceCreateResponse create(DbDatasourceCreateRequest dbDatasourceCreateRequest);

    void delete(DbDatasourceDeleteRequest dbDatasourceDeleteRequest);

    DbDatasourceGetResponse get(DbDatasourceGetRequest dbDatasourceGetRequest);
}
```

## 7. Interface Parameter Validation

1. Request objects use Bean Validation annotations for basic validation.
2. Prefer `@NotNull`, `@NotBlank`, `@NotEmpty`, `@Size`, `@Min`, `@Max`, `@Pattern`, and `@Valid`.
3. Use `@Valid` when nested objects or collection elements require continued validation.
4. Controllers, facades, or other call entry points trigger validation.
5. Implementations must not duplicate basic null, length, or format checks already expressed by validation annotations.
6. Permissions, state transitions, object existence, and business conflicts remain domain-core business rules.

## 8. SPI and Domain API Boundaries

1. `domain-api` defines application business capabilities.
2. `spi` defines database plugin extension capabilities.
3. Business services do not belong in `spi`.
4. Plugin extension points do not belong in `domain-api`.
5. `domain-core` may use plugin capabilities through `spi`, but must not depend on concrete plugin implementations.

## 9. Module Package Structure

1. In each Maven module, `enums`, `constant`, `model`, and `config` are module-level classification packages.
2. These packages may contain business subpackages, for example `enums/completion`, `model/completion/context`, or `config/completion`.
3. Do not place a classification package below a business package, such as `completion/enums`, `completion/model`, `completion/config`, or `impl/rdb/doc/constant`.
4. Use the singular package name `constant`; do not add `constants` packages.
5. A classification package appears only once and must be the first segment after the module root package. Later business subpackages must not reuse `enums`, `constant`, `model`, or `config` as names.
6. Existing `request`, `response`, `dto`, `service`, `impl`, `converter`, and `adapter` packages are outside this classification rule.
7. Source packages and directories must not use `rdb`; database-related source packages use `db`. Compatibility URLs such as `/api/rdb/...` are outside the scope of this package rule.

Examples:

```text
ai.chat2db.plugin.mysql.enums.completion
ai.chat2db.plugin.mysql.model.completion.context
ai.chat2db.plugin.mysql.config.completion
ai.chat2db.community.domain.core.constant.db.doc
```

## 10. Review Checklist

Interface-contract reviews must verify:

1. Every interface under `src/main/java` uses the `I` prefix.
2. Domain-api service interfaces use both the `I` prefix and an allowed top-level business-domain prefix.
3. Every `*ServiceImpl` in domain-core explicitly implements an interface.
4. The web module does not add a business `service` package or business `*Service.java` type.
5. Web controllers use business-domain prefixes, and filenames match public class names.
6. Source packages, directories, and Java identifiers do not retain obsolete `rdb` or `Rdb` naming.
7. Modules do not import another module's `impl` package or `*Impl` class.
8. Code does not retrieve implementations directly through calls such as `ApplicationContext.getBean(XxxImpl.class)`.
9. Domain-api and SPI signatures use contract objects named `XxxRequest` and `XxxResponse`.
10. Domain-api and SPI interfaces do not return generic result wrappers.
11. `XxxRequest` parameters in domain-api and SPI use the matching lowerCamel parameter name.
12. Request objects declare at least one applicable basic validation annotation.
13. Domain-api and SPI signatures and contract fields do not expose Java enum types.
14. Value-to-enum parsing is not scattered outside enum types.
15. `enums`, `constant`, `model`, and `config` follow module-level classification-package rules.
16. Contract types under domain-api request and response packages use an allowed top-level business-domain prefix.
17. Domain-api does not retain `exception` or `util` support packages, `*Exception` types, or obsolete package references.

Third-party packages and Spring bean initialization order are outside this checklist.
