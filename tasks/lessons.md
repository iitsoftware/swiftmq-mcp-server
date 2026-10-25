# Lessons

Rules derived from corrections during planning/implementation (2026-10-24).

## SwiftMQ CLI Admin API
- **Read only via getters.** Lists come from `getContextEntities()`, values from `getContextProperty()`. Never `lc`, which the API refuses anyway ("command returns a result").
- **Know the shell vs. API boundary.** `output`, aliases, `echo`, `var`, `execute` etc. belong to the interactive CLI shell, not the CLI Admin API. Don't design around shell features.
- **Don't execute commands beyond navigation.** Only `sr` and `cc`. No `view`, no `gc`: message content stays off-limits.
- **Missing usage sublists usually mean the smart tree is on** (`use-smart-tree`, default true, readable at `/.env`). Hint the user to set `use-smart-tree="false"` on `<router>`; don't report 0.
- **Use the javax client** (`com.swiftmq:swiftmq-client`), not `swiftmq-client-jakarta`, unless told otherwise.

- **One connection reaches the whole network.** No per-router passwords or `authenticate` are needed; don't add per-router credentials.

## Editions
- HA builds on UR, and UR builds on CE (additions + per-swiftlet override). Ask about scope before building for all editions: v1 is **CE only**.

## Design process
- **Clarify the deployment model early** (local stdio vs. remote). It decides auth, sessions, TLS and most of the architecture. Ask before designing the heavy variant.
- **Prefer denylists the user names** over guessing allowlists when the user knows the domain.
- **Verify "facts" taken from diffs and docs against the source.** E.g. the CE/HA default difference was `duplicate-detection-backlog-size`, not `cleanup-interval`. A test caught it.

## Testing
- **Test against a real router network early.** A single idle router hid four bugs that only a 3-router network showed:
  entity names with '/', a hanging connect behind a port proxy, the empty-network-means-healthy trap, and connector
  issues masked by incoming connections. Fakes encode my assumptions; real data corrects them.
- **Network-wide answers must never be silently empty.** Fail with the reason (e.g. not connected) instead of
  returning an empty result that looks healthy.
- **Every blocking external call needs a time limit**, including connection setup.

## Git
- **Anchor root-level ignore patterns** (`/swiftmq/`, not `swiftmq/`). An unanchored directory pattern also matched the
  Java package path `src/main/java/com/swiftmq/` and silently excluded all sources. Check `git status` for the
  expected files before every commit.
