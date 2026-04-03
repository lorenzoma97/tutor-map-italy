#!/usr/bin/env python3
"""
Genera tracciati precisi per ogni tratto Tutor usando geometria OSM.

Approccio:
1. Scarica la geometria autostradale da OpenStreetMap (Overpass API)
2. Costruisce catene di way connessi e filtra SOLO le carreggiate principali
   (catene lunghe con >= 50 punti), escludendo rampe/bretelle/svincoli
3. Per ogni tratto Tutor, usa le coordinate caselli (caselli_coords.json)
   come fonte autorevole, le proietta sulla carreggiata principale,
   e estrae il sottopercorso esatto
4. Valida la lunghezza del tracciato contro i km_tutor dichiarati

Il risultato sono tracciati che seguono esattamente la carreggiata autostradale.
"""

import json
import math
import time
from pathlib import Path

import requests

BASE_DIR = Path(__file__).parent
SEGMENTS_FILE = BASE_DIR / "tutor_segments.json"
CASELLI_FILE = BASE_DIR / "caselli_coords.json"
HIGHWAY_CACHE_DIR = BASE_DIR / "highway_cache"

OVERPASS_URL = "https://overpass-api.de/api/interpreter"


# ======================== Utility ========================

def haversine(c1, c2):
    """Distanza in km tra due coordinate [lat, lon]."""
    lat1, lon1 = math.radians(c1[0]), math.radians(c1[1])
    lat2, lon2 = math.radians(c2[0]), math.radians(c2[1])
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    a = math.sin(dlat/2)**2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon/2)**2
    return 6371 * 2 * math.asin(math.sqrt(a))


def path_length(coords):
    """Lunghezza totale di un percorso in km."""
    return sum(haversine(coords[i], coords[i+1]) for i in range(len(coords)-1))


# ======================== OSM Geometry ========================

def fetch_highway_geometry(highway_ref):
    """Scarica la geometria completa di un'autostrada da OSM Overpass (con cache)."""
    cache_file = HIGHWAY_CACHE_DIR / f"{highway_ref}.json"
    if cache_file.exists():
        with open(cache_file, "r") as f:
            return json.load(f)

    print(f"  Scaricamento {highway_ref} da OSM...")
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
            print(f"    Tentativo {attempt+1}/3 fallito: {e}")
            if attempt < 2:
                time.sleep(30 * (attempt + 1))

    if data is None:
        return None

    nodes = {}
    ways = []
    for elem in data.get("elements", []):
        if elem["type"] == "node":
            nodes[elem["id"]] = [elem["lat"], elem["lon"]]
        elif elem["type"] == "way":
            ways.append(elem)

    all_coords = []
    for way in ways:
        way_coords = [nodes[nid] for nid in way.get("nodes", []) if nid in nodes]
        if len(way_coords) >= 2:
            all_coords.append(way_coords)

    all_coords = [
        [c for c in w if 35 < c[0] < 48 and 6 < c[1] < 19]
        for w in all_coords
    ]
    all_coords = [w for w in all_coords if len(w) >= 2]

    result = {"ways": all_coords, "node_count": len(nodes)}
    HIGHWAY_CACHE_DIR.mkdir(exist_ok=True)
    with open(cache_file, "w") as f:
        json.dump(result, f)

    return result


# ======================== Chain Building ========================

def build_chains(ways):
    """Concatena i way OSM in catene ordinate usando endpoint condivisi."""
    if not ways:
        return []

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

        chain = list(ways[start_wi])
        used.add(start_wi)

        # Estendi in avanti
        for _ in range(len(ways)):
            tail_key = (round(chain[-1][0], 7), round(chain[-1][1], 7))
            found = False
            for wi, is_start in endpoint_map.get(tail_key, []):
                if wi in used:
                    continue
                way = ways[wi]
                if is_start:
                    chain.extend(way[1:])
                else:
                    chain.extend(way[-2::-1])
                used.add(wi)
                found = True
                break
            if not found:
                break

        # Estendi all'indietro
        for _ in range(len(ways)):
            head_key = (round(chain[0][0], 7), round(chain[0][1], 7))
            found = False
            for wi, is_start in endpoint_map.get(head_key, []):
                if wi in used:
                    continue
                way = ways[wi]
                if is_start:
                    chain = way[-1:0:-1] + chain
                else:
                    chain = way[:-1] + chain
                used.add(wi)
                found = True
                break
            if not found:
                break

        if len(chain) >= 2:
            chains.append(chain)

    chains.sort(key=len, reverse=True)
    return chains


def get_major_chains(ways, min_points=50):
    """
    Costruisce catene e filtra SOLO le carreggiate principali.
    Le catene corte (rampe, bretelle, svincoli) vengono escluse.
    Ritorna solo catene con >= min_points punti.
    """
    all_chains = build_chains(ways)
    major = [c for c in all_chains if len(c) >= min_points]
    # Fallback: se nessuna catena e' abbastanza lunga, prendi le top 3
    if not major and all_chains:
        major = all_chains[:3]
    return major


# ======================== Snapping & Extraction ========================

def find_nearest_on_chains(coord, chains):
    """Trova il punto piu' vicino su una lista di catene.
    Ritorna (chain_idx, point_idx, distance_km)."""
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


def extract_subpath(chains, start_coord, end_coord):
    """
    Estrae il sottopercorso tra due punti lungo le catene autostradali.
    Cerca la singola catena che minimizza la somma delle distanze di snap.
    Non ha limite di distanza: i caselli possono essere lontani dalla carreggiata
    (es. pedaggi su rampe lunghe) ma la proiezione e' comunque corretta.
    """
    # Cerca la catena migliore: quella che minimizza snap_start + snap_end
    best_path = None
    best_score = float('inf')
    best_start_dist = 0
    best_end_dist = 0

    for ci, chain in enumerate(chains):
        # Trova punti piu' vicini su questa catena
        best_si, best_sd = 0, float('inf')
        best_ei, best_ed = 0, float('inf')
        for pi, pt in enumerate(chain):
            ds = haversine(pt, start_coord)
            de = haversine(pt, end_coord)
            if ds < best_sd:
                best_sd = ds
                best_si = pi
            if de < best_ed:
                best_ed = de
                best_ei = pi

        score = best_sd + best_ed
        if score < best_score and best_si != best_ei:
            best_score = score
            best_start_dist = best_sd
            best_end_dist = best_ed
            if best_si <= best_ei:
                best_path = chain[best_si:best_ei+1]
            else:
                best_path = chain[best_ei:best_si+1][::-1]

    return best_path, best_start_dist, best_end_dist


# ======================== Main ========================

def main():
    print("=== Generazione tracciati precisi con geometria OSM ===\n")

    with open(SEGMENTS_FILE, "r", encoding="utf-8") as f:
        segments = json.load(f)

    caselli = {}
    if CASELLI_FILE.exists():
        with open(CASELLI_FILE, "r", encoding="utf-8") as f:
            caselli = json.load(f)
        print(f"Caselli: {sum(len(v) for v in caselli.values())} punti")

    print(f"Segmenti: {len(segments)}\n")

    # Carica e costruisci catene principali per ogni autostrada
    highways = sorted(set(s["highway"] for s in segments),
                      key=lambda x: int(x.replace('A', '')))

    highway_chains = {}
    for hwy in highways:
        geom = fetch_highway_geometry(hwy)
        if geom and geom.get("ways"):
            chains = get_major_chains(geom["ways"])
            highway_chains[hwy] = chains
            total_pts = sum(len(c) for c in chains)
            print(f"  {hwy}: {len(chains)} carreggiate, {total_pts} punti")
        time.sleep(0.5)

    print()

    # Processa ogni segmento
    updated = 0
    ok_count = 0
    warn_count = 0
    skip_count = 0

    for i, seg in enumerate(segments):
        hwy = seg["highway"]
        start_name = seg.get("start_name", "")
        end_name = seg.get("end_name", "")

        if hwy not in highway_chains:
            skip_count += 1
            continue

        # km_tutor per validazione
        start_km = seg.get("start_km")
        end_km = seg.get("end_km")
        km_tutor = abs(end_km - start_km) if start_km is not None and end_km is not None else 0

        # Coordinate autorevoli da caselli_coords.json
        hwy_caselli = caselli.get(hwy, {})
        start = hwy_caselli.get(start_name) or seg.get("start_coords")
        end = hwy_caselli.get(end_name) or seg.get("end_coords")

        if not start or not end:
            skip_count += 1
            continue

        # === Fix manuali A14: tratto Ravenna-Imola-CSP (gap OSM senza ref=A14) ===

        # Ravenna Nord -> Imola
        if "Ravenna Nord" in start_name and "Imola" in end_name:
            seg["route_coords"] = [
                [44.3459525, 11.8398457], [44.3461285, 11.8394358],
                [44.3556216, 11.81047], [44.3619356, 11.7905368],
                [44.3621284, 11.7900512], [44.3638427, 11.7861793],
                [44.3642019, 11.7854049], [44.3783383, 11.7488552],
                [44.3816949, 11.7395166], [44.3818684, 11.7390469],
            ]
            seg["start_coords"] = seg["route_coords"][0]
            seg["end_coords"] = seg["route_coords"][-1]
            updated += 1
            ok_count += 1
            continue

        # Imola -> Castel San Pietro (Nord) — percorso completo attraverso gap OSM
        if "Imola" in start_name and "Castel San Pietro" in end_name:
            seg["route_coords"] = [
                [44.3857993, 11.7280814], [44.3871769, 11.7241912],
                [44.3884650, 11.7206721], [44.3898450, 11.7167239],
                [44.3911331, 11.7130504], [44.3919794, 11.7107758],
                [44.3934023, 11.7076259], [44.3953342, 11.7038665],
                [44.3968674, 11.7007594], [44.3979529, 11.6980729],
                [44.3999950, 11.6925969], [44.4017554, 11.6877357],
                [44.4036475, 11.6827092], [44.4049313, 11.6791902],
                [44.4066482, 11.6746669], [44.4079481, 11.6711478],
                [44.4092971, 11.6675858], [44.4107197, 11.6628994],
                [44.4113572, 11.6595778], [44.4117502, 11.6562568],
                [44.4125966, 11.6491148], [44.4130248, 11.6456839],
                [44.4139886, 11.6379160], [44.4144106, 11.6343179],
                [44.4148641, 11.6311679], [44.4156173, 11.6286652],
                [44.4169575, 11.6262437], [44.4187386, 11.6233573],
                [44.4200628, 11.6213145], [44.4215340, 11.6190658],
                [44.4229629, 11.6165721], [44.4239314, 11.6144178],
                [44.4251451, 11.6107785], [44.4260707, 11.6076114],
                [44.4267781, 11.6051282], [44.4282713, 11.5998789],
            ]
            seg["start_coords"] = seg["route_coords"][0]
            seg["end_coords"] = seg["route_coords"][-1]
            updated += 1
            ok_count += 1
            continue

        # Castel San Pietro -> Imola (Sud) — percorso invertito
        if "Castel San Pietro" in start_name and "Imola" in end_name:
            seg["route_coords"] = [
                [44.4282713, 11.5998789], [44.4267781, 11.6051282],
                [44.4260707, 11.6076114], [44.4251451, 11.6107785],
                [44.4239314, 11.6144178], [44.4229629, 11.6165721],
                [44.4215340, 11.6190658], [44.4200628, 11.6213145],
                [44.4187386, 11.6233573], [44.4169575, 11.6262437],
                [44.4156173, 11.6286652], [44.4148641, 11.6311679],
                [44.4144106, 11.6343179], [44.4139886, 11.6379160],
                [44.4130248, 11.6456839], [44.4125966, 11.6491148],
                [44.4117502, 11.6562568], [44.4113572, 11.6595778],
                [44.4107197, 11.6628994], [44.4092971, 11.6675858],
                [44.4079481, 11.6711478], [44.4066482, 11.6746669],
                [44.4049313, 11.6791902], [44.4036475, 11.6827092],
                [44.4017554, 11.6877357], [44.3999950, 11.6925969],
                [44.3979529, 11.6980729], [44.3968674, 11.7007594],
                [44.3953342, 11.7038665], [44.3934023, 11.7076259],
                [44.3919794, 11.7107758], [44.3911331, 11.7130504],
                [44.3898450, 11.7167239], [44.3884650, 11.7206721],
                [44.3871769, 11.7241912], [44.3857993, 11.7280814],
            ]
            seg["start_coords"] = seg["route_coords"][0]
            seg["end_coords"] = seg["route_coords"][-1]
            updated += 1
            ok_count += 1
            continue

        subpath, snap_s, snap_e = extract_subpath(highway_chains[hwy], start, end)

        if not subpath or len(subpath) < 2:
            print(f"  SKIP: {hwy} {start_name} -> {end_name} (subpath vuoto)")
            skip_count += 1
            continue

        seg["route_coords"] = subpath
        seg["start_coords"] = subpath[0]
        seg["end_coords"] = subpath[-1]
        updated += 1

        # Validazione
        route_km = path_length(subpath)
        if km_tutor > 0:
            ratio = route_km / km_tutor
            if ratio < 0.5 or ratio > 2.0:
                warn_count += 1
                print(f"  WARN: {hwy} {start_name} -> {end_name} "
                      f"- route {route_km:.1f}km vs tutor {km_tutor:.1f}km "
                      f"(x{ratio:.2f}, snap {snap_s:.1f}/{snap_e:.1f}km)")
            else:
                ok_count += 1

    print(f"\n{'='*50}")
    print(f"Aggiornati:    {updated}/{len(segments)}")
    print(f"OK distanza:   {ok_count}")
    print(f"WARN distanza: {warn_count}")
    print(f"Saltati:       {skip_count}")

    with open(SEGMENTS_FILE, "w", encoding="utf-8") as f:
        json.dump(segments, f, indent=2, ensure_ascii=False)

    print(f"\nSalvato in {SEGMENTS_FILE}")


if __name__ == "__main__":
    main()
