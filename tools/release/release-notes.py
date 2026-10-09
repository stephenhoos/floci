#!/usr/bin/env python3
import subprocess
import sys

version = sys.argv[1]
commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
print(f"""Ready-to-run builds of Stephen Hoos's security-hardened Floci fork, based on upstream 2.2.0.

Source: [{commit[:12]}](https://github.com/stephenhoos/floci/commit/{commit}).

### Docker (same installation flow as upstream)

```bash
docker run -d --name floci -p 127.0.0.1:4566:4566 ghcr.io/stephenhoos/floci:{version}
```

Both Linux amd64 and arm64 are available. `latest` tracks the latest fork release.
The JVM image includes Java 25, AWS CLI, `awslocal`, Python and boto3; `-compat` aliases select the same image.
Allow up to two minutes for a socket-free first start. Use the attached `compose.yaml` for Docker-backed workloads:

```bash
docker compose up -d
```

The official Floci CLI can select this image with `floci start --detach --image ghcr.io/stephenhoos/floci:{version}`.

Wait for `curl -f http://localhost:4566/_floci/health` to succeed before AWS calls.
For Docker-backed workloads on Colima, prefer Compose with the VM's default `/var/run/docker.sock`.

### Java download (no compilation)

Download `floci-{version}-jvm.tar.gz` or `.zip`, extract it, and run `./run.sh` on macOS/Linux or `run.cmd` on Windows with Java 25 installed.
Keep the entire extracted directory: `quarkus-run.jar` needs the accompanying libraries.

### Docker archive (registry-independent installation)

Download the `linux-amd64` Docker archive for Intel/AMD, or `linux-arm64` for Apple Silicon/ARM.
Run `docker load -i <downloaded-file>` and then the Docker command above with the pinned version. No source build is required.

### AWS tools

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
aws s3 mb s3://my-bucket
```

Verify downloads with `sha256sum -c SHA256SUMS` (Linux) or `shasum -a 256 -c SHA256SUMS` (macOS; download all listed files or verify only your selected entry).

Browser request protection, loopback host port bindings, credential filtering and privileged workload opt-in remain enabled.
The Compose workload profile grants access to your Docker daemon, as upstream does. Use it only for trusted development workloads.
See the fork's [installation instructions](https://github.com/stephenhoos/floci/blob/{commit}/docs/getting-started/installation.md)
and [security settings](https://github.com/stephenhoos/floci/blob/{commit}/docs/configuration/security-hardening.md).

These builds use the JVM. Upstream native-image startup and memory benchmarks do not apply.
""")
