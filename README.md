# nixbot-cli

> A small CLI for inspecting and controlling [Nixbot](https://github.com/Mic92/nixbot) CI builds from the terminal.

`nixbot-cli` gives Nixbot a `gh`-style workflow:

- list repos, builds, and the build queue
- view a build with its attributes
- get a one-shot failure summary with log tails
- view the history of one attribute across builds
- fetch raw build logs
- watch a build until it finishes
- restart or cancel builds, enable or disable repos
- emit human, plain text, JSON, or EDN output

## Motivation

Nixbot already gives you CI for Nix flakes.
But humans and coding agents often need to stay in the terminal: watch a push, see which attribute failed, and fetch the logs.

```bash
./nixbot-cli watch --compact --exit-status
# #7 github/outskirtslabs/clave main failed — 5 succeeded, 1 failed, 0 pending, 0 cancelled — failed: x86_64-linux.tests

./nixbot-cli failures 7
./nixbot-cli logs 7 x86_64-linux.tests --tail 100
```

## Requirements

- [Babashka](https://babashka.org/)
- `git`, for local repository auto-detection

Point the CLI at your Nixbot instance and provide an API token (create one under Settings in the Nixbot web UI):

```bash
export NIXBOT_URL=https://nixbot.example.com
export NIXBOT_API_TOKEN=bnix_...
```

Or read the API token from a command:

```bash
export NIXBOT_API_TOKEN_COMMAND='op item get "Nixbot" --reveal --fields NIXBOT_API_TOKEN'
```

Both variables are also read from a `.env` file in the current directory.

Alternatively, put the settings in `$XDG_CONFIG_HOME/nixbot-cli/config.edn` (all keys optional; environment variables take precedence over the config file):

```clojure
{:url           "https://nixbot.example.com"
 :token         "bnix_..."
 ;; or, instead of :token, a command printing the token to stdout:
 :token-command "op item get Nixbot --reveal --fields password"
 :default-forge "github"}
```

`nixbot-cli config` shows the effective settings and where each one came from:

```text
Config file: /home/casey/.config/nixbot-cli/config.edn

url:           https://ci.outskirtslabs.com  (from config.edn :url)
token:         bnix_JQjxN…  (from NIXBOT_API_TOKEN (environment))
default-forge: github  (from built-in default)
```

## Install

<details>
<summary>Option 1: Direct download</summary>

```bash
curl -fsSL https://raw.githubusercontent.com/outskirtslabs/nixbot-cli/main/nixbot-cli -o ~/.local/bin/nixbot-cli
chmod +x ~/.local/bin/nixbot-cli
```

</details>

<details>
<summary>Option 2: Install with bbin</summary>

```bash
bbin install io.github.outskirtslabs/nixbot-cli
```

</details>

<details>
<summary>Option 3: Install with Nix flakes</summary>

Install into your user profile:

```bash
nix profile install github:outskirtslabs/nixbot-cli
```

Or run without installing:

```bash
nix run github:outskirtslabs/nixbot-cli -- help
```

To use it from another flake, add the input:

```nix
{
  inputs.nixbot-cli.url = "github:outskirtslabs/nixbot-cli";
}
```

Then add the package to your NixOS or Home Manager packages:

```nix
{ inputs, pkgs, ... }:
{
  environment.systemPackages = [
    inputs.nixbot-cli.packages.${pkgs.system}.default
  ];

  # or, with Home Manager:
  # home.packages = [ inputs.nixbot-cli.packages.${pkgs.system}.default ];
}
```

</details>

<details>
<summary>Option 4: From git</summary>

```bash
git clone https://github.com/outskirtslabs/nixbot-cli.git
cd nixbot-cli
bb build
ln -s "$(pwd)/nixbot-cli" ~/.local/bin/nixbot-cli
```

</details>

## Usage

Run commands from a git checkout, or pass `-R [FORGE/]OWNER/REPO`.
Without `-R` the repository is detected from the nearest git checkout (walking up from the current directory).
The default forge is `github`; override it with `NIXBOT_CLI_DEFAULT_FORGE` or by using the three-part `FORGE/OWNER/REPO` form.

### List repos

```bash
./nixbot-cli repos
```

### List builds

```bash
# Current repository
./nixbot-cli builds

# Another repository, filtered
./nixbot-cli builds -R outskirtslabs/clave --status failure --limit 5
./nixbot-cli builds --branch main --pr 12 --commit f54be57a
```

Failed builds show their failed attributes nested underneath (in json/edn output they appear as a `failed_attrs` vector on each item):

```text
Builds for github/outskirtslabs/llx
#3  [OK] succeeded  main  ea558cd4  2026-06-11T07:41:29.816753Z
#2  [FAIL] failed  main  7440c727  PR#1  2026-06-10T15:40:39.555190Z
    ├─ x86_64-linux.cljs  [FAIL] failed
    └─ x86_64-linux.tests  [FAIL] failed
```

### View a build

```bash
# Latest build for the current repository
./nixbot-cli build

# A specific build
./nixbot-cli build 7 -R outskirtslabs/clave
```

When a build has failures, the output lists the failed attributes with a ready-to-run `logs` command.

### Failure summary

One request answering "why did this build fail?" — failed attributes with errors and log tails:

```bash
./nixbot-cli failures 7 --tail 100
```

### Attribute history

```bash
./nixbot-cli attr x86_64-linux.tests --limit 10
```

### Fetch logs

```bash
./nixbot-cli logs 7 x86_64-linux.tests

# Only the last 100 lines
./nixbot-cli logs 7 x86_64-linux.tests --tail 100

# Without an attribute: list the build's attributes to pick from
./nixbot-cli logs 7
```

### Watch a build

```bash
# Watch the latest build
./nixbot-cli watch

# Compact agent-friendly output
./nixbot-cli watch --compact --exit-status --interval 10
```

`--exit-status` exits non-zero when the watched build fails or is cancelled.
Compact output includes failed attribute names as soon as Nixbot reports them.

### Queue

```bash
./nixbot-cli queue
```

### Control builds and repos

These require a token whose user is authorized on the Nixbot instance (admins, or PR authors for their own PRs):

```bash
./nixbot-cli restart 7
./nixbot-cli cancel 7
./nixbot-cli enable -R outskirtslabs/clave
./nixbot-cli disable -R outskirtslabs/clave
```

### Machine-readable output

Global output flags work before or after the command:

```bash
./nixbot-cli --json builds -R outskirtslabs/clave
./nixbot-cli --edn build 7
./nixbot-cli --plain watch --compact
```

You can also use `--format human|plain|json|edn`.

### Getting help

```bash
./nixbot-cli help
```

## Development

Use the Nix dev shell:

```bash
nix develop
```

Common tasks:

```bash
# Run tests
bb test

# Run CI checks: tests, format check, and lint
bb ci

# Rebuild the uberscript
bb build

# Build the Nix package and run all checks
nix flake check
```

## License: European Union Public License 1.2

Copyright © 2026 Casey Link

Distributed under the [EUPL-1.2](https://spdx.org/licenses/EUPL-1.2.html).
