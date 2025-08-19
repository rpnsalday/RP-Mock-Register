# RP-Mock-Register — Desktop App with External Discount Service

This repository is the desktop Swing register UI (the “mock register”).
The discount service is a separate Spring Boot project (external repo) that exposes POST `/discount` and is not the same as the optional log/print server.

By default, the register calls http://localhost:8080/discount for discounts. The Host/Port fields in the app's header configure the optional log server, not the discount service.

## Prerequisites
- Java 17 (or compatible JDK) on your PATH
- Internet access for Gradle to download dependencies (first run)

You do NOT need to install Gradle — the wrapper scripts are included.

## How to Run
Open two terminals:

Terminal 1 — start your discount service from its own repository (follow that repo’s README):
- Typical command (may vary): `./gradlew bootRun` (or `gradlew.bat bootRun` on Windows)
- Ensure it listens on http://localhost:8080 (or set DISCOUNT_URL accordingly)

Terminal 2 — start the desktop app from this repo root:
- macOS/Linux: `./gradlew run`
- Windows: `gradlew.bat run`

A window titled “Mock Register” will open. The app will contact the discount service at http://localhost:8080/discount when discounts are applied.

To override the endpoint, set DISCOUNT_URL env var or -Ddiscount.url:
- macOS/Linux: `DISCOUNT_URL=http://localhost:9090/discount ./gradlew run`
- Windows: `set DISCOUNT_URL=http://localhost:9090/discount && gradlew.bat run`
- Or: `./gradlew run -Ddiscount.url=http://localhost:9090/discount`

## Verifying the server (optional)
With the server running, you can test the endpoint using curl:
```
curl -X POST http://localhost:8080/discount \
  -H 'Content-Type: application/json' \
  -d '{
        "items": [
          {"upc":"0001","description":"Glazed Donut","unitPrice":1.50,"quantity":2},
          {"upc":"0002","description":"Bottled Water","unitPrice":1.00,"quantity":3}
        ]
      }'
```
You should get a JSON response with `discounts`, `totalDiscount`, and `discountedTotal`.

## Notes on Discounts
- Example rules implemented in the service:
  - Donut BOGO (buy one, get one): one free for every pair of donuts.
  - 10% off beverages (water/soda/etc.), capped at $5 per basket.
- Non-discountable categories like lottery/fuel/tobacco are ignored by percentage rules.

## Troubleshooting
- Port already in use: If something else uses port 8080, stop it or change the server port in the service, and update this app’s endpoint via DISCOUNT_URL or -Ddiscount.url.
- Java version: Ensure `java -version` shows 17.x. If not, switch your JDK.
- First run is slow: Gradle will download dependencies.
- Firewall/antivirus: Ensure localhost connections to your chosen port are allowed.

## Build Tasks Reference
- Run desktop app: `./gradlew run`
- Auto rebuild and re-run on save (continuous mode):
  - Preferred: `./gradlew run --continuous`
  - Aliases: `./gradlew dev --continuous` or `./gradlew runOnSave --continuous`

Notes:
- Keep the Gradle command running in your terminal; it will watch for changes and re-run when you save files under `src/main` or `src/resources`.
- If you change dependencies in `build.gradle`, stop and restart the command.

That’s it — keep the service running in one terminal and the register app in another.

## Redirecting Console Output to a Server Socket (Optional)
You can redirect everything this app prints (System.out and System.err) to a remote TCP server, e.g., the sample SocketPrintServer provided in your other project.

Configuration (use environment variables or -D system properties):
- LOG_SERVER_HOST or -Dlog.server.host
- LOG_SERVER_PORT or -Dlog.server.port
- LOG_TEE_LOCAL or -Dlog.tee.local (optional) — set to true to also print locally; default is false.

Examples:
- Start your print server (example port 5050) in a separate terminal.
- macOS/Linux:
  - `LOG_SERVER_HOST=192.168.8.197 LOG_SERVER_PORT=6060 ./gradlew run`
  - With local tee: `LOG_SERVER_HOST=192.168.8.197 LOG_SERVER_PORT=6060 LOG_TEE_LOCAL=true ./gradlew run`
- Windows (PowerShell):
  - `$env:LOG_SERVER_HOST='192.168.8.197'; $env:LOG_SERVER_PORT='6060'; ./gradlew.bat run`

If the app cannot connect to the server, it will keep logging to the local console and print a warning.

You can also configure this from the UI:
- Use the Host and Port fields at the top of the window and click "Connect Log Server".
- This connects System.out and System.err to your log server. Set LOG_TEE_LOCAL=true if you want to also keep local console output.
