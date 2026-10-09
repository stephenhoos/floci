# Docker Images

This fork publishes `ghcr.io/stephenhoos/floci`, built from its security-hardened source.
Use [Releases](https://github.com/stephenhoos/floci/releases/latest) for the corresponding
Docker archives, Java downloads, Compose file and checksums.

| Tag | Contents |
|---|---|
| `latest` | Latest fork release |
| `2.2.0-hoos.1` | Pinned release based on upstream 2.2.0 |
| `latest-compat`, `2.2.0-hoos.1-compat` | Aliases of the JVM images, with AWS tools already included |

All tags support `linux/amd64` and `linux/arm64`. The image contains Java 25,
Floci's entire Quarkus runtime, Python, AWS CLI, `awslocal` and boto3 on Ubuntu Noble.
The entrypoint starts the emulator as uid 1001 and grants the Docker socket group
when mounted. LocalStack init hooks and health aliases remain supported.

This fork publishes JVM builds. Upstream native-image memory and startup benchmarks
do not describe these builds. There are no fork nightly or native baseline tags.

```yaml
services:
  floci:
    image: ghcr.io/stephenhoos/floci:2.2.0-hoos.1
    ports:
      - "127.0.0.1:4566:4566"
```

See [Installation](../getting-started/installation.md) for the official CLI flow,
Docker-backed workload Compose file, readiness check and dummy AWS credentials.

## Verify container signatures

The publishing workflow signs each release index and its platform manifests using
GitHub Actions OIDC and Sigstore. Use cosign v3 and verify the exact release identity:

```bash
cosign verify \
  --certificate-identity 'https://github.com/stephenhoos/floci/.github/workflows/publish-fork.yml@refs/tags/v2.2.0-hoos.1' \
  --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' \
  ghcr.io/stephenhoos/floci:2.2.0-hoos.1
```

The release's `image-digest.txt` records the manifest digest. Pin that digest for an
immutable reference. BuildKit provenance and SPDX SBOMs are attached to the platform
images and included in the recursively signed manifest index.

```bash
docker buildx imagetools inspect ghcr.io/stephenhoos/floci:2.2.0-hoos.1
```

## Publish another fork release

The [Publish Hoos builds](https://github.com/stephenhoos/floci/actions/workflows/publish-fork.yml)
workflow runs when a `v<version>-hoos.<revision>` tag is pushed to this fork.
It verifies security boundaries, builds a portable Java runtime, tests both native
Docker architectures with real AWS CLI calls, publishes the multi-platform aliases,
signs the images and creates the GitHub release. It uses the repository's temporary
`GITHUB_TOKEN`, with no Docker Hub credentials required.

Before a new release, update the pinned version in the installation examples and
`docker-compose.published.yml`, then tag the reviewed commit. Never reuse a published
version for changed code. New GHCR packages must be made public in GitHub's package
settings before coworkers can pull them without signing in.
