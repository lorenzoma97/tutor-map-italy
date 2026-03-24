#!/usr/bin/env python3
"""
Scarica la geometria reale delle autostrade italiane da OpenStreetMap (Overpass API)
e per ogni tratto Tutor estrae il sottopercorso esatto dalla geometria autostradale.
Nessun routing OSRM necessario — usa direttamente i dati stradali OSM.
"""

import json
import math
import time
from pathlib import Path

import requests

BASE_DIR = Path(__file__).parent
SEGMENTS_FILE = BASE_DIR / "tutor_segments.json"
HIGHWAY_CACHE_DIR = BASE_DIR / "highway_cache"

OVERPASS_URL = "https://overpass-api.de/api/interpreter"


def fetch_highway_geometry(highway_ref):
    """Scarica la geometria completa di un'autostrada da OSM Overpass."""
    cache_file = HIGHWAY_CACHE_DIR / f"{highway_ref}.json"
    if cache_file.exists():
        with open(cache_file, "r") as f:
            return json.load(f)

    print(f"  Scaricamento geometria {highway_ref} da OSM...")
    # Include motorway_link per catturare anche rampe/bretelle
    query = f"""
    [out:json][timeout:180];
    way["highway"~"motorway|motorway_link"]["ref"="{highway_ref}"];
    (._;>;);
    out body;
    """
    data = None
    for attempt in range(3):
        try:
            resp = requests.post(OVERPASS_URL, data={"data": query}, timeout=300)
            resp.raise_for_status()
            data = resp.json()
            break
        except Exception as e:
            print(f"  Tentativo {attempt+1}/3 fallito per {highway_ref}: {e}")
            if attempt < 2:
                wait = 30 * (attempt + 1)
                print(f"  Attendo {wait}s prima di riprovare...")
                time.sleep(wait)
    if data is None:
        return None

    # Costruisci mappa nodi id -> coordinate
    nodes = {}
    ways = []
    for elem in data.get("elements", []):
        if elem["type"] == "node":
            nodes[elem["id"]] = [elem["lat"], elem["lon"]]
        elif elem["type"] == "way":
            ways.append(elem)

    # Estrai tutte le coordinate di tutti i way segments
    all_coords = []
    for way in ways:
        way_coords = []
        for node_id in way.get("nodes", []):
            if node_id in nodes:
                way_coords.append(nodes[node_id])
        if way_coords:
            all_coords.append(way_coords)

    # Filtra solo coordinate in Italia (35-48N, 6-19E)
    all_coords = [
        [c for c in way if 35 < c[0] < 48 and 6 < c[1] < 19]
        for way in all_coords
    ]
    all_coords = [w for w in all_coords if len(w) >= 2]

    result = {"ways": all_coords, "node_count": len(nodes)}

    # Salva cache
    HIGHWAY_CACHE_DIR.mkdir(exist_ok=True)
    with open(cache_file, "w") as f:
        json.dump(result, f)

    print(f"  {highway_ref}: {len(all_coords)} segmenti, {len(nodes)} nodi")
    return result


def haversine(c1, c2):
    """Distanza in km tra due coordinate [lat, lon]."""
    lat1, lon1 = math.radians(c1[0]), math.radians(c1[1])
    lat2, lon2 = math.radians(c2[0]), math.radians(c2[1])
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    a = math.sin(dlat/2)**2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon/2)**2
    return 6371 * 2 * math.asin(math.sqrt(a))


def build_chains(ways):
    """Concatena i way OSM in catene ordinate usando endpoint condivisi.

    I way OSM che condividono lo stesso nodo hanno coordinate identiche
    agli endpoint. Li concateniamo in catene lunghe per avere linestring continue.
    """
    if not ways:
        return []

    # Mappa endpoint -> lista di (way_index, is_start)
    endpoint_map = {}
    for wi, way in enumerate(ways):
        if len(way) < 2:
            continue
        start_key = (round(way[0][0], 7), round(way[0][1], 7))
        end_key = (round(way[-1][0], 7), round(way[-1][1], 7))
        endpoint_map.setdefault(start_key, []).append((wi, True))
        endpoint_map.setdefault(end_key, []).append((wi, False))

    used = set()
    chains = []

    for start_wi in range(len(ways)):
        if start_wi in used or len(ways[start_wi]) < 2:
            continue

        # Costruisci catena in avanti e indietro
        chain = list(ways[start_wi])
        used.add(start_wi)

        # Estendi in avanti (dalla fine della catena)
        for _ in range(len(ways)):
            tail_key = (round(chain[-1][0], 7), round(chain[-1][1], 7))
            found = False
            for wi, is_start in endpoint_map.get(tail_key, []):
                if wi in used:
                    continue
                way = ways[wi]
                if is_start:
                    chain.extend(way[1:])  # skip primo punto (duplicato)
                else:
                    chain.extend(way[-2::-1])  # reversed, skip ultimo (duplicato)
                used.add(wi)
                found = True
                break
            if not found:
                break

        # Estendi all'indietro (dall'inizio della catena)
        for _ in range(len(ways)):
            head_key = (round(chain[0][0], 7), round(chain[0][1], 7))
            found = False
            for wi, is_start in endpoint_map.get(head_key, []):
                if wi in used:
                    continue
                way = ways[wi]
                if is_start:
                    chain = way[-1:0:-1] + chain  # reversed, prepend
                else:
                    chain = way[:-1] + chain  # prepend, skip ultimo (duplicato)
                used.add(wi)
                found = True
                break
            if not found:
                break

        if len(chain) >= 2:
            chains.append(chain)

    # Ordina catene per lunghezza (le piu' lunghe = carreggiate principali)
    chains.sort(key=len, reverse=True)
    return chains


def find_nearest_on_chains(coord, chains):
    """Trova il punto piu' vicino su una lista di catene.
    Ritorna (chain_index, point_index, distance_km)."""
    best_dist = float('inf')
    best_chain = 0
    best_idx = 0
    for ci, chain in enumerate(chains):
        for pi, point in enumerate(chain):
            d = haversine(coord, point)
            if d < best_dist:
                best_dist = d
                best_chain = ci
                best_idx = pi
    return best_chain, best_idx, best_dist


def extract_subpath(chains, start_coord, end_coord, max_snap_dist=25):
    """Estrae il sottopercorso tra due punti lungo le catene autostradali."""
    sc, si, sd = find_nearest_on_chains(start_coord, chains)
    ec, ei, ed = find_nearest_on_chains(end_coord, chains)

    # Se troppo lontano dalla highway, fallback
    if sd > max_snap_dist or ed > max_snap_dist:
        return None

    # Stessa catena: estrai direttamente il sotto-percorso
    if sc == ec:
        chain = chains[sc]
        if si <= ei:
            return chain[si:ei+1]
        else:
            return chain[ei:si+1][::-1]

    # Catene diverse: prendi il percorso dalla catena di start + catena di end
    # Cerca la catena piu' lunga che contiene punti vicini a entrambi
    best_path = None
    best_score = float('inf')

    for ci, chain in enumerate(chains):
        # Trova punti piu' vicini a start e end su questa catena
        best_si, best_sd2 = 0, float('inf')
        best_ei, best_ed2 = 0, float('inf')
        for pi, pt in enumerate(chain):
            ds = haversine(pt, start_coord)
            de = haversine(pt, end_coord)
            if ds < best_sd2:
                best_sd2 = ds
                best_si = pi
            if de < best_ed2:
                best_ed2 = de
                best_ei = pi

        score = best_sd2 + best_ed2
        if score < best_score and best_sd2 < max_snap_dist and best_ed2 < max_snap_dist:
            best_score = score
            if best_si <= best_ei:
                best_path = chain[best_si:best_ei+1]
            else:
                best_path = chain[best_ei:best_si+1][::-1]

    if best_path and len(best_path) >= 2:
        return best_path

    # Fallback: concatena dalla catena start alla catena end
    start_snapped = chains[sc][si]
    end_snapped = chains[ec][ei]
    return [start_snapped, end_snapped]


def main():
    print("=== Aggiornamento tracciati con geometria OSM ===\n")

    with open(SEGMENTS_FILE, "r", encoding="utf-8") as f:
        segments = json.load(f)

    # Raccogli autostrade uniche
    highways = sorted(set(s["highway"] for s in segments),
                      key=lambda x: int(x.replace('A', '')))

    print(f"Autostrade da processare: {', '.join(highways)}\n")

    # Scarica geometrie (con cache)
    highway_geoms = {}
    for hwy in highways:
        geom = fetch_highway_geometry(hwy)
        if geom and geom.get("ways"):
            highway_geoms[hwy] = geom["ways"]
        else:
            print(f"  {hwy}: geometria non disponibile, mantengo tracciati esistenti")
        time.sleep(15)  # Rate limiting generoso per Overpass

    print(f"\nGeometrie caricate: {len(highway_geoms)}/{len(highways)}")

    # Costruisci catene connesse per ogni autostrada
    highway_chains = {}
    for hwy, ways in highway_geoms.items():
        chains = build_chains(ways)
        highway_chains[hwy] = chains
        total_pts = sum(len(c) for c in chains)
        print(f"  {hwy}: {len(chains)} catene, {total_pts} punti totali")

    # Aggiorna tracciati
    updated = 0
    kept = 0
    for seg in segments:
        hwy = seg["highway"]
        if hwy not in highway_chains:
            kept += 1
            continue

        start = seg.get("start_coords")
        end = seg.get("end_coords")
        if not start or not end:
            kept += 1
            continue

        # Skip tratti con fix manuale
        if "Ravenna Nord" in seg.get("start_name", "") and "Imola" in seg.get("end_name", ""):
            kept += 1
            continue

        subpath = extract_subpath(highway_chains[hwy], start, end)
        if subpath and len(subpath) >= 2:
            seg["route_coords"] = subpath
            # Aggiorna anche le coordinate di inizio/fine ai punti snappati
            seg["start_coords"] = subpath[0]
            seg["end_coords"] = subpath[-1]
            updated += 1
        else:
            print(f"  SKIP: {hwy} {seg.get('start_name')} -> {seg.get('end_name')} (subpath={subpath})")
            kept += 1

    print(f"\nTracciati aggiornati: {updated}")
    print(f"Tracciati mantenuti: {kept}")

    # Salva
    with open(SEGMENTS_FILE, "w", encoding="utf-8") as f:
        json.dump(segments, f, indent=2, ensure_ascii=False)

    print(f"\nSalvato in {SEGMENTS_FILE}")
    print("Ricarica la mappa nel browser per vedere i risultati.")


if __name__ == "__main__":
    main()
