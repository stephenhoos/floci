# Installation

These instructions install Stephen Hoos's security-hardened Floci fork. Use the same Docker,
AWS endpoint, credentials and official CLI workflow as upstream, with `ghcr.io/stephenhoos/floci`
as the emulator image. Download ready-to-run builds from [Releases](https://github.com/stephenhoos/floci/releases/latest).

## Docker (recommended)

Install Docker with Compose v2. Both Linux Intel/AMD (`amd64`) and Apple Silicon/ARM (`arm64`)
images are published; Docker selects your architecture automatically.

```bash
docker pull ghcr.io/stephenhoos/floci:latest
docker run -d --name floci -p 127.0.0.1:4566:4566 ghcr.io/stephenhoos/floci:latest
```

No Java installation or compilation is needed. Allow up to two minutes for a first start
without the Docker socket. Check readiness with `curl -f http://localhost:4566/_floci/health`.

### Docker Compose and Docker-backed workloads

Download [compose.yaml](https://github.com/stephenhoos/floci/releases/latest/download/compose.yaml)
into an empty directory, then run:

```bash
docker compose up -d
```

The file uses a pinned fork image, local host ports, a named data volume and a shared
Docker network. It mounts the Docker socket for Lambda, RDS, ECS and other workloads,
as upstream's full development setup does. Run only trusted workloads with this profile.
For a rootless daemon, set `FLOCI_DOCKER_SOCKET` to its Unix socket path as seen by the Docker daemon.
On Colima, keep the default `/var/run/docker.sock`: that path belongs to the Docker VM.
The Mac's `~/.colima/.../docker.sock` is the client's connection endpoint, not the VM's mount source.
For API-only use, the socket-free `docker run` command above is sufficient.

Privileged containers remain disabled. EC2/EKS and other workloads that need privilege
require `FLOCI_SECURITY_ALLOW_PRIVILEGED_CONTAINERS=true`. See
[Security hardening](../configuration/security-hardening.md) for the API key, approved
images, browser protection and other controls.

### Official Floci CLI

Install the upstream CLI and select this fork's image:

```bash
brew install floci-io/floci/floci
floci start --detach --image ghcr.io/stephenhoos/floci:latest
eval "$(floci env)"
```

`--detach` avoids the official CLI's 30-second readiness timeout. Wait until
`curl -f http://localhost:4566/_floci/health` succeeds before running AWS commands.
On Colima, prefer the Compose setup for Docker-backed workloads because the upstream CLI
mounts its client socket path, which may not exist inside the Docker VM. On Linux/Windows,
install the official CLI using its [instructions](https://github.com/floci-io/floci-cli#installation),
then pass the same `--image` option. The CLI's downloadable `floci.jar` is the CLI, not the emulator.

### Image tags

| Tag | Meaning |
|---|---|
| `latest` | Latest fork release |
| `2.2.0-hoos.1` | Pinned release based on upstream 2.2.0 |
| `latest-compat`, `2.2.0-hoos.1-compat` | Aliases, AWS tools are included in every JVM image |

The images include Java 25, AWS CLI, `awslocal`, Python and boto3. These are JVM builds;
upstream native-image startup and memory benchmarks do not apply. This fork does not
publish nightly or native baseline tags. See [Docker Images](../configuration/docker-images.md).

## Configure AWS tools

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
aws s3 mb s3://my-bucket
aws s3 ls
```

Existing SDK, Terraform, CDK and Testcontainers configurations use the same endpoint.
Use dummy AWS credentials for local development.

## Download and run with Java (no compilation)

Install Java 25. Download the `.tar.gz` or `.zip` JVM archive and `SHA256SUMS` from
[Releases](https://github.com/stephenhoos/floci/releases/latest). On macOS/Linux:

```bash
tar -xzf floci-2.2.0-hoos.1-jvm.tar.gz
cd floci-2.2.0-hoos.1
./run.sh
```

On Windows, extract the ZIP and run `run.cmd`. Set `JAVA_HOME` if your default Java
installation is a different version. Keep the entire extracted folder: `quarkus-run.jar`
needs its accompanying libraries. Installation instructions, security settings, the MIT
license and source commit details are included.

The API uses `http://localhost:4566`. Docker-backed workloads additionally require a
reachable Docker daemon; the Docker Compose installation configures that for you.

## Downloadable Docker archive

The release includes a ready-made Docker archive for each architecture. Download
`floci-2.2.0-hoos.1-linux-amd64-docker.tar.gz` for Intel/AMD or
`floci-2.2.0-hoos.1-linux-arm64-docker.tar.gz` for Apple Silicon/ARM, then run:

```bash
docker load -i floci-2.2.0-hoos.1-linux-arm64-docker.tar.gz
docker run -d --name floci -p 127.0.0.1:4566:4566 ghcr.io/stephenhoos/floci:2.2.0-hoos.1
```

Use the `amd64` filename on Intel/AMD. The loaded image uses the same pinned tag as the
registry image, so the Compose file also works after loading it.

## Verify downloads

Compare your selected file against its entry in `SHA256SUMS`:

```bash
shasum -a 256 floci-2.2.0-hoos.1-jvm.tar.gz
```

If all listed assets are downloaded, run `sha256sum -c SHA256SUMS` on Linux or
`shasum -a 256 -c SHA256SUMS` on macOS. See [Docker Images](../configuration/docker-images.md)
for container signature verification.

## Build from source

Java 25 and the included Maven wrapper are sufficient for a JVM build:

```bash
git clone https://github.com/stephenhoos/floci.git
cd floci
./mvnw package -DskipTests
java --enable-native-access=ALL-UNNAMED -jar target/quarkus-app/quarkus-run.jar
```

For a native image built locally, run `make native`, `make native-image`, then
`make native-up`. Native builds are separate from the published JVM downloads.
