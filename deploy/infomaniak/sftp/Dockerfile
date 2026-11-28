# Dienst «sftp»: Scan-Eingang für den Netzwerkscanner (Spec 09, design.md Abschnitt 1, ADR-016). Prototyp für den
# Spike 27.4; Compose, Härtung und Deploy folgen mit den Aufgaben 28.2 bis 28.4.
# Bauen: mvn -B -ntp -f deploy/infomaniak/sftp/pom.xml package && docker build -t archiv-sftp:lokal deploy/infomaniak/sftp
# Basis wie das Image der API (backend/src/main/docker/Dockerfile.jvm), per Digest gepinnt.
FROM eclipse-temurin:25.0.5.1_2-jre-noble@sha256:2ec98aa522c472deadfd2b7c5b39bca680e33ef522a53a72f10789bdf89a6bb5

COPY target/scan-eingang-sftp.jar /opt/archiv-sftp/scan-eingang-sftp.jar
# Wurzel des Eingangs; im Betrieb das Volume archiv_eingang, lokal ein eingehängter Ordner
RUN mkdir -p /srv/eingang && chown 10001:0 /srv/eingang && chmod 0770 /srv/eingang

# Ohne Root (numerische uid wie die API); der Port liegt darum über 1024 (ARCHIV_SFTP_PORT, Standard 2222)
USER 10001
EXPOSE 2222
# Heap 48 MiB nach dem Vorschlag in design.md (E11: mem_limit 96m)
ENTRYPOINT ["java", "-Xmx48m", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", "-jar", "/opt/archiv-sftp/scan-eingang-sftp.jar"]
