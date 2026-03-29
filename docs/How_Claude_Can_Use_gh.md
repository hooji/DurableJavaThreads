# How to Use the GitHub CLI (`gh`) in This Repository

The `gh` CLI is available in the Claude Code sandbox but requires one
environment variable to work, because the sandbox proxies git traffic
through `127.0.0.1` which `gh` does not recognize as GitHub.

## Setup

Set `GH_REPO` to tell `gh` which repository to target:

```bash
export GH_REPO=hooji/DurableJavaThreads
```

That's it. `GITHUB_TOKEN` is already set in the sandbox, and the proxy
environment variables handle network routing. No `gh auth` needed.

## Usage examples

```bash
# List workflow runs (CI)
gh run list

# View a specific run
gh run view <run-id>

# List PRs
gh pr list

# Create a release
gh release create v1.0.0 target/my-jar.jar --title "v1.0.0" --notes "Notes"

# List issues
gh issue list

# View CI workflow files
gh api repos/hooji/DurableJavaThreads/actions/workflows
```

## Why GH_REPO is needed

`gh` determines the target repository using this precedence:

1. `GH_REPO` environment variable
2. `--repo` / `-R` flag
3. Git remote URL parsing

The sandbox's git remote points to `http://127.0.0.1:PORT/...` which
`gh` cannot parse as a GitHub host, so step 3 fails. Setting `GH_REPO`
bypasses remote-sniffing entirely.
