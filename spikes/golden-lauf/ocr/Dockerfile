# OCRmyPDF mit Italienisch und poppler (pdftotext, pdftoppm) für den Golden-Lauf (Aufgabe 0.5).
FROM jbarlow83/ocrmypdf:v17.13.0
USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends tesseract-ocr-ita poppler-utils \
 && rm -rf /var/lib/apt/lists/*
