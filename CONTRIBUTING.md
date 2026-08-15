# Contributing

Thanks for considering a contribution to MCP Screenshotter.

## Getting set up

Install the system requirements listed in the [README](README.md#requirements), then:

```bash
mvn clean package -DskipTests
```

This builds `server/target/mcp-screenshotter-server.jar` and `worker/target/mcp-screenshotter-worker.jar`.

## Running the tests

Unit tests (no display/D-Bus/AT-SPI2 needed - OpenCV and the X11 bindings are exercised through
mocks or ship their native libs inside the dependency jars):

```bash
mvn test
```

The full GUI end-to-end test, which actually spins up the sandbox, launches a sample GTK app, and
drives it via `mouse_action`/`get_ui_tree`/`get_screenshot`:

```bash
mvn clean package -DskipTests
python3 e2e/test_gui.py
```

On a headless machine (no host X session for `Xephyr` to attach to, e.g. over SSH or in CI), run
it against `Xvfb` instead - see [Display Backend](README.md#display-backend):

```bash
SCREENSHOTTER_DISPLAY_BACKEND=xvfb python3 e2e/test_gui.py
```

Both are run automatically in CI (`.github/workflows/ci.yml`) on every push and pull request.

## Before opening a PR

- Add or update tests for the behavior you're changing.
- Update the [README](README.md) if you're changing anything user-facing (a tool's parameters, an
  environment variable, a requirement).
- Make sure `mvn clean verify` and `e2e/test_gui.py` both pass locally.

## Commit messages

This repo follows [Conventional Commits](https://www.conventionalcommits.org/) - `feat:`, `fix:`,
`docs:`, `test:`, `refactor:`, `chore:`, `ci:`, etc. Look at `git log` for examples. Keep the
subject line short and imperative; put the "why", not just the "what", in the body when it isn't
obvious from the diff alone.

## Reporting issues

When filing a bug, please include:

- The `SCREENSHOTTER_DISPLAY_BACKEND` you're using (`xephyr` or `xvfb`).
- Whether the target application supports AT-SPI2 or you're relying on the OpenCV fallback.
- Relevant stderr output - re-run with `SCREENSHOTTER_DEBUG=1` for more detail (see
  [Debug Logging](README.md#debug-logging)).

## Security

Please read [Security Considerations](README.md#security-considerations) before relying on this
project's sandboxing for anything beyond convenience - `launch_app` is not a hardened jail. If
you've found an actual security issue (not just "the sandbox doesn't isolate X", which is already
documented), please open an issue describing it rather than a PR with an exploit.
