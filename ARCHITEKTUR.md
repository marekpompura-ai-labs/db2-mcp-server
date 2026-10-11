# Architekturüberblick

> Kurzfassung auf Deutsch. Die ausführliche Dokumentation dieses Projekts
> ist englisch — Konvention für quelloffene Projekte auch im
> deutschsprachigen Enterprise-Umfeld. Dieses Dokument ordnet die
> vorhandenen Artefakte den Kapiteln von **arc42** zu, damit der Einstieg
> für Leserinnen und Leser mit iSAQB-/arc42-Hintergrund kurz ausfällt.
>
> Es ist bewusst **kein vollständiges arc42-Dokument**, sondern eine
> Landkarte auf die englischsprachigen Originale.

## 1. Einführung und Ziele

Ein MCP-Server, über den KI-Assistenten **lesend und kontrolliert** auf
IBM Db2 zugreifen: Metadaten erkunden und begrenzte diagnostische Abfragen
ausführen. Grundannahme: **das Sprachmodell ist kein vertrauenswürdiger
Sicherheitsprinzipal.** Durchgesetzt wird die Richtlinie im Server, nicht
im Prompt.

→ [`README.md`](README.md), [`docs/master-brief.md`](docs/master-brief.md)

## 2. Randbedingungen

Java 21, Spring Boot, Spring AI (MCP-Server-Starter), IBM Db2 über JDBC.
Unterstützter Transport ist **stdio** — der Prozess öffnet keinen eigenen
Netzwerk-Port. Der Datenbankzugriff erfolgt über einen **lesenden Benutzer
mit minimalen Rechten**.

Daneben existiert ein **experimenteller HTTP-Transport mit Bearer-Token**
(ADR 0037), der **standardmäßig abgeschaltet** ist. Ihn einzuschalten ist
eine bewusste Betreiberentscheidung und verändert die Angriffsfläche: es
entsteht ein lauschender Port, den es bei stdio nicht gibt. Er bindet nur
auf Loopback, bringt kein eigenes TLS mit und kennt weder Token-Rotation
noch Rate Limiting. **Unternehmensauthentifizierung und produktiver
Remote-Betrieb sind in 1.0.0 nicht enthalten.**

→ [ADR 0001](docs/adr/0001-stdio-transport.md),
[ADR 0002](docs/adr/0002-read-only-enforcement.md)

## 3. Kontextabgrenzung

MCP-Client (z. B. Claude Code) → stdio → Server → JDBC → Db2.
Die einzige echte Vertrauensgrenze verläuft zwischen Server und Datenbank;
alles links davon läuft im selben Prozess ohne eigenen Listener.

→ [`docs/architecture-diagrams.md`](docs/architecture-diagrams.md) §14.1

## 4. Lösungsstrategie

Jeder Werkzeugaufruf durchläuft dieselbe Richtlinienkette, unabhängig
davon, worum der Client gebeten hat: **Capability-Profil → Eingabe-
validierung → strukturelle SQL-Prüfung → Schema-Allowlist → Ausführung mit
Limits → Telemetrie.**

Drei Capability-Profile bestimmen, welche Werkzeuge überhaupt sichtbar
sind: `METADATA_ONLY` (Voreinstellung), `SAFE_QUERY`, `ADVANCED_QUERY`.
Ein Werkzeug, das ein Profil nicht erlaubt, ist für den Client **nicht
sichtbar** — es wird nicht erst beim Aufruf abgelehnt.

→ [ADR 0006](docs/adr/0006-capability-profiles.md)

## 5. Bausteinsicht

Hexagonale Struktur mit ausgehenden Ports (`MetadataPort`, `QueryPort`,
`ExplainPort`, `CapabilityPort`), die ausschließlich fachliche Typen
zurückgeben. Ein datenbankfreier Test-Adapter ist in die echten
MCP-Werkzeugklassen verdrahtet und belegt die Austauschbarkeit, statt sie
nur zu behaupten.

→ [ADR 0027](docs/adr/0027-phase-p3-dependency-inversion.md),
[ADR 0029](docs/adr/0029-phase-p5-portability-evidence.md)

## 6. Laufzeitsicht

Zwei Abläufe desselben Werkzeugs, einmal erlaubt und einmal abgelehnt —
in den Diagrammen §14.2 und §14.3 nebeneinander dargestellt. Das ist der
schnellste Weg, die Wirksamkeit der Kette nachzuvollziehen.

→ [`docs/architecture-diagrams.md`](docs/architecture-diagrams.md) §14.2/§14.3

## 7. Verteilungssicht

Ein einzelner Prozess neben dem MCP-Client, üblicherweise als gepacktes
JAR. Kein Netzwerkdienst, keine eigene Persistenz, keine Sitzungsdaten.

## 8. Querschnittliche Konzepte

- **Lesezugriff dreifach abgesichert:** Datenbankrechte, Verbindungs-
  ebene, strukturelle SQL-Prüfung — keine Ebene gilt für sich als
  ausreichend ([ADR 0002](docs/adr/0002-read-only-enforcement.md)).
- **Strukturelle SQL-Prüfung** am geparsten Syntaxbaum statt per
  Zeichenkettensuche, danach Schema-Allowlist
  ([ADR 0007](docs/adr/0007-sql-structural-validation.md),
  [ADR 0008](docs/adr/0008-schema-allowlist.md)).
- **Ressourcengrenzen:** Zeilenobergrenze, Statement-Timeout,
  Komplexitätsgrenzen sowie eine harte, am vollständigen serialisierten
  Ergebnis gemessene Obergrenze für die Antwortgröße
  ([ADR 0034](docs/adr/0034-query-complexity-controls.md),
  [ADR 0045](docs/adr/0045-hard-universal-payload-bound.md)).
- **Nachvollziehbarkeit:** Korrelations-ID über Audit-Log, Metriken und
  Traces hinweg; Audit-Einträge aus einem geschlossenen Vokabular, **nie
  mit SQL-Text oder Nutzdaten**
  ([ADR 0010](docs/adr/0010-traceability.md),
  [ADR 0041](docs/adr/0041-correlation-id-and-result-bytes-observability.md)).
- **Observability:** Micrometer-Metriken und OpenTelemetry-Traces, optional
  nach Dynatrace exportierbar (standardmäßig abgeschaltet)
  ([ADR 0036](docs/adr/0036-dynatrace-observability-export.md)).

## 9. Architekturentscheidungen

**46 Architecture Decision Records** in [`docs/adr/`](docs/adr/) — jeweils
mit Kontext, verworfenen Alternativen und den tatsächlich eingetretenen
Konsequenzen. Mehrere ADRs dokumentieren Fehler, die erst im Betrieb
auffielen, samt Korrektur; sie wurden nicht nachträglich geglättet.

## 10. Qualitätsanforderungen

Im Vordergrund stehen **Sicherheit** und **Nachvollziehbarkeit**, danach
**Wartbarkeit** und **Portabilität**; Durchsatz ist ausdrücklich kein
Ziel. Belegt werden diese Eigenschaften durch automatisierte
Angriffstests, Architekturtests, Vertrags-Snapshots und Integrationstests
gegen eine echte Db2-Instanz im Container.

→ [`docs/security/adversarial-test-cases.md`](docs/security/adversarial-test-cases.md)

## 11. Risiken und technische Schulden

Offen dokumentiert im Abschnitt **„Honest limitations"** der
[`README.md`](README.md) sowie in den Review-Dokumenten unter
[`docs/reviews/`](docs/reviews/), in denen **jeder** externe Befund
eingeordnet ist: behoben, mit Begründung zurückgestellt, oder mit
Begründung abgelehnt.

## 12. Regulatorischer Bezug

Der Build erzeugt eine CycloneDX-SBOM; hinzu kommen Bedrohungsmodell und
Angriffstestkatalog. Diese Artefakte sollen ausgewählte Praktiken zur
Lieferkettensicherheit und zur sicheren Entwicklung unterstützen, wie sie
in **BSI TR-03183-2** und **BSI TR-03185** beschrieben sind.

**Das ist eine Einordnung, keine Konformitätsaussage** — weder eine
Zertifizierung noch eine Konformitätsbewertung noch eine
Rechtsauffassung. Ob Cyber Resilience Act, NIS2, DSGVO oder vertragliche
Zulieferanforderungen greifen, hängt von Akteur, Vertriebsmodell,
Einsatzkontext und anwendbarem Recht ab — nichts davon entscheidet dieses
Repository.

## Glossar

| Begriff | Bedeutung |
|---|---|
| **MCP** | Model Context Protocol — Protokoll, über das KI-Clients Werkzeuge aufrufen |
| **Capability-Profil** | Konfiguration, die festlegt, welche Werkzeuge sichtbar sind |
| **Allowlist** | Positivliste zugelassener Schemata; alles andere ist abgelehnt |
| **ADR** | Architecture Decision Record |
| **SBOM** | Software Bill of Materials — maschinenlesbare Komponentenliste |
