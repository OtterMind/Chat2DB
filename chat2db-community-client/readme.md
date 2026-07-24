# Chat2DB Community Frontend

The Community frontend is built with Umi 4, React, TypeScript, Ant Design 5,
and Zustand. Community behavior is selected with `UMI_ENV=community`.

## Requirements

- Node.js 18.17.0 or newer
- Yarn using the repository's `yarn.lock`
- The Community backend when running the development server

Install dependencies from this directory:

```bash
yarn install --frozen-lockfile
```

Do not generate npm or pnpm lockfiles. The repository maintains only the Yarn
lockfile.

## Development

Start the Community backend on `127.0.0.1:10825`, then run:

```bash
yarn run start:community:hot
```

The Community development server listens on port `8889`.

## Production Build

Build the Community renderer with an explicit public version:

```bash
yarn run build:web:community --app_version=5.3.0
```

The generated renderer is written to `dist/`. For a web or Docker package, the
files are staged under the Spring Boot module at:

```text
../chat2db-community-server/chat2db-community-start/src/main/resources/static/front/
../chat2db-community-server/chat2db-community-start/src/main/resources/thymeleaf/index.html
```

From the repository root, use `./docker/docker-build.sh` to perform the complete
frontend, backend, and image build without manually staging these files.

## Desktop Packaging

The JCEF desktop packaging entry point is repository-local:

```bash
script/package/package-community-jcef.sh 5.3.0 prepare
```

Run it from the repository root. Replace `prepare` with `mac`, `linux`, or `win`
on the matching operating system to build a native installer. Generated inputs
and installers are written under `jpackage/` and are not frontend source files.

## Checks

```bash
yarn run lint
yarn run test:i18n
yarn run test:result-markdown
yarn run test:sql-in-clipboard
```

## Source Conventions

- Prefix TypeScript interfaces and type aliases with `I`.
- Use values from `window._AppThemePack` in JavaScript and CSS variables such
  as `var(--control-item-bg-active)` in styles instead of hard-coded theme
  colors.
- Use keys from `src/i18n/` through `i18n` or `i18nElement`. Placeholders use
  `{1}`, `{2}`, and so on. Spanish and Korean catalogs must keep exact module,
  key, placeholder, and HTML-tag parity with `en-US`; update their source hashes
  whenever the corresponding English wording changes.
