# language: de
@spec-01
Funktionalität: Leitplanken gegen Secrets im Repository

  # Teilnachweis (Anteil 1.8): Secrets kommen nie ins Repository; das Lesen aus /run/secrets folgt mit den Adaptern.
  @req-16.9
  Szenario: Ein Commit mit Secret wird lokal abgewiesen
    Dann prüft der Hook «.githooks/pre-commit» jeden Commit mit gitleaks und bricht ohne gitleaks ab

  @req-16.9
  Szenario: Jeder Push wird in der CI auf Secrets geprüft
    Dann prüft der Workflow «.github/workflows/secret-scan.yml» jeden Push auf allen Branches mit gitleaks

  @req-16.9
  Szenario: main ist lokal gesperrt
    Dann weist der Hook «.githooks/pre-push» einen Push auf «main» ab
