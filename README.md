# swiftmq-mcp-server

A local, **read-only** [MCP](https://modelcontextprotocol.io) server for [SwiftMQ](https://www.swiftmq.com) router networks.
You start it on your own machine from an AI client (Claude, ChatGPT/Codex, GitHub Copilot, …). It connects with **your
SwiftMQ credentials** via the CLI Admin API to a router, and from there it sees every router in that router network.

- Read-only: nothing can be created, changed or deleted, and message contents are not accessible.
- `sys$authentication`, `sys$mgmt` and `sys$timer` are never accessed. Passwords in the management tree are redacted.
- Transport: stdio (the client starts the server as a child process).
- Supported: SwiftMQ **13.x Community Edition (CE)**.

> **Status:** feature-complete for SwiftMQ 13.x CE. Tested against a single router and a 3-router network. UR/HA
> support and an optional remote mode are possible next steps; see [`tasks/todo.md`](tasks/todo.md).

## Tools

| Area | Tool | What it shows |
|---|---|---|
| Connection | `connection_info` | Connection URL, user, state (incl. last error), available routers, selected router |
| | `list_routers` | Routers currently available in the router network |
| | `select_router` | Sets the default router for the following calls |
| Queues | `list_queues` | Active queues with messagecount, rates, totals, latency, and `delta` since the previous call; glob filter |
| | `find_queue` | Searches all routers for queues matching a pattern |
| | `get_queue` | One queue: usage, configuration (regular/clustered/composite incl. bindings), receivers/senders/browsers |
| Topics | `list_topics` | Topics with active and durable subscriber counts |
| | `get_topic` | One topic: subscribers and durable subscribers with backlog |
| | `list_durable_subscribers` | Durable subscribers with backlog (messages waiting), largest first |
| Routing | `list_routing_connections` | Active router-to-router connections |
| | `get_routing_table` | Destinations and dynamic routes |
| | `get_routing_config` | Listeners, connectors, static routes, filters (passwords redacted) |
| | `network_topology` | Graph of the whole network, plus issues (connector without connection, one-way links, unavailable routers) |
| Network | `list_network_connections` | All connections with owning swiftlet and throughput |
| | `network_summary` | Connections and throughput per swiftlet |
| Router | `get_router_memory` | Free/total/used memory of one router, or of all routers |
| | `get_router_info` | Release, OS, startup time, smart tree setting, memory, available swiftlets |
| Overview | `network_health` | Dead letter queue, unroutable, store-and-forward backlogs, fullest queues, memory, routing issues |
| Browse | `list_swiftlets` | Available management areas of a router |
| | `browse_context` | Any context of the management tree (properties + children), for everything without a dedicated tool |
| | `change_context` | Sets the current context for `browse_context` (like `cc`, with relative paths and `..`) |

Every tool takes an optional `router`. Without it, the tool uses the selected router, or the only available one.
All tools are marked read-only.

**Consumer details** (receivers, senders, browsers) are only visible when the router runs with
`use-smart-tree="false"` on the `<router>` element in `routerconfig.xml`. Otherwise the tools say so and give this hint.

## Requirements

- Java **21+**
- A SwiftMQ 13.x router reachable from your machine (default `smqp://host:4001`)
- A SwiftMQ user with admin access to the management tree. Recommended: a dedicated read-only user, see
  [Read-only SwiftMQ user](#read-only-swiftmq-user-recommended). Once connected, the server can reach every router
  connected to that router; no per-router credentials are needed.

## Build

```bash
mvn package
# → target/swiftmq-mcp-server.jar  (single executable jar)
```

## Settings

Settings come from command line arguments, then environment variables, then an optional JSON config file.

| Setting | Argument | Environment | Default |
|---|---|---|---|
| Router URL | `--url` | `SWIFTMQ_URL` | — (required) |
| User | `--user` | `SWIFTMQ_USER` | — (required) |
| Password | `--password-file <file>` | `SWIFTMQ_PASSWORD` | none |
| JNDI connection factory | `--connection-factory` | `SWIFTMQ_CONNECTION_FACTORY` | `ConnectionFactory` |
| Config file | `--config <file>` | — | none |
| Log file | `--log <file>` | — | `~/.swiftmq-mcp/logs/server.log` |

The password is never accepted as an argument (it would be visible in the process list), never in the config file,
and never inside the URL. Use `SWIFTMQ_PASSWORD` or `--password-file`:

```bash
# one command: the variable is passed to java
SWIFTMQ_PASSWORD=secret java -jar target/swiftmq-mcp-server.jar --url smqp://localhost:4001 --user admin

# or export it first
export SWIFTMQ_PASSWORD=secret
java -jar target/swiftmq-mcp-server.jar --config config/swiftmq-mcp.json
```

`SWIFTMQ_PASSWORD=secret && java …` does **not** work: it sets a shell variable that isn't passed on to `java`.

URL options follow SwiftMQ's syntax, without `?`: `smqp://localhost:4001/timeout=10000;reconnect=true`.

Routers don't have to be configured. The server learns which routers in the network are available, and when they
become unavailable, from the connected router.

Optional config file. Copy `config/swiftmq-mcp.json.example` to `config/swiftmq-mcp.json` (not versioned) and pass
`--config config/swiftmq-mcp.json`:

```json
{
  "url": "smqp://prod-router:4001/timeout=10000",
  "user": "analyst",
  "passwordFile": "/Users/me/.swiftmq-mcp/prod.pw",
  "limits": { "maxListItems": 500, "requestTimeoutMs": 15000 },
  "deniedContexts": ["sys$streams"]
}
```

`deniedContexts` can only add to the built-in denylist.

## Quick test without an AI client

`scripts/smoke-test.jsonl` contains a handshake, `tools/list` and three tool calls. Pipe it into the server:

```bash
{ cat scripts/smoke-test.jsonl; sleep 3; } | SWIFTMQ_PASSWORD=secret java -jar target/swiftmq-mcp-server.jar --url smqp://localhost:4001 --user admin
```

stdout shows one JSON-RPC response per request (the order may vary; match them by `id`). Logs go to stderr and the
log file. Add `2>/dev/null` to see only the responses.

The `sleep` keeps stdin open until the answers are written. Per the MCP spec, closing stdin ends the session, so
requests that are still unanswered at that moment get no response. Real clients keep stdin open for the whole session.

Interactive alternative: the [MCP Inspector](https://github.com/modelcontextprotocol/inspector) (needs Node.js):

```bash
npx @modelcontextprotocol/inspector -e SWIFTMQ_PASSWORD=secret \
  java -jar /path/to/swiftmq-mcp-server.jar --url smqp://localhost:4001 --user admin
```

## Read-only SwiftMQ user (recommended)

The server itself only ever navigates (`sr`, `cc`) and reads. It never accesses `sys$authentication`, `sys$mgmt` or
`sys$timer`, and it redacts passwords. These checks run on your machine, though; the real security boundary is the
router. With an admin role, the router itself enforces read-only access for the user the server connects with.

This preconfig file creates the user `mcp` with a read-only role. It denies the three contexts and keeps full
access for the existing `admin` user (tested with CE 13.2.3; also at
[`scripts/test-network/preconfig/readonly-role.xml`](scripts/test-network/preconfig/readonly-role.xml)):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<router>
  <swiftlet name="sys$authentication">
    <users>
      <user name="mcp" password="change-me" _op="add"/>
    </users>
  </swiftlet>
  <swiftlet name="sys$mgmt" admin-roles-enabled="true">
    <roles>
      <role name="admin" _op="add">
        <context-filters>
          <context-filter name="everything" type="include" cli-context-predicate="/%" granted-commands="%"/>
        </context-filters>
      </role>
      <role name="mcp" _op="add">
        <context-filters>
          <context-filter name="all-read-only" type="include" cli-context-predicate="/%" read-only="true"/>
          <context-filter name="no-authentication" type="exclude" cli-context-predicate="/sys$authentication%"/>
          <context-filter name="no-mgmt" type="exclude" cli-context-predicate="/sys$mgmt%"/>
          <context-filter name="no-timer" type="exclude" cli-context-predicate="/sys$timer%"/>
        </context-filters>
      </role>
    </roles>
  </swiftlet>
</router>
```

Apply it at router start: `./router /path/to/readonly-role.xml` (the first argument of the start script is a
preconfig file), or with Docker via `SWIFTMQ_PRECONFIG`.

**Important:**
- **Apply it to every router of the network.** A role applies on the router that executes the command, and the role
  name must equal the user name.
- **With `admin-roles-enabled="true"`, admin users without a role can't connect any admin tool anymore**
  ("No role defined for user"). Give every existing admin user a role, like `admin` above.
- Result (verified): `mcp` can read every router of the network. The denied contexts answer "Access to this context
  is not granted", and changes answer "Command execution is not granted!".

## Integration

Every client gets the same command:

```
java -jar /path/to/swiftmq-mcp-server.jar --url smqp://localhost:4001 --user admin
```

The password goes in the environment variable `SWIFTMQ_PASSWORD`. The snippets use `/path/to/swiftmq-mcp-server.jar`;
use the absolute path of your jar. On Windows write paths as `C:\\path\\to\\swiftmq-mcp-server.jar` in JSON.

For several routers or networks, add one entry per network with different names (`swiftmq-prod`, `swiftmq-test`, …).

### Overview

| Client | Local (stdio) server | How |
|---|---|---|
| Claude Desktop | ✅ | `claude_desktop_config.json` |
| Claude Code | ✅ | `claude mcp add` or `.mcp.json` |
| ChatGPT desktop app (Codex), Codex CLI / IDE extension | ✅ | `~/.codex/config.toml` |
| ChatGPT web (connectors / developer mode) | ❌ remote only | see [ChatGPT web](#chatgpt-web) |
| GitHub Copilot in VS Code (agent mode) | ✅ | `.vscode/mcp.json` |
| GitHub Copilot CLI | ✅ | `~/.copilot/mcp-config.json` |
| Microsoft 365 Copilot / Copilot Studio | ❌ remote only | see [Microsoft 365 Copilot](#microsoft-365-copilot--copilot-studio) |
| Windows On-device Agent Registry | ⚠️ preview | see [Windows](#windows-on-device-agent-registry-preview) |

### Claude Desktop

Open **Settings → Developer → Edit Config**, or edit the file directly:

- macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`
- Windows: `%APPDATA%\Claude\claude_desktop_config.json`

```json
{
  "mcpServers": {
    "swiftmq": {
      "command": "java",
      "args": ["-jar", "/path/to/swiftmq-mcp-server.jar", "--url", "smqp://localhost:4001", "--user", "admin"],
      "env": { "SWIFTMQ_PASSWORD": "secret" }
    }
  }
}
```

Quit Claude Desktop completely and start it again. The server then appears under the tools/connectors menu.

- Claude Desktop doesn't expand variables in this file, so the password is stored there in plain text. To keep it out
  of the file, use `--password-file` instead of `env`.
- GUI apps on macOS often don't see the `java` from your shell `PATH`. If the server doesn't start, use the absolute
  path, e.g. the output of `/usr/libexec/java_home -v 21` followed by `/bin/java`.
- Client log: `~/Library/Logs/Claude/mcp-server-swiftmq.log` (macOS), `%APPDATA%\Claude\logs` (Windows).

Docs: https://modelcontextprotocol.io/docs/develop/connect-local-servers

### Claude Code

```bash
claude mcp add swiftmq --transport stdio --scope user -e SWIFTMQ_PASSWORD=secret -- \
  java -jar /path/to/swiftmq-mcp-server.jar --url smqp://localhost:4001 --user admin
```

- `--scope`: `local` (default: this project, only you), `project` (writes `.mcp.json` for the team) or `user` (all
  your projects).
- Put the server name first and all options before `--`. A name directly after `-e KEY=VALUE` is read as another
  variable ("Invalid environment variable format").
- Check with `claude mcp list`, or with `/mcp` inside Claude Code.

For a shared project `.mcp.json` without a secret in it, Claude Code expands `${VAR}` from your environment:

```json
{
  "mcpServers": {
    "swiftmq": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/path/to/swiftmq-mcp-server.jar", "--url", "smqp://localhost:4001", "--user", "admin"],
      "env": { "SWIFTMQ_PASSWORD": "${SWIFTMQ_PASSWORD}" }
    }
  }
}
```

Docs: https://code.claude.com/docs/en/mcp

### ChatGPT desktop app / Codex CLI / Codex IDE extension

These share one config file: `~/.codex/config.toml` (macOS/Linux) or `%USERPROFILE%\.codex\config.toml` (Windows).

```toml
[mcp_servers.swiftmq]
command = "java"
args = ["-jar", "/path/to/swiftmq-mcp-server.jar", "--url", "smqp://localhost:4001", "--user", "admin"]
env_vars = ["SWIFTMQ_PASSWORD"]   # passed through from the environment Codex was started in
```

Codex starts servers with a clean environment, so `SWIFTMQ_PASSWORD` must be listed in `env_vars`. Alternatively,
set it literally:

```toml
[mcp_servers.swiftmq.env]
SWIFTMQ_PASSWORD = "secret"
```

Or from the command line:

```bash
codex mcp add swiftmq --env SWIFTMQ_PASSWORD=secret -- \
  java -jar /path/to/swiftmq-mcp-server.jar --url smqp://localhost:4001 --user admin
```

Docs: https://learn.chatgpt.com/docs/extend/mcp, https://developers.openai.com/codex/config-reference

### ChatGPT web

ChatGPT on the web (Settings → Apps/Connectors → Advanced → Developer mode) only accepts **remote** MCP servers over
HTTPS, not local ones. Options:

- Use the ChatGPT desktop app / Codex as shown above.
- OpenAI's [Secure MCP Tunnel](https://github.com/openai/tunnel-client) can run a local stdio server
  (`--mcp-command "java -jar …"`) and relay it to ChatGPT. Not tested with this server yet.
- A remote mode for this server is planned as an optional later step.

### GitHub Copilot in VS Code (agent mode)

Create `.vscode/mcp.json` in the workspace, or use **MCP: Open User Configuration** for all workspaces. VS Code asks
for the password once and stores it securely:

```json
{
  "inputs": [
    { "type": "promptString", "id": "swiftmq-password", "description": "SwiftMQ password", "password": true }
  ],
  "servers": {
    "swiftmq": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/path/to/swiftmq-mcp-server.jar", "--url", "smqp://localhost:4001", "--user", "admin"],
      "env": { "SWIFTMQ_PASSWORD": "${input:swiftmq-password}" }
    }
  }
}
```

Start the server from the `mcp.json` editor (Start), then pick its tools in the Copilot Chat tool picker (agent mode).

Docs: https://code.visualstudio.com/docs/copilot/customization/mcp-servers

### GitHub Copilot CLI

`~/.copilot/mcp-config.json`:

```json
{
  "mcpServers": {
    "swiftmq": {
      "type": "local",
      "command": "java",
      "args": ["-jar", "/path/to/swiftmq-mcp-server.jar", "--url", "smqp://localhost:4001", "--user", "admin"],
      "env": { "SWIFTMQ_PASSWORD": "secret" },
      "tools": ["*"]
    }
  }
}
```

Or use `/mcp add` inside the CLI. `${VAR}` expansion in `env` has been unreliable in some versions, so use a literal
value or `--password-file` if it doesn't work.

Docs: https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/add-mcp-servers

### Microsoft 365 Copilot / Copilot Studio

These only connect to **remote** MCP servers (Streamable HTTP URL), so they can't use this server in its current
local form. They become possible with the planned remote mode.

Docs: https://learn.microsoft.com/en-us/microsoft-copilot-studio/agent-extend-action-mcp

### Windows On-device Agent Registry (preview)

Windows Insider builds (26220.7262+) have an on-device MCP registry (`odr.exe`). It registers local servers from an
MCPB `manifest.json`. An MCPB bundle for this server isn't provided yet; it would also give Claude Desktop a one-click
install with a secure password prompt.

Docs: https://learn.microsoft.com/en-us/windows/ai/mcp/overview

## Troubleshooting

Start with `connection_info`: it shows the connection state and the last error.

- **Server doesn't start:** check the client's MCP log and the server log `~/.swiftmq-mcp/logs/server.log`.
  Invalid settings are reported on stderr together with the usage text.
- **Tools report "Not connected":** call `connection_info`. It shows the state and the last connection error. The
  server keeps retrying in the background (1 s up to 30 s).
- **`invalid password`:** the password didn't reach the server. `SWIFTMQ_PASSWORD=secret java …` passes it;
  `SWIFTMQ_PASSWORD=secret && java …` doesn't. In client configs, put it in `env`.
- **`no response within … ms` / stays `CONNECTING`:** the router is reachable, but the address it hands out to JMS
  clients isn't. Typical behind NAT or Docker: set `connectaddress` / `connectport` on the router's JMS listener to
  the address clients use (see `scripts/test-network/preconfig/routerA.xml`).
- **`Router 'X' denies access to …`:** an admin role on that router excludes the context. That's intended for
  sys$authentication, sys$mgmt and sys$timer; for anything else, check the role.
- **Consumer details "not available":** the router runs with the smart management tree; set
  `use-smart-tree="false"` on `<router>` and restart it.
- **`java` not found:** use the absolute path to a Java 21+ `java` binary as `command`.
- **Nothing on stdout but errors in the client:** the server writes only JSON-RPC to stdout and everything else to
  stderr/log. Don't wrap it in scripts that print to stdout.

## Development

```bash
mvn test                           # unit, security and stdio process tests (live-router tests run if localhost:4001 answers)
mvn -Pgen-catalog process-classes  # regenerate the property catalog from ./swiftmq (local CE distribution)
```

### Test network

`scripts/test-network` starts three CE routers in a chain (`routerA - routerB - routerC`) with Docker Compose.
Only routerA is reachable from the host, on `localhost:14001`; B and C are reached through routing.

```bash
(cd scripts/test-network && docker compose up -d)
SWIFTMQ_TEST_NETWORK=true mvn test -Dtest=RouterNetworkTest   # stops and starts routerC during the test
SWIFTMQ_PASSWORD=secret java -jar target/swiftmq-mcp-server.jar --url smqp://localhost:14001/timeout=10000 --user admin
(cd scripts/test-network && docker compose down -v)
```

With the read-only role from above on all three routers (user `mcp` / `mcp-secret`, `admin` keeps full access):

```bash
(cd scripts/test-network && docker compose -f docker-compose.yml -f docker-compose.readonly-role.yml up -d)
SWIFTMQ_PASSWORD=mcp-secret java -jar target/swiftmq-mcp-server.jar --url smqp://localhost:14001/timeout=10000 --user mcp
```

### Known limitation

The CLI Admin API can't enter management entities whose name contains `/`. Outgoing routing connections are named
like that (`routerB/172.21.0.2:4100`). They are listed with router name and remote address, but without details.

Plan and progress: [`tasks/todo.md`](tasks/todo.md).

## License

Apache License 2.0, see [LICENSE](LICENSE).
