#!/usr/bin/env python3
"""
Genera file KML dai dati tutor per importazione in Google Maps / Google Earth.
"""

import json
import math
from pathlib import Path

import simplekml


def simplify_coords(coords, tolerance_km=0.15):
    """Semplifica una lista di coordinate con algoritmo Douglas-Peucker."""
    if len(coords) <= 2:
        return coords

    def point_line_dist(pt, start, end):
        """Distanza approssimata di un punto da una linea (in gradi, veloce)."""
        dx = end[1] - start[1]
        dy = end[0] - start[0]
        if dx == 0 and dy == 0:
            return math.sqrt((pt[0]-start[0])**2 + (pt[1]-start[1])**2)
        t = max(0, min(1, ((pt[1]-start[1])*dx + (pt[0]-start[0])*dy) / (dx*dx + dy*dy)))
        proj = [start[0] + t*dy, start[1] + t*dx]
        # Approssimazione: 1 grado ~ 111 km
        return math.sqrt(((pt[0]-proj[0])*111)**2 + ((pt[1]-proj[1])*111*math.cos(math.radians(pt[0])))**2)

    # Douglas-Peucker
    max_dist = 0
    max_idx = 0
    for i in range(1, len(coords)-1):
        d = point_line_dist(coords[i], coords[0], coords[-1])
        if d > max_dist:
            max_dist = d
            max_idx = i

    if max_dist > tolerance_km:
        left = simplify_coords(coords[:max_idx+1], tolerance_km)
        right = simplify_coords(coords[max_idx:], tolerance_km)
        return left[:-1] + right
    else:
        return [coords[0], coords[-1]]

BASE_DIR = Path(__file__).parent
INPUT_FILE = BASE_DIR / "tutor_segments.json"
OUTPUT_FILE = BASE_DIR / "tutor.kml"

# Colori KML (formato AABBGGRR)
HIGHWAY_COLORS = {
    'A1':  'ff3539e5',  # rosso
    'A4':  'ffe5881e',  # blu
    'A5':  'ff414c6d',  # marrone
    'A6':  'ffaa248e',  # viola
    'A7':  'ff7b8900',  # teal
    'A8':  'ff1e51f4',  # arancio
    'A9':  'ffab4939',  # indaco
    'A10': 'ff33cac0',  # lime
    'A11': 'ffc1ac00',  # ciano
    'A13': 'ff42b37c',  # verde
    'A14': 'ff47a043',  # verde scuro
    'A16': 'ff008fff',  # ambra
    'A23': 'ffc06b5c',  # indaco chiaro
    'A26': 'ff9aa626',  # verde acqua
    'A27': 'ff006cef',  # arancio
    'A28': 'ff9c9078',  # grigio
    'A30': 'ff601bd8',  # rosa
    'A56': 'ffb0279c',  # porpora
}


# Raggruppamento macro-area per stare entro 10 livelli Google My Maps
AREA_GROUPS = {
    'Nord-Ovest': ['A4', 'A5', 'A6', 'A7', 'A8', 'A9', 'A10', 'A26', 'A28'],
    'Nord-Est': ['A13', 'A23', 'A27', 'A30'],
    'A1 Milano-Napoli': ['A1'],
    'A14 Adriatica': ['A14'],
    'Centro-Sud': ['A11', 'A16', 'A56'],
}

def get_area(highway):
    for area, hwys in AREA_GROUPS.items():
        if highway in hwys:
            return area
    return 'Altro'


def main():
    print("📄 Generazione file KML...\n")

    with open(INPUT_FILE, "r", encoding="utf-8") as f:
        segments = json.load(f)

    kml = simplekml.Kml(name="Tutor Autostradali Italia")
    kml.document.description = (
        "Mappa dei tratti autostradali controllati dal sistema Tutor in Italia.\n"
        "Dati: Autostrade per l'Italia / Polizia di Stato"
    )

    # Raggruppa per macro-area
    areas = {}
    for seg in segments:
        area = get_area(seg["highway"])
        areas.setdefault(area, []).append(seg)

    area_order = list(AREA_GROUPS.keys()) + ['Altro']

    for area in area_order:
        segs = areas.get(area, [])
        if not segs:
            continue
        folder = kml.newfolder(name=f"{area} ({len(segs)} tratti)")

        for seg in segs:
            if not seg.get("start_coords") or not seg.get("end_coords"):
                continue

            hwy = seg["highway"]
            color = HIGHWAY_COLORS.get(hwy, 'ff999999')

            # Nome del tratto
            km_info = ""
            if seg.get("start_km") is not None and seg.get("end_km") is not None:
                km_info = f" (km {seg['start_km']} → {seg['end_km']})"

            name = f"{hwy} {seg['start_name']} → {seg['end_name']}{km_info}"

            # Tipo
            type_label = "TUTOR 3.0" if seg.get("type") == "tutor_3.0" else "Standard"

            # Descrizione
            desc_parts = [
                f"Autostrada: {hwy}",
                f"Da: {seg['start_name']}",
                f"A: {seg['end_name']}",
                f"Direzione: {seg['direction']}",
                f"Tipo: {type_label}",
            ]
            if seg.get("start_km") is not None and seg.get("end_km") is not None:
                length = abs(seg["end_km"] - seg["start_km"])
                desc_parts.append(f"km inizio: {seg['start_km']}")
                desc_parts.append(f"km fine: {seg['end_km']}")
                desc_parts.append(f"Lunghezza: {length:.1f} km")

            description = "\n".join(desc_parts)

            # Crea LineString
            linestring = folder.newlinestring(name=name, description=description)
            if seg.get("route_coords") and len(seg["route_coords"]) > 2:
                simplified = simplify_coords(seg["route_coords"])
                linestring.coords = [(c[1], c[0]) for c in simplified]
            else:
                linestring.coords = [
                    (seg["start_coords"][1], seg["start_coords"][0]),
                    (seg["end_coords"][1], seg["end_coords"][0]),
                ]

            # Stile
            linestring.style.linestyle.color = color
            linestring.style.linestyle.width = 4 if seg.get("type") != "tutor_3.0" else 5

    kml.save(str(OUTPUT_FILE))
    num_areas = len([a for a in area_order if areas.get(a)])
    print(f"✅ KML salvato: {OUTPUT_FILE}")
    print(f"   {len(segments)} tratti in {num_areas} livelli")
    print(f"\n📱 Per importare in Google Maps app:")
    print(f"   1. Apri Google Maps → Menu → I tuoi luoghi → Mappe")
    print(f"   2. Tocca 'Crea mappa' (oppure usa Google My Maps)")
    print(f"   3. Importa il file tutor.kml")


if __name__ == "__main__":
    main()
