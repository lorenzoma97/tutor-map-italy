#!/usr/bin/env python3
"""
Genera file KML dai dati tutor per importazione in Google Maps / Google Earth.
"""

import json
from pathlib import Path

import simplekml

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


def main():
    print("📄 Generazione file KML...\n")

    with open(INPUT_FILE, "r", encoding="utf-8") as f:
        segments = json.load(f)

    kml = simplekml.Kml(name="Tutor Autostradali Italia")
    kml.document.description = (
        "Mappa dei tratti autostradali controllati dal sistema Tutor in Italia.\n"
        "Dati: Autostrade per l'Italia / Polizia di Stato"
    )

    # Raggruppa per autostrada
    highways = {}
    for seg in segments:
        hwy = seg["highway"]
        if hwy not in highways:
            highways[hwy] = []
        highways[hwy].append(seg)

    # Ordina autostrade
    sorted_hwys = sorted(highways.keys(), key=lambda x: int(x.replace('A', '')))

    for hwy in sorted_hwys:
        segs = highways[hwy]
        folder = kml.newfolder(name=f"{hwy} ({len(segs)} tratti)")

        color = HIGHWAY_COLORS.get(hwy, 'ff999999')

        for seg in segs:
            if not seg.get("start_coords") or not seg.get("end_coords"):
                continue

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

            # Crea LineString — usa route_coords (tracciato reale) se disponibile
            linestring = folder.newlinestring(name=name, description=description)
            if seg.get("route_coords") and len(seg["route_coords"]) > 2:
                linestring.coords = [(c[1], c[0]) for c in seg["route_coords"]]  # KML: lon, lat
            else:
                linestring.coords = [
                    (seg["start_coords"][1], seg["start_coords"][0]),
                    (seg["end_coords"][1], seg["end_coords"][0]),
                ]

            # Stile
            linestring.style.linestyle.color = color
            linestring.style.linestyle.width = 4 if seg.get("type") != "tutor_3.0" else 5

            # Placemark inizio
            start_pm = folder.newpoint(
                name=f"📍 {seg['start_name']}" + (f" km {seg['start_km']}" if seg.get('start_km') else ""),
                coords=[(seg["start_coords"][1], seg["start_coords"][0])]
            )
            start_pm.style.iconstyle.scale = 0.6
            start_pm.style.iconstyle.icon.href = 'http://maps.google.com/mapfiles/kml/paddle/grn-circle.png'

            # Placemark fine
            end_pm = folder.newpoint(
                name=f"🏁 {seg['end_name']}" + (f" km {seg['end_km']}" if seg.get('end_km') else ""),
                coords=[(seg["end_coords"][1], seg["end_coords"][0])]
            )
            end_pm.style.iconstyle.scale = 0.6
            end_pm.style.iconstyle.icon.href = 'http://maps.google.com/mapfiles/kml/paddle/red-circle.png'

    kml.save(str(OUTPUT_FILE))
    print(f"✅ KML salvato: {OUTPUT_FILE}")
    print(f"   {len(segments)} tratti su {len(sorted_hwys)} autostrade")
    print(f"\n📱 Per importare in Google Maps app:")
    print(f"   1. Apri Google Maps → Menu → I tuoi luoghi → Mappe")
    print(f"   2. Tocca 'Crea mappa' (oppure usa Google My Maps)")
    print(f"   3. Importa il file tutor.kml")


if __name__ == "__main__":
    main()
