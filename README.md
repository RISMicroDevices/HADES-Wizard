# HADES-Wizard

HADES-Wizard is a standalone native Eclipse application for declarative configuration providers. It uses SWT/JFace directly through the Equinox application API: there is no IDE workspace, perspective, `org.eclipse.ui` dependency, browser, HTTP server, Swing, or JavaFX.

## Build and test

Requirements on Linux x86_64: `bash`, `curl`, `tar`, and `sha256sum`. The build bootstraps everything else inside this directory. No global Java or Maven installation is changed.

```sh
./scripts/bootstrap-jdk.sh
export JAVA_HOME="$PWD/.toolchain/jdk-21.0.9+10-linux-x64"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw clean verify
```

`./mvnw` defaults both its wrapper home and Maven repository to `.m2/`; `.mvn/maven.config` fixes the repository at `.m2/repository`. It also disables second-level p2 mirror discovery so artifacts come directly from the declared immutable repositories. The equivalent Windows entry point is `mvnw.cmd` when `JAVA_HOME` names a Java 21 JDK.

The reactor compiles for Java 21, runs the core tests, runs the SWTBot native-dialog tests on the current display, and materializes Linux and Windows products. On a headless Linux host, provide a real or virtual X11/Wayland display before running the UI tests. WSLg works through its normal `DISPLAY`/`WAYLAND_DISPLAY` environment. No Verdi display is used.

To repeat the complete product build:

```sh
./scripts/build-products.sh
```

To launch the unpacked Linux product after a successful build:

```sh
./scripts/launch-linux.sh
```

On WSL, select the verified X11 display path when no backend has been chosen: `GDK_BACKEND=x11 ./scripts/launch-linux.sh`. The HADES-SRAM `hades-sram-gen gui` launcher applies this WSL-only default automatically and preserves an explicit `GDK_BACKEND` selection.

Unpacked products are under `products/cn.rismd.hades.wizard.product/target/products/cn.rismd.hades.wizard.product/`:

- `linux/gtk/x86_64/hades-wizard`
- `win32/win32/x86_64/hades-wizard.exe`

Both products include the pinned JustJ Java 21 runtime. Use `scripts/verify-windows.ps1` for actual native Windows build, UI, and packaged-GUI verification rather than relying on cross-build success.

## Application

A backend provider opens the five-page native wizard on startup. File New/Open/Save/Save As/Exit and Edit Configuration preserve unsaved-change protection. New configurations start in Automatic addressing with automatic timing; opened configurations start in Manual addressing with manual timing. Structural JSON failures leave the current session untouched, while decoded semantic-invalid configurations open for repair.

Application arguments:

```text
--provider PATH   Descriptor JSON or its source tools directory
--config PATH     Configuration to open through backend analyze_text
--output DIR      Initial generation directory
--python EXEC     Preferred Python 3.11+ executable
--fixture         Explicit two-page development fixture; no backend
```

Without `--provider`, the application loads `<install>/provider/provider.json` when present. A source launch can use `--provider /path/to/HADES-SRAM/tools`. If neither an installed nor explicit provider exists, the small fixture remains the development fallback.

## Provider descriptor and backend API

Provider schema/protocol version 1 contains reverse-domain `id`, semantic `version`, optional `vlnv`, `display.name/description/help`, pages, fields, reports, and optional `backend` and `addressing` objects. Fields support `STRING`, `MULTILINE`, `INTEGER`, `BOOLEAN`, and `CHOICE`, with labels, help, required/default metadata, and string choices. Backend pages declare `kind` as `integration`, `ports`, `clock`, `maintenance`, or `review`. `backend.entryPoint` is a relative launcher path resolved from the descriptor directory; it is not a shell command. Addressing declares automatic/manual labels and base/depth/size field names. Numeric bounds and defaults come from backend `schema`, not descriptor duplication.

The maintained HADES-SRAM descriptor is `/home/kumon/Projects/HADES-SRAM/tools/hades_sram_wizard.json`. `BackendService` discovers Python (explicit preference, `python3`, `python`, then Windows `py -3`), verifies 3.11+, launches `[python, launcher, protocol]` using `ProcessBuilder`, and performs the protocol/schema/version/VLNV handshake. It provides synchronized monotonic request IDs, 2 MiB request and 32 MiB response limits, strict UTF-8 framing, bounded stderr draining, timeouts, cancellation, malformed/late-response rejection, and cleanup of only its owned child. `PYTHONDONTWRITEBYTECODE=1` prevents backend cache writes.

`BackendController` executes service calls off the SWT thread and notifies listeners through `Display.asyncExec`. Immutable revision snapshots discard stale responses and preview data. Backend `allocate`, `apply_depths`, `derive_periods`, `analyze`, `preview`, `save`, and `generate` own all sizing, normalization, timing, and validation; Java does not duplicate LC arithmetic. Backend module metadata feeds the shared preview. Artifact preview is in-memory and invalidated on configuration edits or full-copy changes; that generation option does not enter canonical JSON. Finish revalidates the current snapshot and succeeds only for `outcome=generated` and `status=0`, then shows **Generation Complete**. It never launches Vivado or hardware.

The original fixture APIs (`WizardSession`, `ProviderWizard`, `WizardWindow`, `ConfigurationStore`) remain available. Backend applications use `BackendWizard`, `BackendWindow`, and `BackendController`.

## Shared module-port preview

The `cn.rismd.hades.wizard.modulepreview` bundle is a reusable, provider-neutral SWT/JFace library. Its Java model describes module/interface groups and signals; `ModulePortPreview` renders them without knowing HADES-SRAM field names, backend transport, or lane arithmetic. The backend supplies the module metadata, and the UI performs only JSON-to-model conversion.

The persistent left-hand component appears throughout the backend configuration wizard and main window. It includes a double-buffered diagram with expandable signal groups, drag-to-pan, bounded Ctrl+wheel/keyboard zoom, **Zoom In**, **Zoom Out**, **Fit**, and **Reset**, plus a synchronized accessible tree. Expansion and view state survive model refreshes. Empty, loading, and invalid states keep the preview visible without presenting stale data as current. HADES-SRAM uses separate groups for every SRAM port, AXI4, AXI4-Lite or static I/O, clocks/resets, and maintenance.

## Real SRAM verification and staging

The normal `./mvnw clean verify` remains self-contained and runs fixture and failure-injection tests. Run the real source-backend profile explicitly with:

```sh
./mvnw clean verify -Phades-sram-integration \
  -Dhades.sram.descriptor=/home/kumon/Projects/HADES-SRAM/tools/hades_sram_wizard.json \
  -Dhades.sram.python=python3
```

The profile runs real core operations and SWTBot workflows without skipping them. Screenshots are written to `tests/cn.rismd.hades.wizard.ui.tests/target/screenshots/`.

After that build, stage the descriptor and backend into both generated products and create separate archives and checksums:

```sh
./scripts/package-provider.sh /home/kumon/Projects/HADES-SRAM/tools/hades_sram_wizard.json
```

The script repeats the standard clean verification, then writes disposable `provider/` directories containing `provider.json`, `hades-sram-gen`, `hades_sram_gen/`, and the complete integration-source payload used by default full-copy generation. Staged products remain under the normal product path; archives and `SHA256SUMS` are under `products/cn.rismd.hades.wizard.product/target/provider-products/`. These copies are packaging output only and are never installed into the SRAM IP. `scripts/package-provider.ps1` provides the same packaging flow for a native Windows checkout; its `-SkipBuild` option is only for an immediately preceding successful build in that same tree.

### Native Windows verification

Use an interactive Windows x86_64 session with PowerShell and Python 3.11+ available as `python3` (or pass `-Python`). The pinned Temurin JDK and Maven wrapper remain project-local; no global Java or Maven installation is required. From the Windows checkout, run:

```powershell
.\scripts\verify-windows.ps1 `
  -Descriptor C:\src\HADES-SRAM\tools\hades_sram_wizard.json
```

The script bootstraps and verifies the pinned Windows JDK, then creates a unique `hw-*` directory directly under the current user's profile. This clearly owned Windows-local location and short subdirectory names keep the native Eclipse launcher and bundled JVM below the traditional Windows path-length limit. The script copies the maintained source, Maven cache, JDK, descriptor, and backend there without mirroring or deleting an existing directory. The actual build is the ordinary `mvnw.cmd clean verify -Phades-sram-integration` with local `-Dhades.sram.descriptor` and `-Dhades.sram.python` arguments. There is no UNC lifecycle extension, Java agent, mapped-drive repository, or lock workaround.

After the build, the entry point checks the real core and SWTBot reports for zero failures, errors, and skips; confirms both materialized products and their JustJ runtimes; stages the default provider into both products; and runs `hades-wizard.exe` through native UI Automation and Win32 window messages. The packaged-GUI checks cover all five Automatic pages and a mixed Manual configuration with base, width, and depth edits. They exercise the shared preview controls and completion dialog, generate a complete-copy Automatic output and a generated-only Manual output, and compare every nested artifact and manifest entry against direct-backend references.

Logs, Surefire reports, screenshots, parity records, runtime evidence, and provider archives are copied to `.local/windows-<timestamp>-<id>/`. Window screenshots use desktop capture when available and fall back to native `PrintWindow` capture with blank-frame validation; no visual check is skipped. The unique Windows-local staging path and product locations are recorded in `stage-info.txt` for inspection. GUI execution requires the current user's interactive desktop and must not run concurrently with another UI test session.

## Pinned build inputs

- Eclipse Platform 4.41 (2026-09): `https://download.eclipse.org/eclipse/updates/4.41/R-4.41-202608281142/`
- Tycho 5.0.4, resolved by Maven; Java source/release 21
- SWTBot 4.3.0: `https://download.eclipse.org/technology/swtbot/releases/4.3.0/`
- JustJ OpenJDK 21.0.9 (`21.0.9.v20251105-0741`): `https://mirror1.hs-esslingen.de/Mirrors/eclipse/justj/jres/21/updates/release/21.0.9/` (immutable Eclipse mirror)
- Gson 2.13.2, JUnit 4.13.2, Hamcrest 2.2, from Maven Central through the target definition
- Maven Wrapper 3.3.4 and Maven 3.9.11, with SHA-256 values in `.mvn/wrapper/maven-wrapper.properties`
- Temurin JDK 21.0.9+10: `https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.9%2B10/OpenJDK21U-jdk_x64_linux_hotspot_21.0.9_10.tar.gz`, SHA-256 `810d3773df7e0d6c4394e4e244b264c8b30e0b05a0acf542d065fd78a6b65c2f`

There are no `latest` repository URLs. Downloaded toolchains, Maven caches, targets, and products are ignored by Git. See `THIRD_PARTY_NOTICES.txt` for dependency license and notice sources.
