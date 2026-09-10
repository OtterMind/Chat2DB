# Java Server Module Dependency Boundaries

## 1. Purpose

Server modules must follow one-way dependency rules based on their responsibilities. Callers depend on contracts rather than implementations, and the startup module assembles the implementations.

These boundaries apply only to Chat2DB modules. They do not restrict third-party dependencies such as Spring, ANTLR, MyBatis, or JDBC drivers.

## 2. Module Responsibilities

| Module | Responsibility | Must Not Do |
| --- | --- | --- |
| `chat2db-community-tools` | Shared utilities, exceptions, result wrappers, and basic context helpers | Contain HTTP code, business workflows, storage implementations, or plugin implementations |
| `chat2db-community-domain-api` | Define business contracts, including service interfaces, requests, models, enums, and storage contracts | Depend on domain-core, web, storage, SPI, or plugin modules |
| `chat2db-community-domain-core` | Implement domain-api business logic and orchestrate database plugin capabilities | Depend on web, storage implementations, or concrete plugin implementations |
| `chat2db-community-storage` | Implement persistence contracts defined in domain-api | Depend on web, domain-core, SPI, or plugins |
| `chat2db-community-spi` | Define database plugin extension contracts and shared plugin-side models | Depend on web, domain-core, storage, or concrete plugin implementations |
| `chat2db-community-plugins/*` | Implement database-specific plugin capabilities | Depend on web, domain-core, or storage |
| `chat2db-community-web` | Provide HTTP, MCP, and CLI adapters, request/response DTOs, controllers, and web converters | Depend on domain-core, storage, SPI, or plugins |
| `chat2db-community-jcef` | Provide desktop-shell adapters | Contain domain business logic or depend on web, domain-core, storage, SPI, or plugins |
| `chat2db-community-agent` | Implement agent runtime adapters, process/RPC transport, installation, and runtime resources | Depend on JCEF, web, domain, storage, SPI, or plugin modules; contain product business workflows |
| `chat2db-community-start` | Provide the startup entry point and runtime assembly | Contain business logic |

## 3. Allowed Project Dependencies

| Current Module | Allowed Chat2DB Dependencies |
| --- | --- |
| `chat2db-community-tools` | None |
| `chat2db-community-domain-api` | `chat2db-community-tools` |
| `chat2db-community-domain-core` | `chat2db-community-domain-api`, `chat2db-community-tools`, `chat2db-community-spi` |
| `chat2db-community-storage` | `chat2db-community-domain-api`, `chat2db-community-tools` |
| `chat2db-community-spi` | `chat2db-community-domain-api`, `chat2db-community-tools` |
| `chat2db-community-plugins/*` | `chat2db-community-spi`, other `chat2db-community-plugins/*` modules |
| `chat2db-community-web` | `chat2db-community-domain-api`, `chat2db-community-tools` |
| `chat2db-community-jcef` | `chat2db-community-tools` |
| `chat2db-community-agent` | `chat2db-community-tools` |
| `chat2db-community-start` | `chat2db-community-web`, `chat2db-community-jcef`, `chat2db-community-agent`, `chat2db-community-domain-core`, `chat2db-community-storage`, `chat2db-community-plugins/*` |

## 4. Prohibited Dependencies

1. `web` must not import or depend on `domain-core`, `storage`, `spi`, or plugin modules.
2. `domain-api` must not import or depend on implementation-layer modules.
3. `domain-core` must not import or depend on concrete plugin modules. Business logic must use plugin capabilities through `spi`.
4. `storage` must not import or depend on `web`, `domain-core`, `spi`, or plugins.
5. `spi` must not import or depend on `web`, `domain-core`, `storage`, or concrete plugin implementations.
6. `tools` must not import or depend on business, web, storage, SPI, or plugin modules.
7. `plugins/*` must not import or depend on `web`, `domain-core`, or `storage`.
8. Code must not bypass module boundaries through `ApplicationContext`, reflection, class-name strings, or bean names.
9. Domain service interfaces must not return web DTOs or HTTP result wrappers.
10. Agent runtime implementations belong in `agent`, not `jcef` or `tools`. `agent` and `jcef` must not depend on each other; `start` assembles them through the shared contracts in `tools`.

## 5. Review Checklist

Module-boundary reviews must cover:

1. Project dependencies with `groupId=ai.chat2db` in Maven POM files.
2. Project-level `ai.chat2db...` imports in Java source files.
3. Runtime lookups or reflection that bypass compile-time dependency boundaries.
4. Contract types that leak web, storage, or implementation-layer models across module boundaries.

Third-party package dependencies are outside the scope of this checklist.
