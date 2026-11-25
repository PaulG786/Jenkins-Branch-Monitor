# Konzept 0.2.0 — Jenkins Branch Monitor

> Status: **Konzept / zur Abstimmung** — Stand 2026-11-18
> Basis: 0.1.0 (Push-Lifecycle, eigene Builds, Push-Statistik)
> Leitplanke (unverändert): **strikt read-only** — ausschließlich HTTP `GET` gegen Jenkins,
> keine Build-Trigger, kein Abort, kein Replay. Alles Neue in diesem Dokument hält das ein.

Vier Features:

| # | Feature | Kurz |
|---|---|---|
| F1 | Deaktivierte Repos mitbeobachten | Setting: Repos ohne aktives Git-Mapping trotzdem anzeigen |
| F2 | Log-Viewer | Klick auf Build → Auswahl „Im Browser öffnen" oder „Log anzeigen" (in der IDE) |
| F3 | Tabellen-Ansicht | Freitext-Zeile → saubere Tabelle mit getrennten Spalten |
| F4 | Pull-Warnung | Hinweis beim Pull/Update, wenn der Jenkins-Build des Branches rot ist |

---

## F1 — Deaktivierte Repos mitbeobachten

### Problem
Das Plugin holt die Repos heute über `GitRepositoryManager`. Der kennt nur Roots, die in
*Settings → Version Control → Directory Mappings* mit VCS **Git** registriert sind.
Roots mit VCS **`<None>`** (deaktiviert) oder **nicht registrierte** Roots
(„Unregistered roots", von IntelliJ erkannt, aber nicht hinzugefügt) fallen raus.

### Lösung
Neuer Baustein `UnmanagedRootScanner` (liest Git-Zustand **direkt aus dem Dateisystem**,
ohne git4idea — denn für diese Roots gibt es kein `GitRepository`-Objekt):

1. **Quellen der Roots**
   - `ProjectLevelVcsManager.getDirectoryMappings()` → Einträge mit leerem VCS (`<None>`).
   - Optional: von IntelliJ erkannte, aber nicht registrierte Roots
     (`VcsRootDetector` bzw. `VcsConfiguration.IGNORED_UNREGISTERED_ROOTS`).
2. **Branch** aus `.git/HEAD` (`ref: refs/heads/release/1.5` → `release/1.5`;
   SHA statt `ref:` → detached). Worktrees: `.git` ist dann eine Datei mit `gitdir: …` → folgen.
3. **Remote-URL** aus `.git/config` (`[remote "origin"] url = …`, Fallback: erstes Remote) →
   damit greift die bestehende Job-Discovery (Projekt-Key-Ordner + Encoding) unverändert.
4. **Aktualisierung:** Einlesen bei jedem Poll-Zyklus (zwei kleine Datei-Reads pro Repo, vernachlässigbar)
   + `BulkFileListener` auf `.git/HEAD` → Branch-Wechsel erscheint sofort, nicht erst nach 60 s.

### Darstellung
- Zeile ganz normal (Kugel, Status, Build), zusätzlich dezentes Merkmal in der Repo-Spalte
  (gedimmtes Icon / Tooltip „VCS-Mapping deaktiviert — Branch aus .git/HEAD gelesen").
- **Einschränkung (dokumentieren):** Push-Tracking & „eigene Builds" funktionieren für diese Repos
  nicht, weil IntelliJ dort weder pusht noch den Push meldet. Für sie gibt es nur den
  reinen Branch-Status (wie „aktuell ausgecheckt"), keine Push-Phasen und keine Popups.

### Settings (Projekt-Ebene… bzw. global, siehe offene Frage)
- ☐ **Repos mit deaktiviertem VCS-Mapping (`<None>`) mitbeobachten** — Default: aus
- ☐ **Nicht registrierte Git-Roots mitbeobachten** — Default: aus

---

## F2 — Log-Viewer („schnell mal die Logs checken")

### Interaktion
**Klick auf eine Zeile** öffnet ein kleines Popup-Menü direkt an der Maus:

```
┌──────────────────────────────┐
│ 📄 Log anzeigen              │   ← Default-Eintrag (Enter)
│ 🌐 Build im Browser öffnen   │
│ 🌐 Job im Browser öffnen     │
│ ──────────────────────────── │
│ 📋 Build-URL kopieren        │
└──────────────────────────────┘
```

- **Einfacher Klick / Enter** = Menü öffnet sich direkt (umgesetzt in 0.2.0; die ursprünglich geplante
  Doppelklick-Default-Aktion entfällt, weil sie mit dem Einfachklick-Menü kollidiert).
- **Rechtsklick** = gleiches Menü (Kontextmenü-Konvention).
- Keine Einträge wie „Rebuild", „Replay", „Abort" — **read-only**.

### Log-Ansicht
Neuer **schließbarer Tab im Tool Window** „Jenkins Builds" (ContentManager-Tabs):

```
[ Builds ] [ Log: shop-service/main #512 ✕ ]
┌───────────────────────────────────────────────────────────────┐
│ ⟳ Refresh  ⇣ Follow  ⚠ Nächster Fehler  ▤ Nur Fehler  🌐  📝  │
├───────────────────────────────────────────────────────────────┤
│ … (letzte 2 MB) — gesamtes Log im Browser / Editor öffnen     │
│ [INFO] Building shop-service 1.5.0-SNAPSHOT                   │
│ [ERROR] COMPILATION ERROR :                    ← rot markiert │
│ [ERROR] /…/OrderService.java:[47,13] cannot find symbol       │
│ [INFO] BUILD FAILURE                                          │
└───────────────────────────────────────────────────────────────┘
```

- **Komponente:** IntelliJ `ConsoleView` (read-only, Strg+F-Suche, Auto-Scroll, Soft-Wrap gratis).
  ANSI-Farbcodes (Jenkins AnsiColor-Plugin) werden über `AnsiEscapeDecoder` gerendert bzw. entfernt.
- **Fehler-Highlighting:** Zeilen mit `[ERROR]`, `BUILD FAILURE`, `FAILED`, `Exception`,
  `Tests run: … Failures: [1-9]` rot; Button **„Nächster Fehler"** springt durch,
  Toggle **„Nur Fehler"** filtert (+ n Zeilen Kontext). Beim Öffnen eines roten Builds
  wird **automatisch zum ersten Fehler** gescrollt — das ist der eigentliche Zeitgewinn.
- **Laufende Builds (blau):** Live-Tail über
  `GET {buildUrl}/logText/progressiveText?start={offset}` — Antwort-Header
  `X-Text-Size` = nächster Offset, `X-More-Data: true` solange der Build läuft.
  Poll alle 2 s nur solange der Log-Tab sichtbar ist; „Follow" scrollt mit.
- **Fertige Builds:** gleicher Endpoint einmalig (bzw. `consoleText`).
- **Große Logs** (Maven-Builds gern 20–100 MB): Standard = **nur die letzten 2 MB** anzeigen
  (Setting „Max. Log-Größe"), Hinweiszeile oben mit „Gesamtes Log im Editor öffnen"
  (→ read-only `LightVirtualFile` im Editor, dort volle Editor-Suche).
  Umsetzung: Stream lesen, Ringpuffer auf die letzten N Bytes — nie alles im Heap.
  *(Zu verifizieren beim Bau: ob `progressiveText` einen Start-Offset nahe dem Ende akzeptiert —
  dann muss nicht das ganze Log übertragen werden.)*
- **Mehrere Logs gleichzeitig:** pro Build ein Tab; Tab eines Builds wird wiederverwendet.
- **Aus Notifications:** Die Popups „Build failed after your push" bekommen zusätzlich
  die Aktion **„Log anzeigen"** neben „Open in Browser".
- **Phase 2 (optional):** Bei Pipelines Stage-Liste über `wfapi/describe` und direkt das
  Log der fehlgeschlagenen Stage zeigen.

---

## F3 — Tabellen-Ansicht

### Problem
Aktuell ist alles in einem Freitext-String: `#512  12 min ago  ·  red after your push — watching for green`.
Bei 4 Repos × 3 Branches liest sich das schlecht, nichts steht untereinander.

### Lösung: `JBTable` mit festen Spalten

| ● | Repository | Branch | Build | Ergebnis / Zeit | Dein Push |
|---|---|---|---|---|---|
| 🔵 | shop-service | release/1.5 | #412 | läuft 3:12 / ~8 min | ⏳ baut |
| 🔴 | shop-service | main | #512 | rot · vor 12 min | ⚠ rot → wartet auf grün |
| 🟢 | shop-web | release/1.4 | #88 | grün · vor 4 min | ✓ durch |
| 🟢 | shop-config | main | #1337 | grün · vor 2 h | – |
| ⚪ | ext_lib *(VCS aus)* | main | – | kein Job gefunden | – |

- **Spalten:** Status-Kugel (fix 20 px) · Repository · Branch · Build-Nr. · Ergebnis/Zeit
  (bei laufenden Builds: Laufzeit / geschätzte Dauer aus `estimatedDuration`) · Dein Push (Phase).
- **Look:** keine Gitterlinien, kompakte Zeilenhöhe, Branch leicht gedimmt, „Dein Push" farbig
  nur wenn aktiv; Tooltip pro Zelle mit Details (wie heute).
- **Sortierung:** bleibt die Aktivitäts-Sortierung aus 0.1.0 (aktive Pushes oben, dann zuletzt
  ausgecheckt). Klick auf Spaltenkopf sortiert temporär; Toolbar-Button „Standard-Sortierung".
- **Spalten ein-/ausblendbar** über Rechtsklick auf den Tabellenkopf (persistiert) —
  wer das Fenster schmal rechts andockt, blendet z. B. „Build" und „Zeit" aus.
- **Statistikzeile** (Pushes / grün / rot) bleibt unter der Tabelle.
- Umsetzung: `TableView<BranchBuildStatus>` + `ListTableModel` + `ColumnInfo` je Spalte,
  eigene Renderer (Kugel = bestehendes `BubbleIcon`). Interaktionen aus F2 hängen an der Tabelle.

---

## F4 — Pull-Warnung bei rotem Build

### Problem
Beim Pull/Update merkt man nicht, dass der Branch in Jenkins gerade rot ist — man holt sich
kaputten Stand und sucht dann lokal Fehler, die gar nicht von einem selbst sind.

### Wichtige Abgrenzung zu „nur eigene Builds"
Die Regel „nur eigene Builds" gilt weiterhin für **Popups nach Push** und die **Statistik**.
Für die Pull-Warnung ist dagegen **jeder** rote Build relevant — gerade fremde, denn genau die
holt man sich per Pull. Bewertet wird der **letzte Build des Upstream-Branches**
(`currentBranch.findTrackedBranch()` → i. d. R. derselbe Name wie lokal).

### Erkennung des Pull-Zeitpunkts
Verifiziert gegen IntelliJ 2024.2 (`vcs-git.jar` / `app.jar`):

| Aktion | Action-ID | erfassbar |
|---|---|---|
| VCS → Git → **Pull…** | `Git.Pull` | ✅ |
| **Update Project** (Strg+T, Toolbar-Pfeil) | `Vcs.UpdateProject` | ✅ |
| Git-Toolwindow „Update Selected" / Branch-Popup „Update" | `GitUpdateSelectedBranchAction` (ab 0.2.5) | ✅ |
| Branch-Popup „Pull into … Using Rebase/Merge" | `GitPullBranchAction$WithRebase/$WithMerge` (ab 0.2.5) | ✅ |
| Pull über Terminal / externe Tools | – | ❌ (dafür: Status-Bar-Widget, s. u.) |

### Entscheidung: echte Rückfrage vor dem Pull (2026-11-18)

Ist mindestens ein betroffener Branch rot, erscheint **vor** dem Pull **jedes Mal** ein modaler Dialog
mit genau zwei Buttons — keine Merk-Logik, kein „nicht mehr fragen":

```
┌ Jenkins-Build ist rot ─────────────────────────────┐
│ ⚠ shop-service/main                        │
│   #512 FAILURE · seit 14 min                      │
│                                                     │
│ Willst du trotzdem pullen?                          │
│                        [Abbrechen]  [Trotzdem pullen] │
└─────────────────────────────────────────────────────┘
```

- **„Trotzdem pullen"** (Default-Button, Enter) → Original-Aktion läuft normal weiter.
- **„Abbrechen"** (Esc) → Pull findet nicht statt.
- Mehrere rote Branches (Update Project über alle Repos) → alle in einem Dialog gelistet.
- Grün/kein Job/Jenkins offline → kein Dialog, Pull läuft direkt (Jenkins-Probleme blockieren nie den Workflow).

**Technik:** Die Actions `Git.Pull` und `Vcs.UpdateProject` werden beim Start per
`ActionManager.replaceAction` mit einem Wrapper ummantelt, der vorher prüft und dann an die
Original-Action delegiert (`update()`/Presentation/Shortcuts werden 1:1 durchgereicht).
Nur so lässt sich der Pull tatsächlich abbrechen — `AnActionListener` kann nur beobachten.
Risiko: Konflikt, falls ein anderes Plugin dieselben Actions ersetzt → Setting zum Abschalten;
beim Deaktivieren/Entladen des Plugins werden die Original-Actions wiederhergestellt.

### Gemeinsame Details
- Geprüft werden die Repos, die die Aktion betrifft (Update Project = alle registrierten Git-Roots).
- **Datenbasis:** zuletzt gepollter Status (max. ~60 s alt) — sofort, kein Warten.
  Optional schneller Frisch-Check (`GET lastBuild`, Timeout 2 s) für die betroffenen Branches.
- **Auch melden?** „Build läuft gerade" → nur Info-Hinweis (Setting), standardmäßig aus.
- Gelb/UNSTABLE: wie rot behandeln (Setting) — Default: ja.

### Ergänzend: Status-Bar-Widget (aus Konzept 0.1, Phase 2 — jetzt sinnvoll)
Kleine Aggregat-Kugel in der Statusleiste, z. B. `● 1 red · ↑12 ✓10 ✗2`, Klick öffnet das Tool Window.
Damit sieht man den roten Branch **bevor** man überhaupt auf Pull drückt — deckt auch
Terminal-Pulls und den Branch-Popup-Fall ab.

**Entscheidung bei Umsetzung:** Die Push-Statistik (vorher Fußzeile im Tool Window) zieht komplett
ins Widget um: Text `↑Pushes ✓grün ✗rot`, Tooltip mit Details + Liste roter Branches,
Rechtsklick → *Open Jenkins Builds* / *Refresh Now* / *Reset Statistics…*. Ein-/Ausblenden über
den Standard-Statusleisten-Rechtsklick der IDE (kein eigenes Setting nötig).

---

## Settings — Änderungen im Überblick

| Bereich | Neu | Default |
|---|---|---|
| Repos | Deaktivierte Mappings (`<None>`) mitbeobachten | aus |
| Repos | Nicht registrierte Git-Roots mitbeobachten | aus |
| Interaktion | ~~Doppelklick öffnet: Log / Browser~~ → entfällt, Einfachklick zeigt Menü | – |
| Log | Max. angezeigte Log-Größe | 2 MB |
| Log | Bei rotem Build automatisch zum ersten Fehler springen | an |
| Pull | Rückfrage bei rotem Build vor Pull/Update | an |
| Pull | UNSTABLE wie rot behandeln | an |
| UI | Status-Bar-Widget anzeigen (über IDE-Statusleisten-Menü) | an |
| UI | Ausgeblendete Tabellenspalten (Rechtsklick auf Tabellenkopf) | keine |

---

## Plugin-Beschreibung (Marketplace / `plugin.xml`) — neuer Entwurf

Die aktuelle Beschreibung ist veraltet (z. B. „Notifications when a build fails or recovers" —
seit 0.1.0 gilt: nur für eigene Pushes; Push-Tracking und Statistik fehlen ganz).
Wird bei der Umsetzung in `plugin.xml`, `README.md` und `<change-notes>` übernommen:

```html
<p>See the Jenkins builds of <b>your</b> branches right in the IDE &mdash; no more hunting
through Jenkins per repository and branch.</p>
<ul>
  <li><b>Zero config discovery:</b> detects all Git roots and their checked-out branches and finds the
      matching Jenkins multibranch jobs automatically (folders, encoded branch names, manual mapping as fallback).
      Optionally also watches repositories whose VCS mapping is disabled.</li>
  <li><b>Compact table view:</b> status bubble (blue = building, green = success, red = failed,
      yellow = unstable, gray = unknown), repository, branch, build number, result/time and your push state.</li>
  <li><b>Follows your pushes:</b> pushed branches stay monitored after you switch branches.
      You are notified when <i>your</i> build passes, breaks or turns green again &mdash;
      builds triggered by others never spam you.</li>
  <li><b>Build logs inside the IDE:</b> open the console log in a tool window tab with live tail,
      error highlighting and "jump to next error" &mdash; or open the build in the browser.</li>
  <li><b>Pull guard:</b> asks before you pull or update a branch whose Jenkins build is currently red
      &mdash; pull anyway or cancel.</li>
  <li><b>Push statistics</b> per workspace: pushes, green and red builds (own builds only).</li>
  <li>Status bar indicator, adaptive polling, API token stored in the IDE password safe.</li>
</ul>
<p><b>Strictly read-only:</b> the plugin only reads build status and logs via HTTP GET. It never
triggers, aborts, replays or modifies builds.</p>
```

---

## Umsetzungsreihenfolge & Aufwand (grob)

| Schritt | Inhalt | Aufwand |
|---|---|---|
| 1 | **F3 Tabelle** (Basis für alle Interaktionen) | ~0,5 Tag |
| 2 | **F2 Log-Viewer** inkl. Klick-Menü, Live-Tail, Fehlernavigation | ~1–1,5 Tage |
| 3 | **F4 Pull-Rückfrage** (Action-Wrapper + Dialog) + Status-Bar-Widget | ~0,5–1 Tag |
| 4 | **F1 Deaktivierte Repos** | ~0,5 Tag |
| 5 | Beschreibung/README/Change-Notes, Version → 0.2.0 | ~0,25 Tag |

## Entscheidungen (2026-11-18)
1. **F1:** Beides abdecken — `<None>`-Mappings und nicht registrierte Roots, je eigene Checkbox.
2. **F4:** Echte Rückfrage bei jedem Pull/Update auf roten Branch: „Trotzdem pullen" / „Abbrechen". Keine Merk-Logik.
3. **F2:** Log als schließbarer Tab im Tool Window, „Gesamtes Log im Editor" als Ausweg für große Logs.
4. **Settings:** global, wie die übrigen Settings.

## Bewusst nicht enthalten
- Build-Trigger, Abort, Replay, Rebuild — **read-only bleibt**.
- Logs anderer Builds als des letzten pro Branch (Historie) → ggf. später über Build-Historie-Sparkline.
