# Branch- und Worktree-Bericht

Stichtag: 25.11.2026. Grundlage: `origin/main` nach `git fetch -q origin`
(Commit `c83770932`). Erfasst sind Remote-Branches `feature/*`, `fix/*`,
`docs/*` und `spec/*`, für die kein offener PR besteht. Kein Branch und kein
Worktree wurde gelöscht.

## Remote-Branches ohne offenen PR

«Gemergt» bedeutet: `git branch -r --merged origin/main` führt den Branch.
«Verwaist» bedeutet hier nur: nicht in `origin/main` gemergt und ohne offenen
PR; daraus folgt keine Löschfreigabe. Total: 50 Branches, davon 20 gemergt
und 30 verwaist. 14 Branches mit offenem PR sind nicht aufgeführt.

### Gemergt (20)

- `origin/docs/infomaniak-ai-datenschutz`
- `origin/feature/01-0.1-golden-synth`
- `origin/feature/01-0.2-s3-object-lock`
- `origin/feature/01-0.3-infomaniak-ai`
- `origin/feature/01-0.4-21crm-export`
- `origin/feature/01-0.5-golden-lauf`
- `origin/feature/01-0.6-gate0-vorbereitung`
- `origin/feature/01-0.6-option-b-req-6.5`
- `origin/feature/01-0.7-stufe-a-zugang`
- `origin/feature/02-10.3-oberflaeche-auswertung`
- `origin/feature/03-10.2-wissensquelle-seed`
- `origin/feature/03-10.3-branding-m1s3`
- `origin/feature/03-11.6-public-vertrag`
- `origin/feature/03-chat-stundenlimit-test`
- `origin/feature/04-18.1-wissenschat-backend`
- `origin/feature/04-wissensbasis-spec`
- `origin/feature/06-21.5-dokumentation-abgleich`
- `origin/feature/07-hilfe-leiste-stabilitaet`
- `origin/fix/01-0.1-golden-synth-ausgabe`
- `origin/spec/01-lokales-modell`

### Nicht gemergt / verwaist (30)

- `origin/docs/aufgabenstand-2026-11`
- `origin/feature/01-3.3-korrekturlernen`
- `origin/feature/01-5.11-lastprobe`
- `origin/feature/01-5.11-messbericht`
- `origin/feature/01-5.11-stufe-b-werte`
- `origin/feature/01-5.9-ki-beispiel-kennzahlen`
- `origin/feature/01-5.9-monitoring-vorbereitung`
- `origin/feature/01-o8-google-login`
- `origin/feature/03-10.2-mandant-seed`
- `origin/feature/03-10.6-sicherung-mandant2`
- `origin/feature/03-12.2-abbau-rest`
- `origin/feature/03-chat-fehlerstabilitaet`
- `origin/feature/04-13.5b-mindeststufe-zitate`
- `origin/feature/04-13.6-lesen-nach-merge`
- `origin/feature/04-14.1b-ingest-teilauftraege`
- `origin/feature/04-18.3-oeffentlicher-wissenschat`
- `origin/feature/05-17.7b-admin-seeds`
- `origin/feature/05-18.2-suche-funktionen`
- `origin/feature/06-19.2-ablauf-versand`
- `origin/feature/06-19.2-realm-nachweise`
- `origin/feature/06-21.2-migration-entwurf`
- `origin/feature/06-21.2a-betreiber-folgetests`
- `origin/feature/06-21.2a-betreibervorgang`
- `origin/feature/06-21.4-sicherheit-testnachtrag`
- `origin/feature/06-21.4-sicherheits-ausfalltests`
- `origin/feature/06-24.2-passkey-folgetests`
- `origin/feature/07-23.6-pflegeregel`
- `origin/feature/07-hilfe-offline-stabilitaet`
- `origin/fix/hilfe-offline-31`
- `origin/spec/05-mandanten-konfiguration`

## Lokale Worktrees (111)

Quelle: `git worktree list --porcelain` am Stichtag. Pfade sind relativ zum
Home-Verzeichnis (`~`). Der Branchname ist die Zuordnung; `detached` hat
keinen zugeordneten Branch, `LOCKED` ist von einem Claude-Agenten gesperrt.

- `~/git/dokumentenmanagement` → `detached`
- `~/.copilot/session-state/0aed736b-20ef-4e7e-a3ae-c83b7103ef1a/files/pr420-worktree` → `detached`
- `~/.copilot/session-state/28d8fa86-7f09-4c85-a933-2524136c72a0/files/b-r11-19.11-rollen` → `feature/06-19.11-rollen-antraege`
- `~/.copilot/session-state/28d8fa86-7f09-4c85-a933-2524136c72a0/files/b-r11-20.2-einstellungen` → `feature/06-20.2-einstellungen`
- `~/.copilot/session-state/454d88ce-0dfe-4ab6-96cc-0abbdf2cd845/files/spec06-19-7-benutzer` → `feature/06-19.7-benutzer`
- `~/.copilot/session-state/46f45a7e-a136-4d8a-bd58-7eb697fe5cd4/pr258-fix` → `copilot/fix-pr258-archunit`
- `~/.copilot/session-state/976b469f-870f-450c-8df9-0ff35dd736fb/files/cfix-307` → `detached`
- `~/.copilot/session-state/9970cd0a-051a-455b-a321-81245b1e098e/files/a-05-18-2-basis` → `detached`
- `~/.copilot/session-state/9970cd0a-051a-455b-a321-81245b1e098e/files/a-05-18-5-integration` → `local/05-18.5-integration`
- `~/.copilot/session-state/9970cd0a-051a-455b-a321-81245b1e098e/files/a-pr326` → `detached`
- `~/.copilot/session-state/9970cd0a-051a-455b-a321-81245b1e098e/files/a-pr332` → `detached`
- `~/.copilot/session-state/9970cd0a-051a-455b-a321-81245b1e098e/files/a-pr333` → `detached`
- `~/.copilot/session-state/adfcb8ce-802a-4788-a713-08bd2d245313/files/chat-sterne` → `feature/02-10.2-chat-sterne`
- `~/.copilot/session-state/adfcb8ce-802a-4788-a713-08bd2d245313/files/stufe-b-werte` → `feature/01-5.11-stufe-b-werte`
- `~/.copilot/session-state/d3ce6efe-9276-4df1-807b-69cc4c9b3954/files/05-17.7b-admin-seeds` → `feature/05-17.7b-admin-seeds`
- `~/.copilot/session-state/d3ce6efe-9276-4df1-807b-69cc4c9b3954/files/05-17.8-regeln-rest` → `feature/05-17.8-regeln-rest`
- `~/.copilot/session-state/d3ce6efe-9276-4df1-807b-69cc4c9b3954/files/review-pr310-muss` → `detached`
- `~/.copilot/session-state/ea1bd0b5-d641-4b7d-a932-7739bf2ce693/files/r27-04-mindestufe-r1` → `feature/04-13.5b-mindeststufe-zitate-r1`
- `~/git/cfix-293-da58abd1` → `detached`
- `~/git/cfix-298-manuell` → `detached`
- `~/git/cfix-317-2b90a985` → `detached`
- `~/git/crev-298-07d22996-p1` → `detached`
- `~/git/crev-298-07d22996-p2` → `detached`
- `~/git/crev-w1` → `detached`
- `~/git/crev-w2` → `detached`
- `~/git/crev-w3` → `detached`
- `~/git/dokumentenmanagement-01-2.6` → `feature/01-2.6-qr-nachweis`
- `~/git/dokumentenmanagement-01-5.6` → `feature/01-5.6-ueberwachung-nachweis`
- `~/git/dokumentenmanagement-01-5.9` → `feature/01-5.9-monitoring-vorbereitung`
- `~/git/dokumentenmanagement-01-5.9-ki-fehlerisolierung` → `feature/01-5.9-ki-beispiel-fehlerisolierung`
- `~/git/dokumentenmanagement-01-5.9-ki-kennzahlen` → `feature/01-5.9-ki-beispiel-kennzahlen`
- `~/git/dokumentenmanagement-03-10.2` → `feature/03-10.2-wissensquelle-seed`
- `~/git/dokumentenmanagement-03-10.3` → `feature/03-10.3-branding-betriebscheck`
- `~/git/dokumentenmanagement-03-chat-fehlerstabilitaet` → `feature/03-chat-fehlerstabilitaet`
- `~/git/dokumentenmanagement-04-13.7` → `feature/04-13.7-restore-zaehlvergleich`
- `~/git/dokumentenmanagement-04-18.3` → `feature/04-18.3-oeffentlicher-wissenschat`
- `~/git/dokumentenmanagement-04-18.4` → `feature/04-18.4-isolations-abnahmetests`
- `~/git/dokumentenmanagement-04-21.6-betrieb-l2` → `feature/04-21.6-betriebsschalter-notiz`
- `~/git/dokumentenmanagement-04-21.6-l2` → `feature/04-21.6-netzauslieferung-isolation`
- `~/git/dokumentenmanagement-06-19.2-ablauf-versand` → `feature/06-19.2-ablauf-versand`
- `~/git/dokumentenmanagement-07-hilfe-stabil` → `feature/07-hilfe-leiste-stabilitaet`
- `~/git/dokumentenmanagement-19.1` → `feature/06-19.1-spike-nachweis`
- `~/git/dokumentenmanagement-19.2` → `feature/06-19.2-realm-nachweise`
- `~/git/dokumentenmanagement-19.2-folge-l2` → `feature/06-19.2-folge-email-ende`
- `~/git/dokumentenmanagement-19.6` → `feature/06-19.6-protokoll-persistenz`
- `~/git/dokumentenmanagement-19.9` → `feature/06-19.9-token-widerruf-nachweis`
- `~/git/dokumentenmanagement-20.6` → `feature/06-20.6-benutzer-nachweise`
- `~/git/dokumentenmanagement-21.2` → `feature/06-21.2-migration-entwurf`
- `~/git/dokumentenmanagement-21.2a` → `feature/06-21.2a-betreibervorgang`
- `~/git/dokumentenmanagement-21.2a-folgetests` → `feature/06-21.2a-betreiber-folgetests`
- `~/git/dokumentenmanagement-21.4-testnachtrag` → `feature/06-21.4-sicherheit-testnachtrag`
- `~/git/dokumentenmanagement-24.2-folgetests` → `feature/06-24.2-passkey-folgetests`
- `~/git/dokumentenmanagement-aufgabenstand-2026-11` → `docs/aufgabenstand-2026-11`
- `~/git/dokumentenmanagement-branch-bericht-l2` → `feature/19-4-branch-bericht`
- `~/git/dokumentenmanagement-ci-flakes-l2` → `feature/19-5-ci-flakes`
- `~/git/dokumentenmanagement-branding-frontend` → `feature/03-10.3-branding-frontend`
- `~/git/dokumentenmanagement-chat-flakes` → `feature/03-6.4-chat-flakes`
- `~/git/dokumentenmanagement-chat-stundenlimit` → `feature/03-chat-stundenlimit-test`
- `~/git/dokumentenmanagement-claude-33` → `docs/spec01-3.3-haken`
- `~/git/dokumentenmanagement-claude-467` → `detached`
- `~/git/dokumentenmanagement-claude-529` → `feature/06-21.1-admin-kommando`
- `~/git/dokumentenmanagement-claude-531` → `feature/06-20.7-benutzeraktionen`
- `~/git/dokumentenmanagement-claude-544` → `feature/06-21.5-dokumentation`
- `~/git/dokumentenmanagement-claude-fix531` → `fix/benutzer-stepup-start-fehler`
- `~/git/dokumentenmanagement-claude-m562` → `feature/06-21.4-sicherheits-ausfalltests`
- `~/git/dokumentenmanagement-claude-o3` → `spec/02-o3-entscheide-wt`
- `~/git/dokumentenmanagement-claude-passkey` → `fix/e2e-passkey-flake`
- `~/git/dokumentenmanagement-claude-passkey242` → `feature/06-24.2-passkey-admin`
- `~/git/dokumentenmanagement-claude-pwa` → `fix/e2e-pwa-controller`
- `~/git/dokumentenmanagement-claude-req85` → `docs/spec06-req-8.4-8.5-vermerk`
- `~/git/dokumentenmanagement-claude-review-416` → `detached`
- `~/git/dokumentenmanagement-claude-ui2` → `feature/ui-version-build`
- `~/git/dokumentenmanagement-claude-ui3` → `feature/e2e-pwa-installiert-stabil`
- `~/git/dokumentenmanagement-copilot` → `feature/01-4.11-a6-watcher`
- `~/git/dokumentenmanagement-copilot-2` → `spec/03-haken-pflege`
- `~/git/dokumentenmanagement-copilot-nacht-stufe-p` → `feature/01-5.5a-stufe-p`
- `~/git/dokumentenmanagement-dep-angular` → `chore/dependabot-angular-22.3`
- `~/git/dokumentenmanagement-dep-config` → `chore/dependabot-config`
- `~/git/dokumentenmanagement-flake31` → `fix/hilfe-offline-31`
- `~/git/dokumentenmanagement-l1-hilfe-offline` → `feature/07-hilfe-offline-stabilitaet`
- `~/git/dokumentenmanagement-m1a` → `feature/04-17.1-wissensquelle-markierung`
- `~/git/dokumentenmanagement-m1s3` → `feature/07-23.4-links-so-funktioniert-es`
- `~/git/dokumentenmanagement-m1s3c` → `feature/03-12.3-isolation-tests`
- `~/git/dokumentenmanagement-m2s1e` → `feature/02-10.3-auswertung-export`
- `~/git/dokumentenmanagement-n2` → `detached`
- `~/git/dokumentenmanagement-n3` → `feature/03-11.5-shortcodes`
- `~/git/dokumentenmanagement-n3-10.5` → `feature/03-10.5-altbestand-rest`
- `~/git/dokumentenmanagement-n3-495` → `feature/03-11.11-grenzen-rest`
- `~/git/dokumentenmanagement-n3-496` → `feature/03-11.6-vertrag-versionierung`
- `~/git/dokumentenmanagement-n3-497` → `feature/03-10.6-sicherung-mandant2`
- `~/git/dokumentenmanagement-n5b` → `detached`
- `~/git/dokumentenmanagement-nacharbeit576` → `nacharbeit-576`
- `~/git/dokumentenmanagement-nacharbeit604` → `nacharbeit-604`
- `~/git/dokumentenmanagement-quinoa-4.0.0` → `feature/07-quinoa-4.0.0-kompatibilitaet`
- `~/git/dokumentenmanagement/.claude/worktrees/agent-a4136e5a6e3a0c4ea` → `feature/ui-wissen-entwuerfe` (LOCKED)
- `~/git/dokumentenmanagement/.claude/worktrees/agent-a426b49c60d23d9ac` → `feature/ui-kontakte` (LOCKED)
- `~/git/dokumentenmanagement/.claude/worktrees/agent-a440558e58c24bfa0` → `feature/ui-eingangskorb` (LOCKED)
- `~/git/dokumentenmanagement/.claude/worktrees/agent-a4d1244acaaa9c5b8` → `feature/ui-erfassen` (LOCKED)
- `~/git/dokumentenmanagement/.claude/worktrees/agent-a5952ee399787d27a` → `feature/ui-verwaltung` (LOCKED)
- `~/git/dokumentenmanagement/.claude/worktrees/agent-a6411634fcbc118ed` → `feature/ui-suche` (LOCKED)
- `~/git/dokumentenmanagement/.claude/worktrees/agent-a6b4376ab5223a1c6` → `feature/ui-export-importe` (LOCKED)
- `~/git/dokumentenmanagement/.claude/worktrees/agent-a7c1c40ac3be5f95c` → `feature/ui-chat` (LOCKED)
- `~/git/dokumentenmanagement/.claude/worktrees/agent-a7db216fa42b23805` → `feature/07-23.1-einfuehrungsvermerk` (LOCKED)
- `~/git/dokumentenmanagement/.claude/worktrees/agent-a7ed4b3c5ada0bb61` → `feature/07-22.5-texte-erfassen` (LOCKED)
- `~/git/dokumentenmanagement/.claude/worktrees/agent-a88535be45159ed75` → `feature/ui-dokument` (LOCKED)
- `~/git/dokumentenmanagement/.claude/worktrees/agent-a898d260b9016e4a5` → `feature/07-22.7-texte-verwaltung` (LOCKED)
- `~/git/dokumentenmanagement/.claude/worktrees/agent-ab5b7b65e4c1053a6` → `feature/07-22.6-texte-suche` (LOCKED)
- `~/git/dokumentenmanagement/.claude/worktrees/agent-ab79cdb9a5b60c952` → `feature/ui-wissen` (LOCKED)
- `~/git/dokumentenmanagement/.claude/worktrees/agent-ab9640480e963159b` → `feature/ui-bausteine` (LOCKED)
- `~/git/dokumentenmanagement/.claude/worktrees/agent-ac8d0c59cafdea205` → `feature/07-22.2-hilfe-seite` (LOCKED)
- `~/git/dokumentenmanagement/.claude/worktrees/agent-ae072ee5136c8e8c9` → `feature/07-22.1-hilfe-katalog` (LOCKED)
