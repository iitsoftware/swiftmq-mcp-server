# SwiftMQ MCP Server — Plan

A **local, stdio-based**, read-only MCP server (Java/Maven). A user starts it on their own machine,
for example from Claude Desktop or Claude Code. It connects with the user's own SwiftMQ credentials
via the CLI Admin API to a (usually remote) SwiftMQ router, and from there it reaches every router
in that router network. Focus areas: Queue Manager and Topic Manager, plus Routing, Network and `.env`
(memory).

## Verified facts (basis for the design)

- **CLI API** (`com.swiftmq:swiftmq-client` **13.2.4, javax.jms**, *not* the jakarta artifact;
  dependencies: `javax.jms:jms-api:1.1-rev-1`, dom4j, brownies-collections):
  `com.swiftmq.admin.cli.CLI(javax.jms.QueueConnection)`, `getAvailableRouters()`,
  `waitForRouter(r[, ms])`, `executeCommand(String)`, `getActRouter()`, `getActContext()`,
  `getContextEntities()`, `getContextProperty(name)`, `addRouterListener(RouterListener)`
  (`onRouterEvent(String router, boolean available)`), `close()`.
- The CLI is **stateful** (current router + current context). Reads are always
  `sr <router>` → `cc <path>` → `getContextEntities()` / `getContextProperty()`.
  Calls to one instance must be serialized.
- `;` separates commands in the CLI. Every name that goes into a command string is an
  **injection vector** and must be validated.
- Connection: JNDI `com.swiftmq.jndi.InitialContextFactoryImpl`, URL `smqp://user:pw@host:4001`.
  **Once connected, the CLI has access to all connected routers.** No per-router password or `authenticate`
  is needed (confirmed by the user).
- Router-side read-only enforcement exists: `sys$mgmt` admin roles (role name == user name),
  context filters with `read-only="true"` and `granted-commands`. Configured per router, off by default.
- The queue `view` command (`/sys$queuemanager/usage`) exists, but **is not used**, by decision:
  no message content is read, and no commands besides navigation are executed.
- **Property metadata** lives in `swiftmq/<edition>/kernel/<swiftlet>/config.xml` (13.2.4).
  **Edition layering: HA builds on UR, and UR builds on CE.** Each edition adds swiftlets and can
  override a lower edition's `config.xml` for the same swiftlet. The effective set is
  `CE ∪ UR ∪ HA`, and on overlap the higher edition wins (HA > UR > CE). The `usage` section
  describes live data:
  - `sys$queuemanager/usage/<queue>`: `messagecount`, `msg-consume-rate`, `msg-produce-rate`,
    `total-consumed`, `total-produced`, `latency`, `flowcontrol-delay`, `mcache-messages`,
    `mcache-size-kb`, `active`, `amax-messages`, `acache-size(-kb)`, `acleanup-interval`,
    `aflowcontrol-start-queuesize`.
    Sublists: `receiver` (username, clientid, selector), `sender` (username, clientid),
    `browser` (username, clientid, selector).
  - `sys$topicmanager/usage`:
    - `durables` (durablename, clientid, topic, selector, nolocal, boundto)
    - `subscriber` (clientid, topic, selector, nolocal, boundto)
    - `subscriber-remote/<router>/topics`
  - `sys$routing` (HA/UR ship `URoutingSwiftletImpl`, "Unlimited Connections"; same tree as CE):
    - config: `route-announce-hop-limit` (3), `roundrobin-enabled`, `inbound-flow-control-enabled`
    - `listeners` (bindaddress, port, bindaddress2/port2, socketfactory-class, keepalive-interval,
      use-compression, use-xa, inbound-window/transaction-size, buffer sizes, **password**, `host-access-list`)
    - `connectors` (enabled, hostname, port, retry-time, socketfactory-class, use-compression, use-xa,
      **password**, …)
    - `static-routes` (entity name = destination router)
    - `filters` (type, `routers`)
    - usage: `connections` (routername, connecttime, protocol-version, uses-xa),
      `routing-table/<destination>/dynamic-routes/<route>`
  - `sys$net` (netty impl):
    - config: collect-interval (1000), zombi-connection-timeout, dns-resolve-enabled, max-chunk-size
    - usage: one entity per connection (swiftlet, connect-time, throughput-input,
      throughput-output). Throughput is only collected while an admin tool is connected, which
      the MCP server always is.
  - `.env` (built by the kernel, not in config.xml; verified in `swiftmq.jar` `RouterMemoryMeter`):
    `router-memory-list/<entry>` with `free-memory` and `total-memory` (Integer, KB). The list's
    only command is `gc`, which is **never** issued. `.env` itself has the
    properties (verified on CE): `routername`, `release` (e.g. `13.02.04 Community Edition`), `os`,
    `startuptime`, `memory-collect-interval` (10000) and `use-smart-tree`.
  - **Smart management tree** (`use-smart-tree`, default `true`): an attribute on `<router>` in
    `routerconfig.xml`, readable as `/.env` property `use-smart-tree`. When it is `true`, the tree
    sent to admin tools has reduced usage parts. Dynamic sublists are missing: `usage/<queue>/
    receiver|sender|browser`, and `sys$jms/usage/<conn>/sessions|tempqueues`.
  - **`sys$authentication`, `sys$mgmt` and `sys$timer` are off-limits** (see Guards).
  - Swiftlets per layer (13.2.4 `kernel/`):
    - **CE (base):** `sys$amqp`, `sys$authentication`, `sys$deploy`, `sys$jms`, `sys$jndi`,
      `sys$log`, `sys$mgmt`, `sys$mqtt`, `sys$net`, `sys$queuemanager`, `sys$routing`,
      `sys$scheduler`, `sys$store`, `sys$streams`, `sys$threadpool`, `sys$timer`,
      `sys$topicmanager`, `sys$trace`, `sys$xa`
    - **UR (on CE):** adds `sys$filecache` and `sys$jac`; overrides `sys$jms`
    - **HA (on UR):** adds `sys$hacontroller`; overrides `sys$filecache`, `sys$jms`,
      `sys$queuemanager`, `sys$routing`, `sys$store`
  - Different routers in one network can run different editions. Tools don't assume a swiftlet
    exists. They discover it at runtime (`list_swiftlets`) and report "not available on this
    router" instead of failing.
  - **Edition detection per router** (rule from the user), under the connection lock after `sr <router>`:
    1. `cc /sys$hacontroller` succeeds → **HA**
    2. else `cc /sys$filecache` succeeds → **UR**
    3. else → **CE**

    The result is cached per router. The cache entry is dropped when `RouterListener` reports the
    router unavailable, so it is re-detected after a reconnect or upgrade.
  - The HA queue manager differs from CE only in implementation class and defaults
    (`duplicate-detection-backlog-size` 100 vs 2000). Same usage schema.
- **Property values**: `getContextProperty(name)` (after `cc` into the entity), and
  `getContextEntities()` for lists. These are the **only** read paths: lists always come from `getContextEntities()`, and `lc` is never used.
- **CLI shell vs. CLI Admin API** (per the user): `output`, the alias commands (`aset`/`adel`/
  `alist`/`asave`) and the other shell conveniences (`echo`, `var`, `substitute`, `verbose`,
  `execute`) belong to the interactive CLI shell. They are **not** part of the CLI Admin API, and
  the server never uses them.
- **Command results**: `executeCommand()` returns `void`. Reply lines go through the private
  `out()` method, which writes to `System.out` (verified in the bytecode). This doesn't matter here:
  the only commands issued are `sr` and `cc`, and all data is read through the
  getters.

- **The CLI writes to `System.out`** (command replies, `Error:` lines, `waitForRouter`/`markRouter` messages in some paths). With stdio, stdout *is* the
  JSON-RPC channel, so any stray line corrupts the protocol. See "stdout protection".

## Decisions (confirmed 2026-10-24)

| Topic | Decision |
|---|---|
| **Edition scope** | **v1 supports CE only.** UR/HA support (edition detection, layered catalogs) comes later; see "Later: UR/HA support". The UR/HA facts below are kept for that |
| Transport | **stdio**, local process per user. Remote mode is a possible v2 (see end) |
| Authentication | The user's own SwiftMQ username/password. No MCP-level auth |
| Authorization | SwiftMQ itself: admin roles (role name == user name) on the routers |
| Connections | One SwiftMQ connection per server process. Several routers/networks = several MCP server entries in the client config |
| Message content | **Not accessible.** `view` is not executed; only queue/topic metadata and usage are read |
| Context access | Blacklist: everything except `sys$authentication`, `sys$mgmt`, `sys$timer` |
| Min. SwiftMQ version | 13.x (mgmt protocol 750) |

## Security model

- **The router is the security boundary.** The user could use the plain CLI with the same
  credentials, so real enforcement belongs on the routers: admin roles with `read-only="true"`
  and `exclude` filters for the denied swiftlets. The README ships a ready-made role snippet.
- **The server-side guards protect against the AI**, not against the user. They make sure the model
  can never change anything, never reach denied contexts, and never see secrets, even when
  the user's SwiftMQ account has full admin rights:
  - The server only ever issues the navigation commands `sr` and `cc` (the
    `CommandBuilder` can't produce anything else), plus the API getters `getContextEntities` and
    `getContextProperty`. No raw-command tool exists. `view`, `gc` and all other commands are
    never issued.
  - The context blacklist and password redaction (see Guards).
- **Credentials never pass through the LLM.** They come from the launch configuration (args, env,
  config file), never from tool parameters. They are never logged and never returned by any tool:
  `connection_info` shows the URL without a password.

## Launch & configuration

The client config starts `java -jar swiftmq-mcp-server.jar`. Settings are resolved in this order:
CLI args → env vars → optional config file (`--config <file>`, JSON).

| Setting | Arg | Env | Notes |
|---|---|---|---|
| Router URL | `--url` | `SWIFTMQ_URL` | `smqp://host:4001[/timeout=..;reconnect=..]`, plus JNDI failover URLs |
| User | `--user` | `SWIFTMQ_USER` | |
| Password | — | `SWIFTMQ_PASSWORD` | Or `--password-file`. **Not** as an arg, because args are visible in `ps` |
| Limits | config file | | maxListItems (500), requestTimeoutMs (15000) |
| Extra denied contexts | config file | | Can only *add* to the fixed denylist |
| Log file | `--log` | | Default `~/.swiftmq-mcp/logs/server.log` |

Example (Claude Code / Claude Desktop):

```json
{
  "mcpServers": {
    "swiftmq-prod": {
      "command": "java",
      "args": ["-jar", "/opt/swiftmq-mcp/swiftmq-mcp-server.jar",
               "--url", "smqp://prod-router:4001", "--user", "analyst"],
      "env": { "SWIFTMQ_PASSWORD": "..." }
    }
  }
}
```

## Architecture

```
MCP client ──stdio (JSON-RPC)──▶ MCP Java SDK (StdioServerTransportProvider)
                                    ▼
                                 Tool handlers (transport-independent ToolRegistry)
                                    ▼                     ▲ NavigationState (current router/context,
                                 SwiftMqGateway (interface; fakeable in tests)    cursors, delta baselines)
                                    ▼
                                 CliConnection (one CLI + lock, ContextGuard, router listener, reconnect)
                                    ▼
                                 SwiftMQ router ──▶ router network
```

- **Stack**: Java 21, Maven, `swiftmq-client` 13.2.4 (javax.jms), official MCP Java SDK
  (`io.modelcontextprotocol.sdk:mcp`, stdio transport), Jackson, SLF4J/Logback, JUnit 5.
  Shaded executable jar.
- **stdout protection** (critical for stdio):
  - At startup, keep the real `System.out` for the SDK transport only, and redirect `System.out`
    to `System.err` *before* the SwiftMQ client is loaded. Any CLI output then lands in the log
    instead of the protocol stream.
  - Logback logs only to stderr and the log file.
  - A test asserts that stdout carries nothing but valid JSON-RPC frames during a full tool run,
    including CLI errors.
- **Connection lifecycle**:
  - The server process starts and answers `initialize` even if the router is unreachable.
    The connect happens in the background, with retry/backoff.
  - Until the connection is up, tools return a clear error ("not connected to smqp://host:4001:
    <reason>").
  - `RouterListener` keeps the availability of routers up to date.
  - On stdin EOF or shutdown: `cli.close()` and the connection is closed, so the router's
    admin-tool lease is released right away.
- **CliConnection**: wraps `CLI`. A `ReentrantLock` makes `sr + cc + read` atomic, and every
  operation starts with an absolute `sr` + `cc`. It applies a per-call timeout. There is one connection per process, so fan-out tools run
  sequentially. That's acceptable for one user, and the router sees exactly one admin tool.
- **NavigationState** (per process = per user, in memory): current router, current context
  (`select_router` / `change_context`), pagination cursors, and the last queue-count snapshot per
  router. The snapshot lets `list_queues` / `network_health` report "Δ since last call". It is
  lost on restart, by design.
- **Transport independence**: tool handlers only depend on `SwiftMqGateway` + `NavigationState`.
  Wiring them to a different SDK transport later (Streamable HTTP) does not touch the tools.

## Guards

- **Paths are always double-quoted**: `cc "<absolute path>"`. Entity names can contain spaces
  (e.g. `sys$scheduler/usage/job-groups/Queue Manager`). An unquoted path fails with
  "Invalid command", while a double-quoted one works. Single quotes and backslash escapes do *not*
  work (verified).
- **Input validation**: names (router, queue, topic, durable, connection, path segments) are
  rejected if they contain `"`, `;`, `/` (within a segment) or control characters. Spaces are
  allowed, because the paths are quoted. Real names seen include `tmp$0-1792864767552`,
  `routeannouncer$initrequests`, `swiftmq.jndi-1`, `127.0.0.1:60526`, `tmp$1-…@router` and
  `Queue Manager`. Everything else is rejected. `CommandBuilder` can only produce `sr <router>`,
  and `cc "<path>"`. `CommandBuilder` is the only place that builds command strings.
- **Context blacklist: hard rule, enforced in `CliConnection`**, so no code path can skip it.
  Every swiftlet and `.env` is readable, **except `sys$authentication`, `sys$mgmt` and `sys$timer`**:
  - `ContextGuard` canonicalizes every target path to an absolute path. It resolves relative
    paths against the current context, rejects `..` escapes, and rejects any path whose first
    segment (case-insensitive) is a denied swiftlet, before a `cc` is issued.
  - The denylist is fixed in code. Config can only *add* entries.
  - The root can't be listed (`cc /` → "Unknown context"; `getContextEntities()` without context →
    "No actual context set!"). So `list_swiftlets` **probes** every non-denied swiftlet known to the
    catalog, plus `.env`, with `cc`. Denied swiftlets are never probed. `swiftmq-metadata.json`
    excludes them.
  - Hiding a context does not open commands. The command whitelist stays the same.
- **Password redaction**:
  - A property is redacted when its name matches `^(.*-)?(password|passwd|secret)$`
    (case-insensitive). The value is replaced with `"***"`.
  - A scan of all 13.2.4 CE/UR/HA `config.xml` files finds exactly 8 `password` properties.
    The anchor keeps `password-check-only` and `link-credit` readable.
  - The metadata generator fails the build if a new unmatched name containing `pass`/`secret`
    appears.
- **Result caps**: all lists are capped and paginated (`limit`/`cursor`).

## Property metadata catalog

A small committed generator (`tools/gen-metadata`) reads the **CE** `kernel/<swiftlet>/config.xml`
files into `src/main/resources/swiftmq-metadata.json`. Each entry is
path → property → type, display name, description, default. It covers all non-denied CE swiftlets.
- The JSON is keyed by edition from the start (`{ "ce": {...} }`), so UR/HA can be added later
  without changing the format.
- It converts CLI string values into typed JSON (Integer, Long, Boolean) and labels and describes
  fields.
- **The catalog is the only source of property names.** The CLI API can list entities but not
  properties, and the runtime `.metadata` entities are empty. Reading a property that isn't there
  throws `CLIException: Property 'x' is unknown in this context!`, which is treated as "absent".
  `.env` property names are hard-coded (`routername`, `release`, `os`, `startuptime`,
  `memory-collect-interval`, `use-smart-tree`; `router-memory-list/*`: `free-memory`, `total-memory`).
- **Smart-tree hint**: on first contact with a router, the server reads `/.env` `use-smart-tree`
  and caches it. When it is `true` and a tool needs data that the smart tree hides (consumers,
  producers, browsers, JMS sessions), the result says "not available" instead of "0". It also
  carries a hint for the user: *"Router 'X' uses the smart management tree, so consumer/producer
  details are hidden. To see them, set `use-smart-tree="false"` on the `<router>` element in
  routerconfig.xml."* The hint is also shown in `get_router_info`.
- The string `"null"` maps to JSON `null`. `connect-time` is `Date.toString()` format, which is
  parsed to ISO-8601. Throughput values look like `"0.00"` (KB/s) and become decimals.
- `swiftmq/` (93 MB of distributions) stays out of git.

Also: a **short TTL cache** (≈ collect-interval, default 10 s) for usage lists, so that fan-out
doesn't hammer routers. And a **log line per tool call** (tool, router, target, duration, result
size; never values or credentials) in the log file.

## MCP tools (v1)

| Tool | Purpose |
|---|---|
| `list_routers` | Available routers in the network (+ connected router, availability) |
| `list_queues` | Queues on a router, with optional name pattern; merged config + usage (message count, consumers, rates) |
| `get_queue` | Full config + usage of one queue (incl. clustered/composite bindings); receivers/senders/browsers if the smart tree is off, otherwise the smart-tree hint |
| `find_queue` | Search a queue name pattern across all available routers |
| `list_topics` | Topic hierarchy on a router |
| `list_durable_subscribers` | `usage/durables`, enriched with backlog from queue `<clientid>$<durable>` |
| `get_topic` | Topic config, subscribers and announce filters |
| **Routing swiftlet** | |
| `list_routing_connections` | Active router-to-router connections on a router (`usage/connections`) |
| `get_routing_table` | Routing table of a router: destinations and their dynamic routes, optionally filtered by destination |
| `get_routing_config` | Listeners, connectors (enabled/host/port/retry), static routes, filters, hop limit. Passwords stripped |
| `network_topology` | Fan-out over all routers to build a graph: nodes = routers, edges = active routing connections. Flags enabled connectors with no active connection, asymmetric links, and unreachable routers |
| **Network swiftlet** | |
| `list_network_connections` | `sys$net/usage`: every connection with its owning swiftlet, connect time and throughput in/out. Filter by swiftlet, sorted by throughput |
| `network_summary` | Per router: connection count and total throughput per swiftlet (jms, amqp, mqtt, routing, mgmt) |
| **Router environment (`.env`)** | |
| `get_router_memory` | `/.env/router-memory-list/<entry>`: `free-memory` and `total-memory` (KB), plus used = total − free and used %. With `router="*"` it fans out over all routers, sorted by usage |
| `get_router_info` | `routername`, `release`, `os`, `startuptime`, `use-smart-tree` (+ hint if `true`), available swiftlets, memory |
| **Connection & navigation** | |
| `connection_info` | URL, user, connected router, available routers, connection state |
| `select_router` | Set the default router for tools when `router` is omitted (must be available) |
| `change_context` | Set the current context for `browse_context` (absolute or relative; `ContextGuard` applies) |
| **Cross-cutting** | |
| `network_health` | Per router: `routerdlq` and `unroutable` depth, `rt$<dest>` backlogs (store-and-forward toward each destination), top-N fullest queues, missing routing connections (from `network_topology`), memory usage % |
| `list_swiftlets` | Top-level contexts of a router (swiftlets + `.env`), found by probing with `cc`, minus the denied ones |
| `browse_context` | Generic read-only listing of any non-denied path: child entities via `getContextEntities`, plus the properties the catalog defines for that path (absent ones skipped), with labels. Covers every swiftlet without a dedicated tool |

v2 candidates: JMS connection/consumer listing, MCP resources (`swiftmq://router/queue/...`),
prompts ("diagnose growing queue"), optional remote mode (below).

## Tasks

### Phase 0 — Spike (resolve unknowns against a live router)
Run 1 (2026-10-24): local CE 13.2.4, `smqp://localhost:4001`, `ConnectionFactory`, admin user,
client `swiftmq-client` 13.2.4. Spike code is in the session scratchpad and is not committed.
- [x] Connect via JNDI `ConnectionFactory` + `createQueueConnection(user, pw)` → `new CLI(qc)`. `getAvailableRouters()` = `[router]`. `actRouter` is null until `sr`
- [x] `sr <router>` and `cc <path>` work via `executeCommand`. Absolute, relative and `..` `cc` all work in the CLI itself, but `ContextGuard` always sends absolute paths
- [x] **Commands that return results are rejected** in programmatic mode: `lc` → `CLIException: Invalid method; command returns a result!`. This was a one-off check only. The server lists via `getContextEntities()` and never sends `lc`
- [x] Errors always surface as `CLIException` (`Unknown context`, `Property '…' is unknown in this context!`, `No actual context set!`)
- [x] **stdout: 0 bytes** written to `System.out` during the whole run, including all error paths. Redirecting stdout is kept anyway as a safety net
- [x] Edition detection is reliable: `cc /sys$hacontroller` and `cc /sys$filecache` → `CLIException: Unknown context` on CE → **CE**
- [x] Swiftlet probing works for all CE swiftlets + `.env`. The root itself can't be listed (see Guards)
- [x] Runtime usage values match the config.xml names: `messagecount`, `msg-consume-rate`, `msg-produce-rate`, `total-consumed`, `total-produced`, `latency`, `active`, `amax-messages`
- [x] `.env` (CE): properties `routername`, `release` (`13.02.04 Community Edition`), `os`, `startuptime`, `memory-collect-interval`, `use-smart-tree`; entity `router-memory-list/<routername>` with `free-memory` / `total-memory` (KB)
- [x] Entity naming:
  - `sys$net/usage` and `sys$jms/usage` entries are `ip:port`
  - durables are `clientid$durablename`
  - subscribers are `<topic>-<n>`
  - `boundto` looks like `tmp$1-…@router`
- [x] Config entity reads work: `queues/testqueue` (cache-size, max-messages, persistence-mode, consumer-mode), `routing/listeners/plainsocket` (port, use-xa, `host-access-list`)
- [x] `.metadata` entities exist in every swiftlet but are empty, so the catalog is required for property names
- [x] **Catalog-driven crawl** over all 14 non-denied CE swiftlets (sampling 3 entities per list): **504 properties read, 0 unknown names**, 126 entities. The CE `config.xml` catalog is confirmed as the source of property names
- [x] Entity names with spaces need a double-quoted `cc` path (see Guards)
- [x] Temporary queue with 1 message: `messagecount=1`, `total-produced=1` (`tmp$14-…@router`; usage entity name = queue name without `@router`). Non-durable subscriber visible in `topicmanager/usage/subscriber/testtopic-5` (clientid, topic, selector `null`, boundto)
- [x] **Usage sublists** missing (`usage/<queue>/receiver|sender|browser`, `sys$jms/usage/<conn>/sessions|tempqueues`), even with live activity → caused by the **smart management tree** (`use-smart-tree="true"`, the default; explained by the user). It's detectable via `/.env` `use-smart-tree`, and the user gets a hint (see catalog section)
- [x] Confirmed with `use-smart-tree="false"` (2026-10-24). The crawl read **521 properties with 0 unknown names**. The only 3 context failures were the unquoted names with spaces in `sys$scheduler`, which happened because the spike didn't quote paths. Sublists now appear:
  - `usage/<queue>/receiver/<id>` (username, clientid `mcp-spike@router`, selector)
  - `usage/<queue>/sender/<id>` (username, clientid)
  - `usage/<queue>/browser/<id>` (username, clientid, selector)
  - `sys$jms/usage/<ip:port>/sessions/<n>/{browser,durables,publisher,receiver,sender,subscriber}/<dest>-<n>`, and `sys$jms/usage/<ip:port>/tempqueues/<name>`
  - With the smart tree on, the queue usage entity has no children at all. That absence is the detection signal, alongside `/.env use-smart-tree`
- [ ] Routing: `usage/connections` and `routing-table` were empty (single router). Verify entity naming and dynamic routes in the 3-router chain
- [ ] CE router network (3 CE routers in a chain A–B–C): routing connections, routing table, `rt$<dest>` queues
- [ ] Read-only admin role (incl. `exclude` for the denied swiftlets, `granted-commands` empty): do `sr`/`cc` and the getters still work under it?

### Phase 0.5 — Housekeeping ✅ (2026-10-24)
- [x] `.gitignore`: `target/`, `.idea/`, `*.iml`, `.DS_Store`, `swiftmq/`
- [x] `CatalogGenerator` (`com.swiftmq.mcp.catalog`) + committed `src/main/resources/swiftmq-metadata.json`:
  - CE only, `{ "release": "13.02.04", "editions": { "ce": {...} } }`, 16 swiftlets (19 minus 3 denied)
  - Regenerate with `mvn -Pgen-catalog process-classes`
  - Keeps type, display, description, default, min/max, choices, read-only/mandatory/reboot-required, `secret`, and the lists' `dynamic-properties`
- [x] Tests:
  - `CatalogTest`: usage properties + types, sublists via templates, CE defaults (`cleanup-interval` 120000, `duplicate-detection-backlog-size` 2000), routing/net, denied swiftlets absent, unknown paths → null
  - The secret flag is set exactly on `sys$routing/{listeners,connectors}/*/password`. The other `password` properties are in `sys$authentication` (denied) or UR/HA
  - `CatalogGeneratorTest`: the committed JSON equals a fresh generation (skipped without `swiftmq/`)
  - `SecretsTest`: incl. denied-context matching
- [x] The generator fails on unreviewed secret-looking names (`Secrets.isUnreviewedSuspicious`); `password-check-only` is reviewed as harmless

### Phase 1 — Skeleton ✅ (2026-10-24)
- [x] Maven project `com.swiftmq:swiftmq-mcp-server`:
  - Java 21, `swiftmq-client` 13.2.4 (javax.jms), MCP Java SDK 2.1.0 (Jackson 3), logback
  - Shaded `target/swiftmq-mcp-server.jar`
  - logback logs to stderr + a rolling file, never stdout. Tests use `logback-test.xml` (stderr only)
- [x] `Main`:
  - `StdoutGuard` (real stdout only for the transport, `System.out` → stderr) runs first
  - Uncaught-exception handler
  - EOF on stdin → graceful close + exit 0
  - Invalid settings → usage on stderr, exit 2
- [x] `SettingsResolver`:
  - Order: args → env → JSON config file → defaults
  - Password only from `SWIFTMQ_PASSWORD` or a password file. `--password` and `"password"` in the config file are rejected
  - The URL must not contain credentials
  - `Settings.toString()` masks secrets
- [x] `SwiftMqMcpServer` on the SDK stdio transport with an empty `ToolRegistry`, plus server instructions. `initialize` and `tools/list` work (no router connection yet; Phase 2 must keep `initialize` independent of the connection)
- [x] Tests (39 total, all green):
  - `SettingsResolverTest` (12)
  - `SwiftMqMcpServerTest`: handshake over piped streams
  - `StdioProcessTest`, which runs a real child JVM:
    - noise on `System.out` never reaches the real stdout
    - stdout carries exactly the JSON-RPC responses
    - the process exits on stdin EOF
    - bad settings → exit 2
- [x] Manual check: `java -jar target/swiftmq-mcp-server.jar --url smqp://localhost:4001 --user admin` with `SWIFTMQ_PASSWORD`. stdout = 2 JSON-RPC lines, and the password doesn't appear in stderr or the log

### Phase 2 — SwiftMQ access layer ✅ (2026-10-25)
Package `com.swiftmq.mcp.access`:
- [x] `Cli` port (narrow: `executeCommand`, `getContextEntities`, `getContextProperty`, `getAvailableRouters`) + `SwiftMqCli` (JNDI lookup, `createQueueConnection(user, pw)`, `new CLI(qc)`, `RouterListener`, JMS `ExceptionListener`, `swiftmq.request.timeout`)
- [x] `CliConnection` implements `SwiftMqGateway`:
  - background connect, so `initialize` never waits
  - reconnect with backoff (1 s → 30 s); a lost connection triggers a reconnect, guarded by a generation counter
  - router availability from `RouterListener` events + `getAvailableRouters()` on connect
  - one `ReentrantLock` with `tryLock(requestTimeout)`; every read is an absolute `sr` + `cc`
  - unknown context → `Optional.empty()`; absent properties are skipped
  - clean `close()` on EOF and in a shutdown hook
- [x] `MgmtPath` (canonical absolute paths; `.`/`..` resolved locally; rejects `"`, `;`, `/` in segments, control chars, leading/trailing blanks), `ContextGuard` (fixed + extra denylist, root not readable), `CommandBuilder` (only `sr <router>` and `cc "<path>"`)
- [x] `ManagementReader`:
  - property names come from the catalog (incl. the built-in `.env` node)
  - values are typed: Integer, Long, Boolean, `"null"` → null, `Date.toString()` → ISO-8601, `throughput-*` → decimal; unparseable values stay strings
  - secrets are redacted (`***`, unset → null)
  - child lists are capped at `maxListItems` (with total + truncated flag)
- [x] `NavigationState`: current router/context; `resolveRouter` picks the explicit router, else the selected one, else the single available one, else an error
- [x] Error model `SwiftMqAccessException` (`NotConnected`, `RouterUnavailable`, `AccessDenied`, `Timeout`). Messages never contain credentials
- [x] Tests (79 total, all green):
  - `PathAndGuardTest` (19): bypass attempts incl. `../../sys$authentication`, case variants, injection via router names
  - `CliConnectionTest` (10): exact commands, nothing sent for denied paths, connecting/retry/reconnect, router events, concurrent reads serialized
  - `ManagementReaderTest` (6)
  - `LiveRouterTest` (5, against the local CE router; skipped if none is listening)
- [x] Manual check: the jar connects to the local router (`available routers: [router]`) and closes on EOF. With an unreachable port it retries with backoff, and the server stays up
- Deferred to Phase 3 (only needed by fan-out/list tools): TTL cache, pagination cursors, delta baselines, smart-tree detection cache

### Phase 3 — Tools ✅ (2026-10-25)
Package `com.swiftmq.mcp.tools`:
- `ToolFactory`: JSON results, `isError` for user-facing errors, a log line per call (tool, outcome, duration; no values), read-only annotations on every tool
- `ToolContext`:
  - router resolution, list entries helper, queue messagecount helper
  - smart-tree cache (5 min)
  - delta baselines (per router; only full `list_queues` calls update them)
  - glob matching
- 21 tools:
  - connection: `connection_info`, `list_routers`, `select_router`
  - queues: `list_queues` (incl. `delta`), `find_queue`, `get_queue`
  - topics: `list_topics`, `get_topic`, `list_durable_subscribers` (backlog = messagecount of the `boundto` queue)
  - router: `get_router_memory`, `get_router_info`
  - routing: `list_routing_connections`, `get_routing_table`, `get_routing_config`, `network_topology` (issues: enabled connector without connection, one-way links, unavailable peers)
  - network: `list_network_connections`, `network_summary`
  - overview: `network_health` (routerdlq, unroutable, `rt$<dest>` backlogs, fullest queues, memory ≥ 90 %, topology issues)
  - browse: `list_swiftlets`, `browse_context`, `change_context`
- `.metadata` entities are hidden
- Verified via the jar against the local CE router: all tools, plus denied paths via absolute, relative (`../sys$mgmt`) and `change_context`
- Tests: `ToolsTest` (11) + `MoreToolsTest` (8) on `ToolTestBase`; 98 tests total
- **Not done, by decision:**
  - Pagination cursors: `limit`, filters and `maxListItems` cover it so far
  - TTL cache: reads are fast enough on the local router; revisit with a real network if fan-out tools get slow
- **Untested with real data** (single local router): routing connections/table/topology, `rt$` backlogs. Needs the 3-router CE chain (Phase 4)

### Phase 4 — Verification
- [ ] Unit tests: validator (injection attempts with `;`, spaces, quotes), `CommandBuilder` emits only `sr`/`cc`, `ContextGuard`, command builder, redaction, `NavigationState`, fake gateway for each tool
- [ ] **stdout purity test**: run every tool (including error paths and a router going down) through the stdio transport and assert stdout contains only valid JSON-RPC
- [ ] Integration test against a CE chain A–B–C (multi-hop routes), with an active producer/consumer and a durable subscriber: every tool, plus a router going down/reconnecting and connector-without-connection detection
- [ ] Startup with the router unreachable → `initialize` OK, tools report "not connected", recovers once the router is up
- [ ] Test with real MCP clients: MCP Inspector, Claude Code, Claude Desktop
- [ ] Security check:
  - Every attempt to reach a denied swiftlet is rejected: `/sys$authentication`, `sys$Authentication`, `/sys$mgmt/roles`, `/sys$timer`, `/sys$queuemanager/../sys$mgmt`, relative paths from `/`, trailing `/`.
  - Denied swiftlets never show up in `list_swiftlets`.
  - No tool can mutate state, and the command log shows only `sr`/`cc` (no `view`, `gc`, …).
  - No redacted property appears in any output.
  - Credentials don't appear in logs or in `connection_info`.

### Phase 5 — Packaging & docs
- [ ] Shaded jar release, README: install, Claude Desktop / Claude Code config snippets, settings reference, SwiftMQ read-only admin-role snippet (with `exclude` filters), troubleshooting (logs, stdout, **smart tree → missing consumer/producer details**)

## Later: UR/HA support

Based on the facts collected above:
- **Edition detection per router**, cached and invalidated on reconnect:
  1. `cc /sys$hacontroller` succeeds → HA
  2. else `cc /sys$filecache` succeeds → UR
  3. else → CE

  The CE case was already verified in Phase 0. Alternative: `/.env` `release` contains the
  edition name (`13.02.04 Community Edition`). Check what UR/HA report there; it may replace the
  `cc` probing.
- **Layered catalogs**: `ur` = `ce` + UR, and `ha` = `ur` + HA (additions + per-swiftlet override). The catalog is picked per router.
- **Probing** also covers `sys$filecache`, `sys$jac` and `sys$hacontroller`.
- **Tests**: a mixed-edition chain, plus generator tests (`sys$jac` in `ur`/`ha` only, HA's `duplicate-detection-backlog-size` default of 100).
- **Until then**: on a UR/HA router, the CE catalog still covers the shared swiftlets. UR/HA-only swiftlets just aren't listed.

## v2 (optional): remote mode

Only if central access, web-based clients (e.g. claude.ai connectors) or users without their own
SwiftMQ credentials become a requirement. The tool layer stays unchanged. It would add:
- Streamable HTTP transport on embedded Jetty, with TLS terminated by the server.
- MCP-level auth: API keys (later OAuth 2.1), mapped to profiles (router/queue/topic patterns,
  message access).
- A shared service account and `CliPool`.
- Per-user sessions bound to `Mcp-Session-Id`, with lease (idle timeout, renewed per request),
  max lifetime, reaper, per-key/global limits, and `404` → re-initialize.
- `NavigationState` becomes per session.
- A central audit log.

## Open questions for the user
_(none at the moment)_

## Review
_(to be filled after implementation)_
