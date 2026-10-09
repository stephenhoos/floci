# Security hardening

Floci runs local development workloads and emulates AWS authentication. Keep its APIs, TCP service ports and Docker daemon within a trusted development environment. The `test` credentials, numeric account credentials and IAM enforcement are compatibility features; they do not authenticate access to the emulator process.

## Defaults and migration

The HTTP listener remains on loopback for direct JVM launches. Docker images listen on all interfaces inside their container so port forwarding works; publish the host port as `127.0.0.1:4566:4566`. The supplied Compose file now publishes its service ports on loopback, disables bind-mount hot reload, and leaves the console and TLS disabled until explicitly enabled. Docker ports created through Floci's container builder and the console also default to `127.0.0.1`.

Native SDK and CLI requests retain their AWS protocols and existing emulated credentials. The following intentional changes require configuration when a workflow depends on them:

- Privileged containers require `FLOCI_SECURITY_ALLOW_PRIVILEGED_CONTAINERS=true`. EC2's normal Docker backend, EKS's k3s backend, EC2 block-device helpers and privileged CodeBuild or Lambda launches can need this. Use a disposable, isolated Docker daemon for such workloads.
- Bind-mount Lambda hot reload requires `FLOCI_SERVICES_LAMBDA_HOT_RELOAD_ALLOWED_PATHS` in addition to the enable flag. Approve a dedicated code directory. Paths exposing known Docker sockets are rejected even if approved, and symbolic links visible to Floci are checked for escapes. Remote daemon paths and concurrent changes to host symlinks remain an operator trust boundary.
- Ambient `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` and `AWS_SESSION_TOKEN` values from the server process are no longer passed into launched containers. Known emulated execution-role credentials and numeric owner-account placeholders still work. Explicitly mounted AWS configuration or workload environment credentials continue to grant the access requested by the operator.
- Browser requests require approved hosts and origins. Local emulator hosts and local console origins work by default. Add exact custom browser hosts with `FLOCI_SECURITY_ALLOWED_BROWSER_HOSTS` and custom origins with `FLOCI_SECURITY_EXTRA_CORS_ALLOWED_ORIGINS`. Wildcard CORS configuration does not bypass browser protection. This also applies to browser calls to deployed API Gateway routes.
- To intentionally publish a service container beyond loopback, set `FLOCI_SECURITY_CONTAINER_PUBLISH_HOST` or that service's explicit bind address. The console uses `FLOCI_SERVICES_UI_BIND_ADDRESS`.

## Independent API key

For callers that can supply a custom HTTP header, set `FLOCI_SECURITY_API_KEY` to a random secret of at least 32 characters and send it as `X-Floci-Api-Key`. Comparison is constant-time. A fake AWS access key or an IAM root identity cannot substitute for this key. Only read-only `GET` and `HEAD` health endpoints are public. Approved browser preflights receive a response without dispatching to an integration or state-changing handler.

```bash
export FLOCI_SECURITY_API_KEY=$(openssl rand -hex 32)
docker compose -f docker-compose.hardened.yml up --build
curl -H "X-Floci-Api-Key: $FLOCI_SECURITY_API_KEY" \
  -H 'X-Amz-Target: AmazonSSM.DescribeParameters' \
  -H 'Content-Type: application/x-amz-json-1.1' \
  -d '{}' http://localhost:4566/
```

In the Java SDK, add the header through an `ExecutionInterceptor.modifyHttpRequest` override before signing. Other SDKs need an equivalent request hook. The stock AWS CLI, automatically launched workloads and console do not automatically add this header; browser WebSockets also cannot supply arbitrary headers; leave the key unset for an isolated compatibility environment, or configure an authenticated gateway and caller adapters for these workflows. Keep the key out of source files and logs. HTTP transmits it in plaintext, so use TLS or a protected local transport when requests cross a trust boundary. This HTTP key does not authenticate separate Redis, SQL, Kubernetes or other TCP endpoints.

## Workload and outbound restrictions

Set `FLOCI_SECURITY_ALLOWED_CONTAINER_IMAGES` to comma-separated exact approved image references to restrict workload and helper images. Unset preserves arbitrary-image local development. Prefer immutable `repository@sha256:...` references. A digest resolved from an approved reference remains accepted for ECS launch preparation. Include every required runtime and helper image; denial is intentional if a needed image is omitted. This control does not sandbox code inside an approved image, nor does it prevent already running containers from continuing.

API Gateway HTTP integrations, SNS HTTP subscriptions, EventBridge API destinations and Cognito OIDC calls reject cloud metadata addresses. Their HTTP transport screens DNS results at connection time, including HTTPS, and does not follow redirects. Bodies are bounded to 10 MiB. Set `FLOCI_SECURITY_ALLOW_PRIVATE_OUTBOUND_TARGETS=false` to also reject loopback, private, link-local and other non-public destinations in these clients. It defaults to `true` for local test backends. This setting covers these four clients; it is not a process-wide egress firewall. Workload containers and other integrations need network isolation appropriate to their environment.

The standalone `docker-compose.hardened.yml` is a control-plane environment with an API key, no mounted Docker socket, private outbound targets disabled, and no approved workload images. Docker-backed services intentionally cannot launch in this mode. The normal Compose file retains the Docker socket for local compatibility. Access to that socket permits powerful host operations even when privileged launches are disabled; mount it only in a trusted environment.

## Certificates and data

TLS stays opt-in. Trust the emulator CA only within processes that need it, using `AWS_CA_BUNDLE`, `NODE_EXTRA_CA_CERTS` or an isolated certificate store. Do not install it into shared operating-system trust. Its key remains an emulator signing authority for ACM and IoT compatibility; shortening or restricting it would change those services' behavior. Protect the data directory and CA key, and remove any previously installed root certificate from shared trust stores.

Persisted emulated secrets and workload environment values remain plaintext development data. Use synthetic credentials, restrict directory permissions, and keep that directory out of shared backups and source control. The hardened profile does not turn Floci into a production multi-tenant AWS service.
