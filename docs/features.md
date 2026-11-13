# Current behavior

The repository currently contains a starter web interface and a small HTTP server. It does not yet implement supply management workflows.

## Web interface

The web entry point mounts the Compose `App` in a browser viewport. The page shows a **Click me!** button on a Material-themed background. Clicking the button toggles visibility of a Compose image and a greeting. The greeting is generated once for the visible content and includes a platform name.

Platform naming differs by web target:

- **JavaScript:** searches the browser user-agent string for Chrome, Firefox, Safari, or Edge; falls back to `Unknown`.
- **Kotlin/Wasm:** uses `Web with Kotlin/Wasm`.

The greeting uses the shared `sayHello` function, so its text has the form `Compose: Hello, <platform>!`.

Relevant source: [`App.kt`](../app/shared/src/commonMain/kotlin/org/example/project/App.kt), [`Greeting.kt`](../app/shared/src/commonMain/kotlin/org/example/project/Greeting.kt), and [`main.kt`](../app/webApp/src/webMain/kotlin/org/example/project/main.kt).

## HTTP server

The Ktor Netty server listens on `0.0.0.0:8080`. Its only defined route is `GET /`, which returns the plain text `Hello, Ktor!` using the same shared greeting function. The server source does not define other routes or application data storage.

Relevant source: [`Application.kt`](../server/src/main/kotlin/org/example/project/Application.kt) and [`GreetingUtil.kt`](../core/src/commonMain/kotlin/org/example/project/GreetingUtil.kt).
