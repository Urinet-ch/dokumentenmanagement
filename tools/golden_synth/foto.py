"""Simulierte Handy-Fotos: Beleg rendern, auf Tisch legen, Perspektive, Licht, Schatten, Unschärfe."""

from __future__ import annotations

import io
import math

import cv2
import numpy as np
import pypdfium2 as pdfium
from PIL import Image

from .modell import FotoParameter


def pdf_seite(pdf: bytes, zielhoehe_px: int, seite: int = 0) -> np.ndarray:
    dokument = pdfium.PdfDocument(pdf)
    blatt = dokument[seite]
    massstab = zielhoehe_px / blatt.get_height()
    bild = blatt.render(scale=massstab).to_numpy()[:, :, :3].copy()
    dokument.close()
    return bild


def _rauschfeld(h: int, w: int, raster: int, rng: np.random.Generator) -> np.ndarray:
    grob = rng.normal(size=(max(2, h // raster + 2), max(2, w // raster + 2))).astype(np.float32)
    return cv2.resize(grob, (w, h), interpolation=cv2.INTER_CUBIC)


def _tisch(h: int, w: int, rng: np.random.Generator) -> np.ndarray:
    art = rng.integers(0, 3)
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    if art == 0:  # Holz
        maser = np.sin(x * 0.015 + _rauschfeld(h, w, 300, rng) * 1.5 + y * 0.001)
        basis = np.array([128, 88, 56], np.float32)
        bild = basis * (0.85 + 0.12 * maser[..., None])
    elif art == 1:  # graue Tischplatte
        basis = np.array([168, 166, 160], np.float32)
        bild = basis * (0.95 + 0.03 * _rauschfeld(h, w, 120, rng)[..., None])
    else:  # dunkler Schreibtisch
        basis = np.array([52, 50, 54], np.float32)
        bild = basis * (0.95 + 0.05 * _rauschfeld(h, w, 200, rng)[..., None])
    bild += rng.normal(0, 3, size=bild.shape).astype(np.float32)
    return np.clip(bild, 0, 255)


def _zerknittern(bild: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    h, w = bild.shape[:2]
    hoehe = _rauschfeld(h, w, max(h, w) // 9, rng) + 0.35 * _rauschfeld(h, w, max(h, w) // 25, rng)
    gy, gx = np.gradient(hoehe)
    staerke = 0.006 * max(h, w)
    karte_x, karte_y = np.meshgrid(np.arange(w, dtype=np.float32), np.arange(h, dtype=np.float32))
    verzerrt = cv2.remap(bild, karte_x + hoehe * staerke * 0.2, karte_y + hoehe * staerke * 0.2,
                         cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
    licht = 1.0 + np.clip((gx + gy) * 10.0, -0.25, 0.18)
    return np.clip(verzerrt.astype(np.float32) * licht[..., None], 0, 255).astype(np.uint8)


def _ecken(dh: int, dw: int, zentrum: tuple[float, float], winkel: float, rng: np.random.Generator) -> np.ndarray:
    cx, cy = zentrum
    neigung = math.radians(winkel)
    oben = 1.0 - math.sin(neigung) * 0.5
    hoehe = dh * math.cos(neigung * 0.6)
    punkte = np.array([[-dw / 2 * oben, -hoehe / 2], [dw / 2 * oben, -hoehe / 2],
                       [dw / 2, hoehe / 2], [-dw / 2, hoehe / 2]], np.float32)
    drehung = math.radians(rng.uniform(-6, 6))
    r = np.array([[math.cos(drehung), -math.sin(drehung)], [math.sin(drehung), math.cos(drehung)]], np.float32)
    punkte = punkte @ r.T
    punkte += rng.uniform(-0.01, 0.01, size=punkte.shape).astype(np.float32) * max(dw, dh)
    return punkte + np.array([cx, cy], np.float32)


def _auflegen(tisch: np.ndarray, dok: np.ndarray, ecken: np.ndarray) -> np.ndarray:
    h, w = tisch.shape[:2]
    dh, dw = dok.shape[:2]
    quelle = np.array([[0, 0], [dw, 0], [dw, dh], [0, dh]], np.float32)
    matrix = cv2.getPerspectiveTransform(quelle, ecken)
    verzogen = cv2.warpPerspective(dok, matrix, (w, h), flags=cv2.INTER_AREA)
    maske = cv2.warpPerspective(np.full((dh, dw), 255, np.uint8), matrix, (w, h)).astype(np.float32) / 255
    schatten = cv2.GaussianBlur(np.roll(maske, (int(h * 0.006), int(w * 0.008)), axis=(0, 1)), (0, 0), w * 0.008)
    tisch = tisch * (1 - 0.45 * schatten[..., None])
    return tisch * (1 - maske[..., None]) + verzogen.astype(np.float32) * maske[..., None]


def fotografiere(pdf: bytes, p: FotoParameter, rng: np.random.Generator) -> bytes:
    lange = int(rng.integers(2480, 4033))
    h, w = lange, int(lange * 0.75)
    bild = _tisch(h, w, rng)

    zielhoehe = int(h * (0.62 if p.zweiter_beleg else 0.78))
    dok = pdf_seite(pdf, zielhoehe)
    if dok.shape[1] > w * 0.82 and not p.zweiter_beleg:
        dok = pdf_seite(pdf, int(zielhoehe * w * 0.82 / dok.shape[1]))
    if p.zerknittert:
        dok = _zerknittern(dok, rng)

    if p.zweiter_beleg:
        hinten = pdf_seite(p.zweiter_beleg(), int(h * 0.6))
        bild = _auflegen(bild, hinten, _ecken(*hinten.shape[:2], (w * 0.33, h * 0.44), p.winkel * 0.6, rng))
        zentrum = (w * 0.64, h * 0.55)
    else:
        zentrum = (w * rng.uniform(0.47, 0.53), h * rng.uniform(0.48, 0.53))
    bild = _auflegen(bild, dok, _ecken(*dok.shape[:2], zentrum, p.winkel, rng))

    if p.ungleiches_licht:
        y, x = np.mgrid[0:h, 0:w].astype(np.float32)
        lx, ly = rng.uniform(0, w), rng.uniform(0, h)
        abstand = np.sqrt((x - lx) ** 2 + (y - ly) ** 2) / math.hypot(w, h)
        bild *= (1.12 - 0.55 * abstand)[..., None]
    if p.schatten:
        maske = np.zeros((h, w), np.float32)
        x0 = rng.uniform(0.0, 0.3) * w
        polygon = np.array([[x0, 0], [x0 + w * 0.45, 0], [x0 + w * 0.25, h * 0.55], [x0 - w * 0.1, h * 0.45]], np.int32)
        cv2.fillPoly(maske, [polygon], 1.0)
        maske = cv2.GaussianBlur(maske, (0, 0), w * 0.02)
        bild *= (1 - 0.42 * maske)[..., None]
    if p.unschaerfe > 0:
        bild = cv2.GaussianBlur(bild, (0, 0), p.unschaerfe)
    bild += rng.normal(0, 3.5, size=bild.shape).astype(np.float32)
    bild = np.clip(bild, 0, 255).astype(np.uint8)
    if p.drehung:
        bild = np.ascontiguousarray(np.rot90(bild, k=p.drehung // 90))

    puffer = io.BytesIO()
    if p.format == "heic":
        import pillow_heif

        pillow_heif.register_heif_opener()
        Image.fromarray(bild).save(puffer, format="HEIF", quality=82)
    else:
        Image.fromarray(bild).save(puffer, format="JPEG", quality=88, optimize=True)
    return puffer.getvalue()
