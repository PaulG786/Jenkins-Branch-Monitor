# Konzept: IntelliJ-Plugin „Jenkins Branch Monitor"

> Arbeitstitel: **Jenkins Branch Monitor** (Alternativen: *BuildBubbles*, *BranchBeacon*)
> Status: Konzept / Draft — Stand 2026-11-12
> Ziel: Veröffentlichung auf dem JetBrains Marketplace (allgemein anwendbar, nicht projektspezifisch)

---

## 1. Problemstellung

Wer an mehreren Repositories und Branches parallel arbeitet (Feature-Branches,
Release-/LTS-Branches wie `release/1.4`, Main), muss nach jedem Push manuell in
Jenkins navigieren, den richtigen Job und Branch suchen und den Build beobachten.
Das kostet Zeit, unterbricht den Flow und Build-Fehler fallen oft erst spät auf.

**Kernidee:** Das Plugin erkennt automatisch, *welche* Repos und Branches im
aktuellen IntelliJ-Projekt ausgecheckt sind, findet die zugehörigen Jenkins-Jobs
und zeigt deren Build-Status minimalistisch und permanent sichtbar in der IDE an.

---

## 2. Zielbild (UX)

### 2.1 Haupt-UI: angepinntes Status-Panel

Ein kleines, minimalistisches Fenster — standardmäßig **unten rechts angedockt**
(Tool Window mit Anchor `BOTTOM`/`RIGHT`),
optional auch als frei schwebendes Fenster („Float"-Mode der Tool-Window-API).

Pro überwachtem Branch eine Zeile:

```
● shop-backend/release/1.4        #4711  2m 13s
● shop-backend/feature/ABC-123    #0042  läuft… 67%
● shop-config/main            #1337  vor 18 min
```

**Die Kugel (Status-Indikator):**

| Farbe | Bedeutung | Zusatz |
|---|---|---|
| 🔵 Blau (pulsierend/animiert) | Build läuft gerade | optional Fortschrittsring (geschätzte Restzeit aus Jenkins `estimatedDuration`) |
| 🟢 Grün | Letzter Build erfolgreich | |
| 🔴 Rot | Letzter Build fehlgeschlagen | |
| 🟡 Gelb | Unstable (Tests fehlgeschlagen, Build ok) | Jenkins-Status `UNSTABLE` |
| ⚪ Grau | Kein Build gefunden / Job unbekannt / abgebrochen (`ABORTED`) | |
| ⚫ Durchgestrichen/gedimmt | Jenkins nicht erreichbar | Tooltip mit Fehlergrund |

**Format der Beschriftung:** `<repo-name>/<branch>` — z. B.
`shop-web/release/1.4`. Repo-Name und Branch sind getrennt
stylebar (Branch etwas gedimmt), lange Namen werden in der Mitte gekürzt
(`shop…impl/release/1.4`).

**Interaktionen pro Zeile:**

- **Klick** → öffnet die Build-Seite im Browser (oder konfigurierbar: Console-Log).
- **Hover/Tooltip** → Job-URL, Build-Nummer, Dauer, Trigger (Commit/User), letzte Commit-Message.
- **Kontextmenü** → „Build jetzt starten", „Console-Log anzeigen", „Zeile anpinnen",
  „Nicht mehr überwachen", „In Jenkins öffnen".
- **Rechts oben im Panel:** Refresh-Button, Filter (nur rote zeigen), Zahnrad → Settings.

### 2.2 Status-Bar-Widget (sekundär)

Zusätzlich ein kompaktes Widget in der IDE-Statusleiste:
eine aggregierte Kugel (schlechtester Status aller überwachten Builds) + Zähler,
z. B. `● 2/5`. Klick öffnet das Tool Window. So hat man den Status auch, wenn das
Panel zugeklappt ist.

### 2.3 Notifications

- Build wird **rot** → IntelliJ-Notification (Balloon), optional mit Sticky-Option.
- Build wird nach rot wieder **grün** → „Fixed"-Notification.
- Alles abschaltbar bzw. pro Severity konfigurierbar (none / balloon / sticky).

---

## 3. Automatische Erkennung (das Herzstück)

### 3.1 Was wird beobachtet?

1. **Repos ermitteln:** Über die `git4idea`-API (`GitRepositoryManager`) alle
   Git-Roots des aktuellen Projekts auflisten — Multi-Repo-Projekte (wie
   Multi-Repo-Workspaces mit 10+ Repos) werden damit automatisch unterstützt.
2. **Branch ermitteln:** Pro Repo den aktuell ausgecheckten Branch via
   `GitRepository.currentBranch`. Branch-Wechsel werden über den
   `GitRepositoryChangeListener` live erkannt → Monitor-Liste passt sich sofort an.
3. **Zusätzlich (optional):** Branches, auf die kürzlich gepusht wurde
   (via `GitPushListener`), bleiben für konfigurierbare Zeit (z. B. 2 h) in der
   Beobachtung — auch nach Branch-Wechsel. Genau der Use-Case „ich hab in 3 Branches
   committet und will alle Builds sehen".

### 3.2 Mapping Repo+Branch → Jenkins-Job

Das ist die kritische Abstraktion für allgemeine Anwendbarkeit. Drei Stufen:

**Stufe 1 — Multibranch-Pipeline-Autodiscovery (Standardfall):**
Jenkins-Multibranch-Jobs haben pro Branch einen Unterjob
(`job/<name>/job/<url-encoded-branch>`). Das Plugin durchsucht den Jenkins-Server
(`/api/json?tree=jobs[name,url,jobs[name]]`, rekursiv mit Tiefenlimit) und matcht:

- Jobs der Klasse `WorkflowMultiBranchProject` / `OrganizationFolder`,
- deren SCM-URL (`scm.userRemoteConfigs.url`) gegen die `origin`-Remote-URL des
  lokalen Repos (normalisiert: ssh/https-Äquivalenz, `.git`-Suffix, Case).

Treffer werden gecacht (pro Remote-URL), damit die Discovery nicht bei jedem
Poll läuft.

**Stufe 2 — Konventionsbasiertes Matching:**
Falls keine SCM-URL abfragbar (Rechte) → Fallback auf Namens-Heuristik:
Jobname == Repo-Name (konfigurierbares Template, z. B. `{repo}`,
`{repo}-{branch}`, `builds/{repo}`). Branch-Namen werden URL-encodiert
(`release/1.4` → `release%2F1.4`).

**Stufe 3 — Manuelles Mapping (Settings):**
Tabelle `Remote-URL-Pattern → Job-Pfad-Template` für alle Sonderfälle
(Freestyle-Jobs mit Branch-Parameter, klassische Jobs pro Branch etc.).
Regex-Capture-Groups erlaubt:
`.*?/(?<repo>[^/]+)\.git → mycompany/{repo}/{branch}`.

### 3.3 Status-Abfrage

- **Polling** der Branch-Jobs: `GET <job>/api/json?tree=lastBuild[number,building,result,timestamp,estimatedDuration,duration,url]`
  — schlanke `tree`-Queries, kein Full-Fetch.
- Intervall konfigurierbar (Default 30 s; laufende Builds 10 s, idle 60 s — adaptiv).
- Optional später: **Push statt Poll** via Jenkins-CLI-SSE / WebSocket
  (Jenkins ≥ 2.4x `/sse-gateway`), wo verfügbar — Polling bleibt Fallback.
- Alle HTTP-Calls asynchron (Kotlin Coroutines auf IO-Dispatcher), nie auf dem EDT.

---

## 4. Architektur

### 4.1 Technologie

| Baustein | Wahl | Begründung |
|---|---|---|
| Sprache | **Kotlin** | JetBrains-Standard für Plugins |
| Build | **Gradle + `intellij-platform-gradle-plugin` (2.x)** | Marketplace-Publishing integriert |
| Ziel-IDE | IntelliJ IDEA Community **und** Ultimate, `sinceBuild` 2024.2+ | git4idea ist in beiden enthalten |
| HTTP | eingebauter Java `HttpClient` oder OkHttp | keine schweren Dependencies |
| JSON | `kotlinx.serialization` | leichtgewichtig |
| UI | Swing + IntelliJ UI-DSL (`com.intellij.ui.dsl`), Icons via `AnimatedIcon` | natives Look & Feel, Dark/Light automatisch |

> Hinweis: Das Plugin hängt nur von `Git4Idea` (bundled) ab → läuft in IDEA, aber
> perspektivisch auch in anderen JetBrains-IDEs (PyCharm, WebStorm, Rider), wenn
> man die `plugin.xml`-Dependency auf `com.intellij.modules.vcs` + `Git4Idea` setzt.
> Das maximiert die Marketplace-Reichweite.

### 4.2 Modulstruktur

```
jenkins-branch-monitor/
├── build.gradle.kts
├── src/main/kotlin/.../
│   ├── core/
│   │   ├── JenkinsClient.kt          # REST-Calls, Auth, tree-Queries
│   │   ├── JobDiscoveryService.kt    # Stufe-1/2/3-Mapping, Cache
│   │   ├── BuildPollerService.kt     # Scheduler, adaptives Polling
│   │   └── model/                    # BuildStatus, MonitoredBranch, JenkinsJobRef
│   ├── git/
│   │   └── BranchTrackerService.kt   # GitRepositoryManager, Change/Push-Listener
│   ├── ui/
│   │   ├── MonitorToolWindowFactory.kt
│   │   ├── BuildListPanel.kt         # Zeilen mit Kugel + Label
│   │   ├── StatusBarWidget.kt
│   │   └── BubbleIcon.kt             # farbige Kugel, Puls-Animation (AnimatedIcon)
│   ├── settings/
│   │   ├── PluginSettings.kt         # PersistentStateComponent (App- + Projekt-Level)
│   │   ├── SettingsConfigurable.kt   # Settings-UI
│   │   └── CredentialsStore.kt       # PasswordSafe-Anbindung
│   └── notify/
│       └── BuildNotificationService.kt
└── src/main/resources/META-INF/plugin.xml
```

### 4.3 Datenfluss

```
GitRepositoryChangeListener ──► BranchTrackerService
                                      │  (Repo, Branch, RemoteUrl)
                                      ▼
                              JobDiscoveryService ──► Jenkins /api/json (Discovery, gecacht)
                                      │  JenkinsJobRef
                                      ▼
                              BuildPollerService ──► Jenkins lastBuild-Poll
                                      │  BuildStatus (Flow/MessageBus)
                        ┌─────────────┼─────────────┐
                        ▼             ▼             ▼
                 BuildListPanel  StatusBarWidget  NotificationService
```

Kommunikation intern über IntelliJs `MessageBus` (Topic `BUILD_STATUS_CHANGED`)
oder Kotlin `StateFlow` — UI abonniert, Services bleiben UI-frei (testbar).

### 4.4 Sicherheit & Credentials

- Jenkins-Zugang: **URL + User + API-Token** (kein Passwort), gespeichert in
  IntelliJs **PasswordSafe** (`CredentialStore`-API) — nie in XML/Settings-Files.
- Mehrere Jenkins-Server konfigurierbar (Liste), z. B. Firmen-Jenkins + OSS-Jenkins.
- Zertifikate: IntelliJs eingebauten `CertificateManager` nutzen (Self-Signed im
  Firmennetz funktioniert dann wie im Rest der IDE).
- CSRF-Crumb-Handling für „Build jetzt starten" (`/crumbIssuer/api/json`).

---

## 5. Einstellungen (Settings-Seite)

**Applikationsweit:**
- Jenkins-Server (Liste): Name, URL, User, API-Token (PasswordSafe), „Verbindung testen"-Button
- Polling-Intervalle (idle / building)
- Notification-Verhalten pro Severity

**Pro Projekt:**
- Überwachungsmodus: `aktuelle Branches` / `aktuelle + kürzlich gepushte` / `manuelle Liste`
- Repos ein-/ausschließen (Checkbox-Liste aller erkannten Git-Roots)
- Manuelle Job-Mappings (Stufe 3, Tabelle)
- Haltedauer für „kürzlich gepushte" Branches

---

## 6. MVP & Roadmap

### Phase 1 — MVP (Marketplace-fähige Erstversion)
- [ ] Ein Jenkins-Server, Auth via API-Token + PasswordSafe
- [ ] Branch-Erkennung aller Git-Roots, Live-Update bei Checkout
- [ ] Multibranch-Autodiscovery (Stufe 1) + manuelles Mapping (Stufe 3)
- [ ] Tool Window unten rechts: Kugel (blau animiert/grün/rot/grau) + `repo/branch` + Buildnr.
- [ ] Klick → Browser; Tooltip mit Details
- [ ] Polling mit tree-Queries, adaptives Intervall
- [ ] Notification bei rot

### Phase 2 — Komfort
- [ ] Status-Bar-Widget mit Aggregat-Kugel
- [x] „Kürzlich gepusht"-Tracking (GitPushListener) — *vorgezogen, bereits im MVP umgesetzt (Retention konfigurierbar, Default 120 min)*
- [ ] Kontextmenü: Build triggern, Console-Log (im IDE-Console-Tab)
- [ ] Gelb/Unstable, Fortschrittsanzeige laufender Builds
- [ ] Mehrere Jenkins-Server
- [ ] Namens-Heuristik (Stufe 2) mit Template

### Phase 3 — Nice to have
- [ ] SSE/WebSocket statt Polling (wo Jenkins es kann)
- [ ] Build-Historie-Sparkline pro Zeile (letzte 5 Builds als Mini-Punkte)
- [ ] Test-Result-Summary im Tooltip (failed/total)
- [ ] Unterstützung weiterer CI-Server hinter SPI (GitLab CI, GitHub Actions) —
      Interface `CiProvider` von Anfang an so schneiden, dass Jenkins nur eine
      Implementierung ist (hält die Marketplace-Tür für „CI Branch Monitor" offen)

---

## 7. Marketplace-Veröffentlichung

**Anforderungen (Checkliste):**
- [ ] Eindeutige Plugin-ID (`org.<dein-namespace>.jenkinsbranchmonitor`), Vendor-Infos
- [ ] `plugin.xml`: `<description>`, `<change-notes>`, Screenshots (hell + dunkel), 40×40/80×80-Logo (SVG)
- [ ] Kompatibilitätsbereich `sinceBuild`/`untilBuild` + **Plugin Verifier** im CI-Build
      (`runPluginVerifier`-Gradle-Task gegen die letzten 3 IDE-Majors)
- [ ] **Signing** (JetBrains-Marketplace-Zertifikat, `signPlugin`-Task) + `publishPlugin` mit Token
- [ ] Keine Netz-Calls ohne Nutzer-Konfiguration (Privacy: Plugin telefoniert nur zum
      konfigurierten Jenkins; keine Telemetrie) → im Marketplace-Text deklarieren
- [ ] Lizenz: z. B. Apache-2.0, Repo öffentlich auf GitHub (erhöht Vertrauen + Installs)
- [ ] Freemium optional später; Start als **kostenloses** Plugin empfohlen

**Abgrenzung zu bestehenden Plugins (USP klar machen):**
- *Jenkins Control Plugin*: zeigt Job-Baum, aber **kein** automatisches
  Repo/Branch-Matching und keine minimalistische Always-On-Ansicht.
- *CI-Pipeline-Tabs anderer Anbieter*: meist Cloud-CI-fokussiert.
- **USP:** „Zero-Config: öffne dein Projekt, sieh deine Builds." Automatik aus
  lokalem Git-Zustand ist das Alleinstellungsmerkmal.

---

## 8. Risiken & offene Punkte

| Risiko | Mitigation |
|---|---|
| Jenkins-Instanzen sind extrem heterogen (Freestyle, Multibranch, Folder, CloudBees) | Stufe-3-Mapping als universeller Fallback; Discovery defensiv mit Tiefenlimit |
| Discovery auf großen Jenkins-Servern (>1000 Jobs) langsam | tree-Queries, Tiefenlimit, Cache pro Remote-URL, Discovery nur on-demand |
| Branch-Namen mit Sonderzeichen (`/` in `release/1.4`) | konsequentes URL-Encoding (`%2F`), Tests dafür |
| API-Rate/Last auf Firmen-Jenkins | adaptives Polling, konfigurierbares Minimum, HTTP-Caching (`ETag`/`Last-Modified` wo vorhanden) |
| IntelliJ-API-Änderungen (Tool Window, StatusBar) | Plugin Verifier im CI, `sinceBuild` konservativ |
| Auth-Varianten (SSO/Reverse-Proxy vor Jenkins) | erstmal nur Basic+Token (deckt 95 %), Header-Injection als Setting für Proxys |

**Offene Designfragen (vor Implementierung klären):**
1. Soll das Panel projektübergreifend aggregieren (alle offenen IDE-Fenster) oder strikt pro Projekt? → Vorschlag: pro Projekt (einfacher, IntelliJ-idiomatisch).
2. Verhalten bei Detached HEAD / Rebase? → Vorschlag: Zeile grau mit „detached", kein Poll.
3. Build-Trigger im MVP oder Phase 2? → Vorschlag: Phase 2 (MVP rein lesend = risikoärmer fürs erste Release).

---

## 9. Grober Aufwand (Schätzung)

| Phase | Aufwand |
|---|---|
| Projekt-Setup, Gradle, plugin.xml, CI | 1–2 Tage |
| Git-Tracking + Jenkins-Client + Discovery | 3–5 Tage |
| Tool Window UI + Kugel-Rendering | 2–3 Tage |
| Settings + PasswordSafe | 1–2 Tage |
| Polish, Verifier, Marketplace-Einreichung | 2–3 Tage |
| **MVP gesamt** | **~2–3 Wochen** (nebenher entsprechend länger) |
