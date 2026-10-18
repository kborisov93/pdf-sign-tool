# PDF Sign Tool

A Windows tray application that signs PDF documents with the user's smart card on behalf of web applications.

A web page sends a PDF and the rectangle for the visible signature to a local REST API. PDF Sign Tool shows the
request (requesting website, document, signature position), the user picks a certificate and enters the smart card
PIN, and the page polls the request and downloads the signed PDF.

- Kotlin, Swing with [FlatLaf](https://www.formdev.com/flatlaf/) (light or dark, like the Windows apps),
  [iText 9](https://itextpdf.com/) (signing), [Ktor](https://ktor.io/) (REST API)
- PAdES signatures (`ETSI.CAdES.detached`, SHA-256), RSA and ECDSA keys, optional RFC 3161 timestamp
- Incremental updates: existing signatures stay valid
- Smart cards through the Windows certificate store (minidriver) or a PKCS#11 driver
- Windows installer built with jpackage and WiX

## Building

You need a JDK 25 to run Gradle. For packaging, Gradle downloads Eclipse Temurin 25 by itself: its runtime is what
ships inside the installer.

| Command | Result |
|---|---|
| `gradlew run` | Starts the tray app from the sources |
| `gradlew test` | Runs the tests (software keys, no smart card needed) |
| `gradlew jpackageImage` | `build/jpackage/image/PDF Sign Tool/`: a self-contained app folder that runs without installing |
| `gradlew jpackageInstaller` | `build/jpackage/installer/PDF Sign Tool-1.1.0.msi` |
| `gradlew jpackageInstaller -PinstallerType=exe` | A setup `.exe` instead of the `.msi` |
| `gradlew jpackageInstaller -PperUserInstall=true` | Installs into `%LOCALAPPDATA%`, without administrator rights |

The installer step needs [WiX Toolset](https://wixtoolset.org/) v3, v4 or v5. WiX 5 installs as a .NET tool:

```
dotnet tool install --global wix --version 5.0.2
wix extension add -g WixToolset.Util.wixext/5.0.2
wix extension add -g WixToolset.UI.wixext/5.0.2
```

The installer adds a Start menu entry and (optionally) a desktop shortcut. Upgrades work in place as long as
`windowsUpgradeUuid` in `build.gradle.kts` stays the same; raise `version` for every release. The bundled runtime only
contains the modules listed in `runtimeModules`. If a new dependency needs another JDK module, add it there.

### Releases

The GitHub repository mirrors GitLab and builds the release files with GitHub Actions
(`.github/workflows/release.yml`). Raise `version` in `build.gradle.kts` and push a matching tag, e.g. `v1.2.0`: the
workflow runs the tests and attaches `PDF-Sign-Tool-1.2.0.msi` and `PDF-Sign-Tool-1.2.0-portable.zip` (the app folder
from `jpackageImage`) to a GitHub Release. It stops if the tag and `version` differ. Running the workflow by hand
(Actions → Release → Run workflow) builds the same files as a workflow artifact without releasing them.

## Using it from a web application

The API listens on `http://127.0.0.1:18765` only (the port can be changed in Settings). Browsers allow pages served
over HTTPS to call `http://127.0.0.1`. Chrome and Edge may ask the user once to allow the site to access apps on
this device.

```js
const API = "http://127.0.0.1:18765/api/v1";

async function signPdf(pdfBlob, fileName) {
  const form = new FormData();
  form.append("file", pdfBlob, fileName);
  form.append("page", "1");          // 1 = first page
  form.append("x", "36");            // rectangle in PDF points (72 pt = 1 inch)
  form.append("y", "36");
  form.append("width", "200");
  form.append("height", "60");
  form.append("reason", "Contract approval");

  let response = await fetch(`${API}/sign-requests`, { method: "POST", body: form });
  let request = await response.json();
  if (!response.ok) throw new Error(request.message);

  // The user now sees the request in PDF Sign Tool. Poll until it is finished.
  while (request.status === "PENDING" || request.status === "SIGNING") {
    await new Promise((resolve) => setTimeout(resolve, 1000));
    request = await (await fetch(request.statusUrl)).json();
  }
  if (request.status !== "SIGNED") throw new Error(request.error ?? `Signing ${request.status.toLowerCase()}`);

  const signedPdf = await (await fetch(request.documentUrl)).blob();
  await fetch(request.statusUrl, { method: "DELETE" }); // optional: discard it from the app right away
  return signedPdf;
}
```

Open `http://127.0.0.1:18765/` (tray menu → *Open test page*) for an interactive test page.

### Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/status` | Detects whether the app is running: `{application, version, apiVersion, activeRequests}` |
| `POST` | `/api/v1/sign-requests` | Creates a request (multipart/form-data, see below). `202 Accepted` with the request and a `Location` header |
| `GET` | `/api/v1/sign-requests/{id}` | The request and its status |
| `GET` | `/api/v1/sign-requests/{id}/document` | The signed PDF (`application/pdf`, `Content-Disposition: attachment`); `409` until the status is `SIGNED` |
| `DELETE` | `/api/v1/sign-requests/{id}` | Cancels a pending request (its window closes) or discards a finished one; `409` while signing |

### `POST /api/v1/sign-requests` fields

| Field | Required | Description |
|---|---|---|
| `file` | yes | The PDF (up to 100 MB). Encrypted PDFs are rejected |
| `page` | yes | Page for the visible signature, 1-based |
| `x`, `y`, `width`, `height` | yes | Signature rectangle in PDF points |
| `origin` | no | `bottom-left` (default) or `top-left`, see below |
| `reason`, `location`, `contactInfo` | no | Stored in the signature; reason and location are also shown in its appearance |
| `fieldName` | no | Name of the new signature field (letters, digits, space, `-`, `_`). Default: `Signature1`, `Signature2`, … |

**Coordinates.** With `origin=bottom-left`, `x`/`y` are PDF user-space coordinates: origin at the bottom-left corner of
the unrotated page, y pointing up. With `origin=top-left`, the rectangle is measured on the page *as a viewer shows
it*: origin at the top-left corner of the visible area (crop box), y pointing down, page rotation applied. This is
the coordinate system of a [PDF.js](https://mozilla.github.io/pdf.js/) viewport at scale 1, so a rectangle the user
draws over a rendered page converts by dividing by the render scale.

### Request object

```json
{
  "id": "de04b1da-f974-4ff9-a81c-44598c36d01a",
  "status": "SIGNED",
  "fileName": "contract.pdf",
  "pageCount": 2,
  "createdAt": "2026-10-15T16:53:48.223Z",
  "updatedAt": "2026-10-15T16:54:02.871Z",
  "statusUrl": "http://127.0.0.1:18765/api/v1/sign-requests/de04b1da-…",
  "documentUrl": "http://127.0.0.1:18765/api/v1/sign-requests/de04b1da-…/document",
  "signer": { "commonName": "…", "subject": "CN=…", "issuer": "CN=…", "serialNumber": "1A2B…" }
}
```

| Status | Meaning |
|---|---|
| `PENDING` | Waiting for the user (or waiting in line behind another request) |
| `SIGNING` | Approved; signing is running and the smart card may be asking for the PIN |
| `SIGNED` | Done: `documentUrl` and `signer` are set |
| `CANCELLED` | Rejected by the user, or cancelled with `DELETE` |
| `FAILED` | Signing failed and the user gave up; see `error` |

If signing fails (wrong PIN, card removed), the window shows the error and the request goes back to `PENDING`. The
user can try again, or give up, which turns the request into `FAILED`. Finished requests are kept for 30 minutes.

Errors use the matching HTTP status and a body like `{"error": "invalid_request", "message": "page must be between 1 and 2"}`:
`invalid_request` (400), `origin_not_allowed` / `forbidden_host` (403), `not_found` (404),
`not_signed` / `signing_in_progress` (409), `document_too_large` (413), `unsupported_media_type` (415),
`too_many_requests` (429, more than 20 active requests).

## Smart cards and certificates

- **Windows certificate store** (default). Cards with a Windows minidriver, which covers most national eID and
  qualified signature cards, register their certificates in the user's personal store automatically. Windows itself
  asks for the PIN when signing. The list shows CA-issued certificates that permit document signing. *Show all
  certificates* also lists self-signed, TLS and device certificates, e.g. for testing with a self-signed `.pfx`
  imported into the store.
- **PKCS#11** (optional). For cards that only ship a PKCS#11 driver: set the driver DLL in Settings. The window then
  offers this source and asks for the PIN itself.

## Settings and files

Tray icon → *Settings…* (or double-click the icon):

| Setting | Default | |
|---|---|---|
| API port | `18765` | |
| Allowed websites | `*` | One origin per line. `*` allows every site, `https://*.example.com` its subdomains. **Restrict this in production.** |
| Timestamp server | empty | RFC 3161 TSA URL; adds a trusted signing time (PAdES B-T) |
| PKCS#11 library | empty | See above |
| Start with Windows | on | Enabled on the first start of the installed app (`HKCU\…\CurrentVersion\Run`) |

- Settings: `%APPDATA%\PdfSignTool\settings.properties`
- Logs: `%LOCALAPPDATA%\PdfSignTool\logs\` (tray → *Open log folder*)

## Security model

A local signing service can be called by any web page the user visits, so it relies on these safeguards:

- **Every signature needs the user's approval and PIN.** The window names the requesting website (its `Origin`)
  and shows the document, which can be opened in the PDF reader before signing.
- **Allowed websites:** requests from other origins get `403`, and CORS headers are only sent to allowed origins.
- **Loopback only:** the server binds to `127.0.0.1`. Requests whose `Host` is not `127.0.0.1`/`localhost` are refused,
  which defeats DNS rebinding.
- **Per-origin requests:** request ids are random UUIDs, and a page from another origin gets `404` for them.
- Text from the request is never rendered as HTML in the window. Requests are limited in size and number.

## License

iText is licensed under the **AGPL-3.0** (or a commercial license from Apryse). Distributing this application,
including inside an organisation's installer, requires complying with the AGPL (publishing the complete source of
the application under the AGPL) or buying an iText commercial license. Ktor, Kotlin, Bouncy Castle and SLF4J use
permissive licenses.
