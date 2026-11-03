# Private-host deployment

This deployment targets **one owner-managed host on a private network**, with Nginx as the only published service. Nginx terminates TLS and applies per-client-IP API rate limits. The CodeBridge app and its runtime JAR dependencies are packaged into an image, reachable only on a private Docker network, required to use an API bearer token, and limited by container CPU, memory, process, filesystem, and privilege controls.

This is a restricted deployment profile, not a public multi-tenant service. Restrict the host firewall to the intended client network. Review and pin image digests before a production release.

## Prerequisites

- Docker Engine with Docker Compose v2.
- A TLS certificate and private key for the hostname clients will use. The files must be named `fullchain.pem` and `privkey.pem`.
- Java 21 is not required on the host; it is included in the app image.

The app container does **not** receive the host Docker socket or Docker CLI. Therefore Docker-based Clang analysis and run/compare features are unavailable in this profile. C/C++ compiler validation is also unavailable because native compilers are not installed in the app image. Java compile checks use the JDK in the app container. No submitted program is run directly on the app host. If run/compare is required in a future deployment, add a separate isolated worker with a narrowly scoped interface; do not mount `/var/run/docker.sock` into this web service.

## Configure and launch

1. Copy `deploy/.env.production.example` to `deploy/.env.production`.
2. Replace `CODEBRIDGE_AUTH_TOKEN` with a randomly generated secret of at least 16 allowed characters. Keep this file private; it is ignored by Git.
3. Put the certificate and key at `deploy/certs/fullchain.pem` and `deploy/certs/privkey.pem`. The `certs` directory is ignored by Git.
4. Keep `CODEBRIDGE_HTTPS_BIND=127.0.0.1` for local-only access. For private LAN access, set it to the host's private interface address and configure the host firewall to allow only the intended clients. Do not use `0.0.0.0` without an explicit network and firewall review.
5. From the repository root, build and start the stack:

   ```powershell
   docker compose --env-file deploy/.env.production -f deploy/compose.production.yaml up --build -d
   ```

6. Check the container state and the TLS endpoint:

   ```powershell
   docker compose --env-file deploy/.env.production -f deploy/compose.production.yaml ps
   curl.exe --cacert path\to\trusted-ca.pem https://localhost:8443/api/health
   ```

   For a trusted public certificate, use its normal trust chain rather than `--cacert`. The UI is at `https://localhost:8443/`. The API health response is public and reports that authentication is enabled; conversion and analysis requests require the token in `Authorization: Bearer <token>`.

To stop the stack:

```powershell
docker compose --env-file deploy/.env.production -f deploy/compose.production.yaml down
```

## Boundaries and operations

- The Compose profile publishes only Nginx, on loopback port 8443 by default. The application has no published host port.
- Nginx accepts TLS 1.2/1.3 and limits `/api/convert` to 30 requests/minute with a burst of 10 and `/api/analyze` to 10 requests/minute with a burst of 4 per client IP. Excess requests receive HTTP 429 JSON. These limits are an initial single-host policy; adjust them after observing real usage.
- Nginx caps request bodies at 1 MiB; the API independently applies its own size limit.
- The app is non-root, read-only except a bounded `/tmp`, drops Linux capabilities, prohibits privilege escalation, and is limited to 1 GiB RAM, 1.5 CPU, and 128 processes. Nginx has similar read-only, capability, and resource limits.
- The app network is Docker-internal; only the Nginx proxy joins the edge network. There is no Docker socket mount.
- Rotate `CODEBRIDGE_AUTH_TOKEN` by updating the private env file and recreating the app container. Do not commit secrets or certificates.
- API bearer authentication is not a substitute for TLS. `CODEBRIDGE_AUTH_TOKEN` must be set for Compose to start.

This setup does not include automatic certificate renewal, backups, high availability, a managed secret store, or centralized monitoring. Those depend on the selected host and certificate provider and must be arranged operationally.
