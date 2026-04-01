#!/usr/bin/env python3
"""
Scraper multi-fonte per tratti Tutor autostradali italiani.
Fonte primaria: Autostrade per l'Italia (km esatti)
Fonte secondaria: Sicurauto.it (lista completa + Tutor 3.0)
"""

import json
import os
import re
import sys
import tempfile
from datetime import datetime
from pathlib import Path

import requests
from bs4 import BeautifulSoup

BASE_DIR = Path(__file__).parent
CASELLI_FILE = BASE_DIR / "caselli_coords.json"
OUTPUT_FILE = BASE_DIR / "tutor_segments.json"
METADATA_FILE = BASE_DIR / "metadata.json"
LOG_FILE = BASE_DIR / "scraper_log.json"
ALERTS_FILE = BASE_DIR / "scraper_alerts.json"

AUTOSTRADE_URL = "https://www.autostrade.it/it/tecnologia-sicurezza/sicurezza/il-tutor"
SICURAUTO_URL = "https://www.sicurauto.it/news/traffico-e-viabilita/tutor-autostrade-mappa-dispositivi-attivi/"
POLIZIA_PAGE_URL = "https://www.poliziadistato.it/articolo/tutor"

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}


def load_caselli_coords():
    with open(CASELLI_FILE, "r", encoding="utf-8") as f:
        return json.load(f)


def normalize_name(name):
    """Normalizza il nome di un casello per il matching."""
    name = name.strip()
    # Rimuovi prefissi direzione
    name = re.sub(r'\s*Dir\.?\s*(Nord|Sud|Est|Ovest|Francia|Italia)\s*', '', name, flags=re.IGNORECASE)
    # Rimuovi suffissi km
    name = re.sub(r'\s*km\s*[\d.,+]+', '', name, flags=re.IGNORECASE)
    # Normalizza spazi
    name = re.sub(r'\s+', ' ', name).strip()
    return name


def extract_highway_code(highway_text):
    """Estrae il codice autostrada (es. 'A1', 'A14') dal testo."""
    match = re.search(r'(A\d+)', highway_text)
    return match.group(1) if match else highway_text.strip()


def parse_km(km_text):
    """Converte testo km in float."""
    if not km_text:
        return None
    km_text = str(km_text).strip().replace(',', '.').replace('+', '.')
    # Rimuovi caratteri non numerici tranne il punto
    km_text = re.sub(r'[^\d.]', '', km_text)
    try:
        return float(km_text)
    except (ValueError, TypeError):
        return None


def find_coords(caselli_db, highway_code, casello_name):
    """Cerca le coordinate di un casello nel database."""
    name = normalize_name(casello_name)

    # Mappatura varianti autostrada
    highway_keys = [highway_code]
    if highway_code == "A1":
        highway_keys.extend(["A1_Diramazione_Nord", "A1_Diramazione_Sud", "A1_Variante_Valico"])

    for hwy_key in highway_keys:
        if hwy_key in caselli_db:
            db = caselli_db[hwy_key]
            # Match esatto
            if name in db:
                return db[name]
            # Match case-insensitive
            for db_name, coords in db.items():
                if db_name.lower() == name.lower():
                    return coords
            # Match parziale
            for db_name, coords in db.items():
                if name.lower() in db_name.lower() or db_name.lower() in name.lower():
                    return coords

    # Cerca in tutte le autostrade come fallback
    for hwy_key, db in caselli_db.items():
        for db_name, coords in db.items():
            if db_name.lower() == name.lower():
                return coords

    return None


def extract_page_date(resp, soup):
    """Estrae la data di aggiornamento di una pagina web."""
    # 1. Header Last-Modified
    last_mod = resp.headers.get("Last-Modified")
    if last_mod:
        try:
            from email.utils import parsedate_to_datetime
            dt = parsedate_to_datetime(last_mod)
            return dt.strftime("%Y-%m-%d")
        except Exception:
            pass

    # 2. Meta tag article:modified_time o dateModified
    for meta in soup.find_all("meta"):
        prop = meta.get("property", "") or meta.get("name", "")
        if prop in ("article:modified_time", "dateModified", "last-modified"):
            content = meta.get("content", "")
            if content:
                match = re.search(r'(\d{4}-\d{2}-\d{2})', content)
                if match:
                    return match.group(1)

    # 3. Schema.org dateModified in JSON-LD
    for script in soup.find_all("script", type="application/ld+json"):
        try:
            ld = json.loads(script.string)
            for key in ("dateModified", "datePublished"):
                if key in ld:
                    match = re.search(r'(\d{4}-\d{2}-\d{2})', str(ld[key]))
                    if match:
                        return match.group(1)
        except Exception:
            pass

    # 4. Cerca pattern data nel testo visibile (es. "aggiornato al 15 marzo 2026")
    text = soup.get_text()
    # Pattern italiano: "15 marzo 2026" o "marzo 2025"
    mesi = {"gennaio": "01", "febbraio": "02", "marzo": "03", "aprile": "04",
            "maggio": "05", "giugno": "06", "luglio": "07", "agosto": "08",
            "settembre": "09", "ottobre": "10", "novembre": "11", "dicembre": "12"}
    for pattern in [r'aggiornato?\s+(?:al?\s+)?(\d{1,2})\s+(\w+)\s+(\d{4})',
                    r'aggiornamento\s*:?\s*(\d{1,2})\s+(\w+)\s+(\d{4})']:
        m = re.search(pattern, text, re.IGNORECASE)
        if m:
            day, month_name, year = m.group(1), m.group(2).lower(), m.group(3)
            if month_name in mesi:
                return f"{year}-{mesi[month_name]}-{int(day):02d}"

    return None


def scrape_autostrade():
    """Scrape tabella km da Autostrade per l'Italia."""
    print("📡 Scaricamento dati da Autostrade per l'Italia...")
    try:
        resp = requests.get(AUTOSTRADE_URL, headers=HEADERS, timeout=30)
        resp.raise_for_status()
    except requests.RequestException as e:
        print(f"⚠️  Errore Autostrade.it: {e}")
        return [], None

    soup = BeautifulSoup(resp.text, "html.parser")
    segments = []

    # Cerca tutte le tabelle nella pagina
    tables = soup.find_all("table")
    print(f"   Trovate {len(tables)} tabelle")

    for table in tables:
        rows = table.find_all("tr")
        current_highway = ""

        for row in rows:
            cells = row.find_all(["td", "th"])
            if not cells:
                continue

            texts = [c.get_text(strip=True) for c in cells]

            # Identifica riga autostrada (intestazione) o riga dati
            if len(texts) >= 4:
                # Cerca il codice autostrada
                highway_match = re.search(r'(A\d+)', texts[0])
                if highway_match and not any(c.isdigit() and '.' in str(texts[1]) for c in str(texts[1])):
                    current_highway = texts[0]
                    continue

                # Prova a parsare come riga dati
                # Formato tipico: Autostrada | Inizio Tratta | km | Fine Tratta | km | Dir
                # oppure: Inizio | km | Fine | km | Dir
                segment = try_parse_row(texts, current_highway)
                if segment:
                    segments.append(segment)

    page_date = extract_page_date(resp, soup)
    print(f"   Estratti {len(segments)} tratti da Autostrade.it (data pagina: {page_date or '?'})")
    return segments, page_date


def try_parse_row(texts, current_highway):
    """Prova a parsare una riga della tabella Autostrade.it."""
    # Vari formati possibili
    segment = None

    if len(texts) >= 6:
        # Formato: Autostrada | Inizio | km | Fine | km | Direzione
        highway = texts[0] if re.search(r'A\d+', texts[0]) else current_highway
        km_start = parse_km(texts[2])
        km_end = parse_km(texts[4])
        if km_start is not None and km_end is not None:
            direction = "Nord"
            for t in texts:
                t_lower = t.lower()
                if "sud" in t_lower:
                    direction = "Sud"
                elif "est" in t_lower:
                    direction = "Est"
                elif "ovest" in t_lower:
                    direction = "Ovest"
                elif "nord" in t_lower:
                    direction = "Nord"
                elif "francia" in t_lower:
                    direction = "Francia"
                elif "italia" in t_lower:
                    direction = "Italia"

            segment = {
                "highway": extract_highway_code(highway),
                "highway_full": highway.strip(),
                "start_name": normalize_name(texts[1]),
                "start_km": km_start,
                "end_name": normalize_name(texts[3]),
                "end_km": km_end,
                "direction": direction,
            }

    elif len(texts) >= 4:
        # Formato ridotto
        km1 = parse_km(texts[1])
        km2 = parse_km(texts[3])
        if km1 is not None and km2 is not None:
            segment = {
                "highway": extract_highway_code(current_highway),
                "highway_full": current_highway.strip(),
                "start_name": normalize_name(texts[0]),
                "start_km": km1,
                "end_name": normalize_name(texts[2]),
                "end_km": km2,
                "direction": "Nord",
            }

    return segment


def scrape_sicurauto():
    """Scrape lista tratti da Sicurauto.it per Tutor 3.0 e tratti extra."""
    print("📡 Scaricamento dati da Sicurauto.it...")
    try:
        resp = requests.get(SICURAUTO_URL, headers=HEADERS, timeout=30)
        resp.raise_for_status()
    except requests.RequestException as e:
        print(f"⚠️  Errore Sicurauto.it: {e}")
        return [], set(), None

    soup = BeautifulSoup(resp.text, "html.parser")
    page_date = extract_page_date(resp, soup)
    segments = []
    tutor30_segments = set()

    # Cerca tabelle
    tables = soup.find_all("table")
    print(f"   Trovate {len(tables)} tabelle")

    for table in tables:
        rows = table.find_all("tr")
        current_highway = ""

        for row in rows:
            cells = row.find_all(["td", "th"])
            if not cells:
                continue

            texts = [c.get_text(strip=True) for c in cells]
            row_html = str(row)

            # Detecta se è un Tutor 3.0 (testo evidenziato o con menzione)
            is_tutor30 = "3.0" in row_html or "tutor 3" in row_html.lower()

            # Cerca codice autostrada
            for text in texts:
                highway_match = re.match(r'^(A\d+)\s', text)
                if highway_match:
                    current_highway = highway_match.group(1)
                    break

            if len(texts) >= 3:
                # Formato: Autostrada | Tratto (Inizio - Fine) | Direzione
                # oppure: Inizio | Fine | Direzione
                highway = current_highway
                for t in texts:
                    hm = re.match(r'^(A\d+)$', t.strip())
                    if hm:
                        highway = hm.group(1)

                if highway:
                    seg = {
                        "highway": highway,
                        "start_name": normalize_name(texts[0]) if len(texts) >= 3 else "",
                        "end_name": normalize_name(texts[1]) if len(texts) >= 3 else "",
                        "direction": texts[-1] if texts[-1] in ["Nord", "Sud", "Est", "Ovest"] else "Nord",
                    }
                    segments.append(seg)

                    if is_tutor30:
                        key = f"{highway}|{seg['start_name']}|{seg['end_name']}"
                        tutor30_segments.add(key)

    print(f"   Estratti {len(segments)} tratti da Sicurauto.it (data pagina: {page_date or '?'})")
    print(f"   Identificati {len(tutor30_segments)} tratti Tutor 3.0")
    return segments, tutor30_segments, page_date


def use_hardcoded_data():
    """Dati hardcoded dalla tabella Autostrade.it (estratti durante la ricerca)."""
    print("📋 Uso dati pre-estratti dalla tabella Autostrade per l'Italia...")
    segments = [
        # A1 Milano-Bologna DIR NORD
        {"highway": "A1", "start_name": "Lodi", "start_km": 24, "end_name": "Milano Sud", "end_km": 12.05, "direction": "Nord"},
        {"highway": "A1", "start_name": "Casale", "start_km": 38.9, "end_name": "Lodi", "end_km": 24, "direction": "Nord"},
        {"highway": "A1", "start_name": "Piacenza Nord", "start_km": 52.2, "end_name": "Casale", "end_km": 38.9, "direction": "Nord"},
        {"highway": "A1", "start_name": "Piacenza Sud", "start_km": 60.885, "end_name": "Piacenza Nord", "end_km": 52.2, "direction": "Nord"},
        {"highway": "A1", "start_name": "Fiorenzuola", "start_km": 76.58, "end_name": "Piacenza Sud", "end_km": 60.885, "direction": "Nord"},
        {"highway": "A1", "start_name": "Fidenza", "start_km": 92.55, "end_name": "Fiorenzuola", "end_km": 76.58, "direction": "Nord"},
        {"highway": "A1", "start_name": "All. A15 A1 N", "start_km": 99.1, "end_name": "Fidenza", "end_km": 92.55, "direction": "Nord"},
        {"highway": "A1", "start_name": "Campegine", "start_km": 125.83, "end_name": "Parma", "end_km": 113.28, "direction": "Nord"},
        {"highway": "A1", "start_name": "Reggio Emilia", "start_km": 139.2, "end_name": "Campegine", "end_km": 125.83, "direction": "Nord"},
        {"highway": "A1", "start_name": "All. A22 A1 N", "start_km": 153.4, "end_name": "Reggio Emilia", "end_km": 139.2, "direction": "Nord"},
        {"highway": "A1", "start_name": "Modena Sud", "start_km": 172.03, "end_name": "Modena Nord", "end_km": 158.53, "direction": "Nord"},
        # A1 Milano-Bologna DIR SUD
        {"highway": "A1", "start_name": "Milano Sud", "start_km": 12.3, "end_name": "Lodi", "end_km": 21.2, "direction": "Sud"},
        {"highway": "A1", "start_name": "Lodi", "start_km": 21.2, "end_name": "Casale", "end_km": 36.1, "direction": "Sud"},
        {"highway": "A1", "start_name": "Casale", "start_km": 36.1, "end_name": "Piacenza Nord", "end_km": 48.1, "direction": "Sud"},
        {"highway": "A1", "start_name": "Piacenza Nord", "start_km": 48.1, "end_name": "Piacenza Sud", "end_km": 54.5, "direction": "Sud"},
        {"highway": "A1", "start_name": "All. A21 A1 S", "start_km": 60.85, "end_name": "Fiorenzuola", "end_km": 71.35, "direction": "Sud"},
        {"highway": "A1", "start_name": "Fiorenzuola", "start_km": 71.35, "end_name": "Fidenza", "end_km": 89.15, "direction": "Sud"},
        {"highway": "A1", "start_name": "Fidenza", "start_km": 89.15, "end_name": "All A15 A1 N", "end_km": 99.45, "direction": "Sud"},
        {"highway": "A1", "start_name": "All A15 A1 S", "start_km": 103.4, "end_name": "Parma", "end_km": 109.2, "direction": "Sud"},
        {"highway": "A1", "start_name": "Parma", "start_km": 109.2, "end_name": "Campegine", "end_km": 122.35, "direction": "Sud"},
        {"highway": "A1", "start_name": "Campegine", "start_km": 122.35, "end_name": "Reggio Emilia", "end_km": 135.85, "direction": "Sud"},
        {"highway": "A1", "start_name": "Modena Nord", "start_km": 158.528, "end_name": "Modena Sud", "end_km": 169.05, "direction": "Sud"},
        {"highway": "A1", "start_name": "Modena Sud", "start_km": 169.05, "end_name": "All. A14 A1 N", "end_km": 186.83, "direction": "Sud"},
        # A1 Bologna-Firenze
        {"highway": "A1", "start_name": "Santa Lucia Nord", "start_km": 264.94, "end_name": "Santa Lucia Sud", "end_km": 272.698, "direction": "Sud"},
        # A1 Variante di Valico
        {"highway": "A1", "start_name": "Firenzuola", "start_km": 27.62, "end_name": "Badia", "end_km": 18.89, "direction": "Nord"},
        {"highway": "A1", "start_name": "Badia", "start_km": 19, "end_name": "Firenzuola", "end_km": 27.65, "direction": "Sud"},
        # A1 Firenze-Roma DIR NORD
        {"highway": "A1", "start_name": "Valdichiana", "start_km": 386.45, "end_name": "Monte San Savino", "end_km": 373.65, "direction": "Nord"},
        {"highway": "A1", "start_name": "Chiusi", "start_km": 411.6, "end_name": "Valdichiana", "end_km": 386.45, "direction": "Nord"},
        {"highway": "A1", "start_name": "Orvieto", "start_km": 467, "end_name": "Fabro", "end_km": 429.1, "direction": "Nord"},
        {"highway": "A1", "start_name": "Magliano", "start_km": 503.11, "end_name": "Orte", "end_km": 493.9, "direction": "Nord"},
        {"highway": "A1", "start_name": "Ponzano Romano", "start_km": 517.55, "end_name": "Magliano", "end_km": 503.11, "direction": "Nord"},
        {"highway": "A1", "start_name": "All. Rac Rm-N A1 N", "start_km": 529.14, "end_name": "Ponzano Romano", "end_km": 517.55, "direction": "Nord"},
        # A1 Firenze-Roma DIR SUD
        {"highway": "A1", "start_name": "Monte San Savino", "start_km": 369.45, "end_name": "Valdichiana", "end_km": 383.95, "direction": "Sud"},
        {"highway": "A1", "start_name": "Valdichiana", "start_km": 383.95, "end_name": "Chiusi", "end_km": 408.95, "direction": "Sud"},
        {"highway": "A1", "start_name": "Orte", "start_km": 489.9, "end_name": "Ponzano Romano", "end_km": 512.9, "direction": "Sud"},
        {"highway": "A1", "start_name": "Ponzano Romano", "start_km": 512.9, "end_name": "All. Rac Rm-N A1 N", "end_km": 527.6, "direction": "Sud"},
        # A1 Roma-Napoli DIR NORD
        {"highway": "A1", "start_name": "All Rac RM-S A1 N", "start_km": 574.6, "end_name": "All. A24 A1 S", "end_km": 564.05, "direction": "Nord"},
        {"highway": "A1", "start_name": "Valmontone", "start_km": 589.2, "end_name": "All Rac RM-S A1s", "end_km": 579.85, "direction": "Nord"},
        {"highway": "A1", "start_name": "Colleferro", "start_km": 596, "end_name": "Valmontone", "end_km": 589.2, "direction": "Nord"},
        {"highway": "A1", "start_name": "Anagni", "start_km": 605.8, "end_name": "Colleferro", "end_km": 596, "direction": "Nord"},
        {"highway": "A1", "start_name": "Pontecorvo", "start_km": 661.75, "end_name": "Ceprano", "end_km": 644.85, "direction": "Nord"},
        {"highway": "A1", "start_name": "Cassino", "start_km": 671.2, "end_name": "Pontecorvo", "end_km": 661.75, "direction": "Nord"},
        {"highway": "A1", "start_name": "San Vittore", "start_km": 680.5, "end_name": "Cassino", "end_km": 671.2, "direction": "Nord"},
        {"highway": "A1", "start_name": "Caianello", "start_km": 702.6, "end_name": "San Vittore", "end_km": 680.5, "direction": "Nord"},
        {"highway": "A1", "start_name": "Sm Capuavetere", "start_km": 731.4, "end_name": "Capua", "end_km": 721.5, "direction": "Nord"},
        {"highway": "A1", "start_name": "Caserta Nord", "start_km": 736.68, "end_name": "Sm Capuavetere", "end_km": 731.4, "direction": "Nord"},
        # A1 Roma-Napoli DIR SUD
        {"highway": "A1", "start_name": "All. A24 A1 S", "start_km": 564, "end_name": "All Rac RM-S A1 N", "end_km": 574, "direction": "Sud"},
        {"highway": "A1", "start_name": "All Rac RM-S A1 N", "start_km": 579.75, "end_name": "Colleferro", "end_km": 589.17, "direction": "Sud"},
        {"highway": "A1", "start_name": "Colleferro", "start_km": 589.17, "end_name": "Anagni", "end_km": 602.9, "direction": "Sud"},
        {"highway": "A1", "start_name": "Anagni", "start_km": 602.9, "end_name": "Ferentino", "end_km": 616.5, "direction": "Sud"},
        {"highway": "A1", "start_name": "Frosinone", "start_km": 622.5, "end_name": "Ceprano", "end_km": 640.8, "direction": "Sud"},
        {"highway": "A1", "start_name": "Pontecorvo", "start_km": 656.7, "end_name": "Cassino", "end_km": 668.57, "direction": "Sud"},
        {"highway": "A1", "start_name": "Cassino", "start_km": 668.57, "end_name": "San Vittore", "end_km": 675.9, "direction": "Sud"},
        {"highway": "A1", "start_name": "San Vittore", "start_km": 675.9, "end_name": "Caianello", "end_km": 696.7, "direction": "Sud"},
        {"highway": "A1", "start_name": "Capua", "start_km": 717.45, "end_name": "Sm Capuavetere", "end_km": 727.95, "direction": "Sud"},
        {"highway": "A1", "start_name": "Sm Capuavetere", "start_km": 727.95, "end_name": "Caserta Nord", "end_km": 732.89, "direction": "Sud"},
        # A1 Diramazioni
        {"highway": "A1", "start_name": "Castelnuovo Di Porto", "start_km": 7.8, "end_name": "Settebagni", "end_km": 17.9, "direction": "Sud"},
        {"highway": "A1", "start_name": "San Cesareo", "start_km": 3.7, "end_name": "Monteporzio", "end_km": 9.2, "direction": "Nord"},
        {"highway": "A1", "start_name": "Monteporzio", "start_km": 11.88, "end_name": "San Cesareo", "end_km": 3.72, "direction": "Sud"},
        # A4 DIR EST
        {"highway": "A4", "start_name": "Trezzo", "start_km": 154.85, "end_name": "Dalmine", "end_km": 166.633, "direction": "Est"},
        {"highway": "A4", "start_name": "Dalmine", "start_km": 166.633, "end_name": "Bergamo", "end_km": 171.3, "direction": "Est"},
        {"highway": "A4", "start_name": "Bergamo", "start_km": 171.3, "end_name": "Seriate", "end_km": 178.45, "direction": "Est"},
        {"highway": "A4", "start_name": "Seriate", "start_km": 178.45, "end_name": "Grumello", "end_km": 187.01, "direction": "Est"},
        {"highway": "A4", "start_name": "Grumello", "start_km": 187.01, "end_name": "Ponte Oglio", "end_km": 190.45, "direction": "Est"},
        {"highway": "A4", "start_name": "Ponte Oglio", "start_km": 190.45, "end_name": "Palazzolo", "end_km": 192.4, "direction": "Est"},
        {"highway": "A4", "start_name": "Palazzolo", "start_km": 192.4, "end_name": "Rovato", "end_km": 200.69, "direction": "Est"},
        {"highway": "A4", "start_name": "Rovato", "start_km": 200.69, "end_name": "Ospitaletto", "end_km": 205.55, "direction": "Est"},
        {"highway": "A4", "start_name": "Cessalto", "start_km": 378, "end_name": "San Stino", "end_km": 388, "direction": "Est"},
        {"highway": "A4", "start_name": "San Stino", "start_km": 388, "end_name": "Portogruaro", "end_km": 402, "direction": "Est"},
        {"highway": "A4", "start_name": "Latisana", "start_km": 430, "end_name": "San Giorgio di Nogaro", "end_km": 445, "direction": "Est"},
        {"highway": "A4", "start_name": "San Giorgio di Nogaro", "start_km": 445, "end_name": "Nodo A4/A23", "end_km": 460, "direction": "Est"},
        {"highway": "A4", "start_name": "Villesse", "start_km": 470, "end_name": "Redipuglia", "end_km": 478, "direction": "Est"},
        # A4 DIR OVEST
        {"highway": "A4", "start_name": "Capriate", "start_km": 161.49, "end_name": "Cavenago", "end_km": 151.5, "direction": "Ovest"},
        {"highway": "A4", "start_name": "Dalmine", "start_km": 168.9, "end_name": "Capriate", "end_km": 161.49, "direction": "Ovest"},
        {"highway": "A4", "start_name": "Seriate", "start_km": 180, "end_name": "Bergamo", "end_km": 174.3, "direction": "Ovest"},
        {"highway": "A4", "start_name": "Grumello", "start_km": 189, "end_name": "Seriate", "end_km": 180, "direction": "Ovest"},
        {"highway": "A4", "start_name": "Ponte Oglio", "start_km": 192.4, "end_name": "Grumello", "end_km": 189, "direction": "Ovest"},
        {"highway": "A4", "start_name": "Palazzolo", "start_km": 193.9, "end_name": "Ponte Oglio", "end_km": 192.4, "direction": "Ovest"},
        {"highway": "A4", "start_name": "Rovato", "start_km": 202.3, "end_name": "Palazzolo", "end_km": 193.9, "direction": "Ovest"},
        {"highway": "A4", "start_name": "Ospitaletto", "start_km": 206.97, "end_name": "Rovato", "end_km": 202.3, "direction": "Ovest"},
        {"highway": "A4", "start_name": "Villesse", "start_km": 470, "end_name": "Palmanova", "end_km": 462, "direction": "Ovest"},
        {"highway": "A4", "start_name": "San Giorgio di Nogaro", "start_km": 445, "end_name": "Latisana", "end_km": 430, "direction": "Ovest"},
        {"highway": "A4", "start_name": "San Stino", "start_km": 388, "end_name": "San Donà", "end_km": 375, "direction": "Ovest"},
        # A5 Traforo Monte Bianco
        {"highway": "A5", "start_name": "Traforo Montebianco Sud", "start_km": 0, "end_name": "Traforo Montebianco Nord", "end_km": 11.6, "direction": "Francia"},
        {"highway": "A5", "start_name": "Traforo Montebianco Nord", "start_km": 11.6, "end_name": "Traforo Montebianco Sud", "end_km": 0, "direction": "Italia"},
        # A6
        {"highway": "A6", "start_name": "Pione", "start_km": 0, "end_name": "Ceva Nord", "end_km": 15, "direction": "Nord"},
        {"highway": "A6", "start_name": "Altare", "start_km": 0, "end_name": "Zinola Sud", "end_km": 10, "direction": "Sud"},
        # A7
        {"highway": "A7", "start_name": "Busalla", "start_km": 114.14, "end_name": "Ronco Scrivia", "end_km": 109.33, "direction": "Nord"},
        {"highway": "A7", "start_name": "Ronco Scrivia", "start_km": 109.33, "end_name": "Isola del Cantone", "end_km": 104.8, "direction": "Nord"},
        {"highway": "A7", "start_name": "Busalla", "start_km": 112.78, "end_name": "Bolzaneto", "end_km": 125.07, "direction": "Sud"},
        # A8
        {"highway": "A8", "start_name": "Castellanza", "start_km": 17, "end_name": "Busto Arsizio", "end_km": 20.8, "direction": "Nord"},
        {"highway": "A8", "start_name": "Busto Arsizio", "start_km": 25.6, "end_name": "Castellanza", "end_km": 20.4, "direction": "Sud"},
        {"highway": "A8", "start_name": "Castellanza", "start_km": 20.4, "end_name": "Origgio Ovest", "end_km": 12.15, "direction": "Sud"},
        # A9
        {"highway": "A9", "start_name": "Turate", "start_km": 18.1, "end_name": "All. A9 A36", "end_km": 20.15, "direction": "Nord"},
        {"highway": "A9", "start_name": "Lomazzo Sud", "start_km": 22.95, "end_name": "Lomazzo Nord", "end_km": 24.85, "direction": "Nord"},
        {"highway": "A9", "start_name": "Lomazzo Nord", "start_km": 24.85, "end_name": "Fino Mornasco", "end_km": 28.6, "direction": "Nord"},
        {"highway": "A9", "start_name": "Turate", "start_km": 20.45, "end_name": "Saronno", "end_km": 17.65, "direction": "Sud"},
        {"highway": "A9", "start_name": "Lomazzo Nord", "start_km": 26.7, "end_name": "Lomazzo Sud", "end_km": 23.95, "direction": "Sud"},
        # A10
        {"highway": "A10", "start_name": "Celle Ligure", "start_km": 31.59, "end_name": "Albisola", "end_km": 36.05, "direction": "Ovest"},
        {"highway": "A10", "start_name": "Albisola", "start_km": 38.7, "end_name": "Celle Ligure", "end_km": 33.89, "direction": "Est"},
        # A11
        {"highway": "A11", "start_name": "Prato Est", "start_km": 7.25, "end_name": "Prato Ovest", "end_km": 15.9, "direction": "Ovest"},
        {"highway": "A11", "start_name": "Prato Ovest", "start_km": 15.9, "end_name": "Pistoia", "end_km": 26.1, "direction": "Ovest"},
        {"highway": "A11", "start_name": "Prato Ovest", "start_km": 18.3, "end_name": "Prato Est", "end_km": 9.55, "direction": "Est"},
        {"highway": "A11", "start_name": "Pistoia", "start_km": 28.45, "end_name": "Prato Ovest", "end_km": 18.3, "direction": "Est"},
        {"highway": "A11", "start_name": "Montecatini", "start_km": 40.5, "end_name": "Pistoia", "end_km": 28.45, "direction": "Est"},
        # A13 DIR NORD
        {"highway": "A13", "start_name": "Arcoveggio", "start_km": 1.47, "end_name": "Bologna Interporto", "end_km": 6.5, "direction": "Nord"},
        {"highway": "A13", "start_name": "Bologna Interporto", "start_km": 6.5, "end_name": "Altedo", "end_km": 19.8, "direction": "Nord"},
        {"highway": "A13", "start_name": "Altedo", "start_km": 19.8, "end_name": "Ferrara Sud", "end_km": 32.3, "direction": "Nord"},
        {"highway": "A13", "start_name": "Ferrara Sud", "start_km": 32.3, "end_name": "Ferrara Nord", "end_km": 40.3, "direction": "Nord"},
        {"highway": "A13", "start_name": "Ferrara Nord", "start_km": 40.3, "end_name": "Occhiobello", "end_km": 45.6, "direction": "Nord"},
        {"highway": "A13", "start_name": "Occhiobello", "start_km": 45.6, "end_name": "Rovigo Sud", "end_km": 61.7, "direction": "Nord"},
        {"highway": "A13", "start_name": "Rovigo Sud", "start_km": 61.7, "end_name": "Rovigo", "end_km": 68.9, "direction": "Nord"},
        {"highway": "A13", "start_name": "Rovigo", "start_km": 68.9, "end_name": "Boara", "end_km": 74.1, "direction": "Nord"},
        {"highway": "A13", "start_name": "Boara", "start_km": 74.1, "end_name": "Monselice", "end_km": 86.4, "direction": "Nord"},
        {"highway": "A13", "start_name": "Monselice", "start_km": 86.4, "end_name": "Terme Euganee", "end_km": 92.8, "direction": "Nord"},
        {"highway": "A13", "start_name": "Terme Euganee", "start_km": 92.8, "end_name": "Padova Sud", "end_km": 99.4, "direction": "Nord"},
        # A13 DIR SUD
        {"highway": "A13", "start_name": "Bologna Interporto", "start_km": 9.4, "end_name": "Arcoveggio", "end_km": 1.4, "direction": "Sud"},
        {"highway": "A13", "start_name": "Altedo", "start_km": 21.1, "end_name": "Bologna Interporto", "end_km": 9.4, "direction": "Sud"},
        {"highway": "A13", "start_name": "Ferrara Sud", "start_km": 34.6, "end_name": "Altedo", "end_km": 21.1, "direction": "Sud"},
        {"highway": "A13", "start_name": "Ferrara Nord", "start_km": 42.4, "end_name": "Ferrara Sud", "end_km": 34.6, "direction": "Sud"},
        {"highway": "A13", "start_name": "Rovigo Sud", "start_km": 64.38, "end_name": "Occhiobello", "end_km": 50.3, "direction": "Sud"},
        {"highway": "A13", "start_name": "Rovigo", "start_km": 71, "end_name": "Rovigo Sud", "end_km": 64.38, "direction": "Sud"},
        {"highway": "A13", "start_name": "Terme Euganee", "start_km": 96.2, "end_name": "Monselice", "end_km": 89.1, "direction": "Sud"},
        {"highway": "A13", "start_name": "Padova Zona Ind", "start_km": 114.2, "end_name": "Padova Sud", "end_km": 101.9, "direction": "Sud"},
        # A14 DIR NORD (Bologna-Ancona)
        {"highway": "A14", "start_name": "All. Ramo Casalecchio", "start_km": 8.4, "end_name": "Borgo Panigale", "end_km": 5.5, "direction": "Nord"},
        {"highway": "A14", "start_name": "Castel San Pietro", "start_km": 39.4, "end_name": "San Lazzaro", "end_km": 23, "direction": "Nord"},
        {"highway": "A14", "start_name": "Imola", "start_km": 51.3, "end_name": "Castel San Pietro", "end_km": 39.4, "direction": "Nord"},
        {"highway": "A14", "start_name": "All. Ravenna Nord", "start_km": 55.3, "end_name": "Imola", "end_km": 51.3, "direction": "Nord"},
        {"highway": "A14", "start_name": "Faenza", "start_km": 65.4, "end_name": "All. Ravenna Sud", "end_km": 58.9, "direction": "Nord"},
        {"highway": "A14", "start_name": "Forli", "start_km": 82.6, "end_name": "Faenza", "end_km": 65.4, "direction": "Nord"},
        {"highway": "A14", "start_name": "Cesena Nord", "start_km": 94.6, "end_name": "Forli", "end_km": 82.6, "direction": "Nord"},
        {"highway": "A14", "start_name": "Valle del Rubicone", "start_km": 112.25, "end_name": "Cesena", "end_km": 101, "direction": "Nord"},
        # A14 DIR SUD (Bologna-Ancona)
        {"highway": "A14", "start_name": "Bologna Fiera", "start_km": 16.4, "end_name": "San Lazzaro", "end_km": 21.8, "direction": "Sud"},
        {"highway": "A14", "start_name": "Castel San Pietro", "start_km": 35.9, "end_name": "Imola", "end_km": 49, "direction": "Sud"},
        {"highway": "A14", "start_name": "Faenza", "start_km": 63, "end_name": "Forli", "end_km": 80.3, "direction": "Sud"},
        {"highway": "A14", "start_name": "Cesena", "start_km": 98.2, "end_name": "Valle del Rubicone", "end_km": 109.12, "direction": "Sud"},
        {"highway": "A14", "start_name": "Valle del Rubicone", "start_km": 109.12, "end_name": "Rimini Nord", "end_km": 115.8, "direction": "Sud"},
        {"highway": "A14", "start_name": "Rimini Sud", "start_km": 126.15, "end_name": "Riccione", "end_km": 134.5, "direction": "Sud"},
        {"highway": "A14", "start_name": "Riccione", "start_km": 134.5, "end_name": "Cattolica", "end_km": 142.7, "direction": "Sud"},
        # A14 DIR NORD (Ancona-Pescara - Tutor 3.0)
        {"highway": "A14", "start_name": "Riccione", "start_km": 137.55, "end_name": "Rimini Sud", "end_km": 128.5, "direction": "Nord"},
        {"highway": "A14", "start_name": "Cattolica", "start_km": 145.95, "end_name": "Riccione", "end_km": 137.55, "direction": "Nord"},
        {"highway": "A14", "start_name": "Pesaro", "start_km": 157.7, "end_name": "Cattolica", "end_km": 145.95, "direction": "Nord"},
        {"highway": "A14", "start_name": "Giulianova", "start_km": 336, "end_name": "Val Vibrata", "end_km": 329.5, "direction": "Nord"},
        # A14 Pescara-Canosa DIR NORD
        {"highway": "A14", "start_name": "Pescara Ovest", "start_km": 383.7, "end_name": "All A14 A25 S", "end_km": 378.3, "direction": "Nord"},
        {"highway": "A14", "start_name": "Ortona", "start_km": 405.45, "end_name": "Pescara Sud", "end_km": 394.55, "direction": "Nord"},
        {"highway": "A14", "start_name": "San Severo", "start_km": 529.55, "end_name": "Poggio Imperiale", "end_km": 508.03, "direction": "Nord"},
        {"highway": "A14", "start_name": "Foggia", "start_km": 555.7, "end_name": "San Severo", "end_km": 529.55, "direction": "Nord"},
        {"highway": "A14", "start_name": "Foggia Zona Industriale", "start_km": 566.978, "end_name": "Foggia", "end_km": 555.7, "direction": "Nord"},
        {"highway": "A14", "start_name": "Cerignola Est", "start_km": 590.8, "end_name": "Foggia Zona Industriale", "end_km": 566.978, "direction": "Nord"},
        {"highway": "A14", "start_name": "All. A16 A14 N", "start_km": 599.51, "end_name": "Cerignola Est", "end_km": 590.8, "direction": "Nord"},
        # A14 Pescara-Canosa DIR SUD
        {"highway": "A14", "start_name": "Pescara Sud", "start_km": 390.07, "end_name": "Ortona", "end_km": 402.3, "direction": "Sud"},
        {"highway": "A14", "start_name": "Valdisangro", "start_km": 417.15, "end_name": "Vasto Nord", "end_km": 434.43, "direction": "Sud"},
        {"highway": "A14", "start_name": "Poggio Imperiale", "start_km": 502.78, "end_name": "San Severo", "end_km": 525.785, "direction": "Sud"},
        {"highway": "A14", "start_name": "San Severo", "start_km": 525.785, "end_name": "Foggia", "end_km": 551.69, "direction": "Sud"},
        {"highway": "A14", "start_name": "Foggia", "start_km": 551.69, "end_name": "Foggia Zona Industriale", "end_km": 562.65, "direction": "Sud"},
        {"highway": "A14", "start_name": "Foggia Zona Industriale", "start_km": 562.65, "end_name": "Cerignola Est", "end_km": 580.225, "direction": "Sud"},
        {"highway": "A14", "start_name": "Cerignola Est", "start_km": 580.225, "end_name": "All. A16 A14 N", "end_km": 599.5, "direction": "Sud"},
        {"highway": "A14", "start_name": "All. A16 A14 S", "start_km": 603.79, "end_name": "Canosa", "end_km": 608.79, "direction": "Sud"},
        {"highway": "A14", "start_name": "Canosa", "start_km": 608.79, "end_name": "Andria Barletta", "end_km": 619.4, "direction": "Sud"},
        # A14 Canosa-Bari DIR NORD
        {"highway": "A14", "start_name": "Canosa", "start_km": 611.1, "end_name": "All. A16 A14 S", "end_km": 605.05, "direction": "Nord"},
        {"highway": "A14", "start_name": "Andria Barletta", "start_km": 628.8, "end_name": "Canosa", "end_km": 611.1, "direction": "Nord"},
        {"highway": "A14", "start_name": "Bitonto", "start_km": 663.97, "end_name": "Molfetta", "end_km": 654, "direction": "Nord"},
        {"highway": "A14", "start_name": "Bari Nord", "start_km": 671, "end_name": "Bitonto", "end_km": 663.97, "direction": "Nord"},
        {"highway": "A14", "start_name": "Bari Sud", "start_km": 681.135, "end_name": "Bari Nord", "end_km": 671, "direction": "Nord"},
        # A14 Canosa-Bari DIR SUD
        {"highway": "A14", "start_name": "Andria Barletta", "start_km": 619.4, "end_name": "Trani", "end_km": 637.05, "direction": "Sud"},
        {"highway": "A14", "start_name": "Trani", "start_km": 637.05, "end_name": "Molfetta", "end_km": 642.5, "direction": "Sud"},
        {"highway": "A14", "start_name": "Molfetta", "start_km": 642.5, "end_name": "Bitonto", "end_km": 663.965, "direction": "Sud"},
        {"highway": "A14", "start_name": "Bitonto", "start_km": 663.965, "end_name": "Bari Nord", "end_km": 668.2, "direction": "Sud"},
        # A16
        {"highway": "A16", "start_name": "Monteforte", "start_km": 36.05, "end_name": "Avellino Ovest", "end_km": 40, "direction": "Est"},
        {"highway": "A16", "start_name": "Monteforte", "start_km": 36.05, "end_name": "Baiano", "end_km": 27.65, "direction": "Ovest"},
        # A23
        {"highway": "A23", "start_name": "Udine Nord", "start_km": 25.2, "end_name": "Gemona", "end_km": 43, "direction": "Nord"},
        {"highway": "A23", "start_name": "Gemona", "start_km": 43, "end_name": "Carnia", "end_km": 54.37, "direction": "Nord"},
        {"highway": "A23", "start_name": "Gemona", "start_km": 47.2, "end_name": "Udine Nord", "end_km": 30.65, "direction": "Sud"},
        {"highway": "A23", "start_name": "Udine Sud", "start_km": 15, "end_name": "Nodo A23-A4", "end_km": 0, "direction": "Sud"},
        # A26
        {"highway": "A26", "start_name": "Ovada", "start_km": 28.95, "end_name": "Predosa", "end_km": 44.49, "direction": "Nord"},
        {"highway": "A26", "start_name": "Predosa", "start_km": 43, "end_name": "Ovada", "end_km": 31.75, "direction": "Sud"},
        {"highway": "A26", "start_name": "Masone", "start_km": 10, "end_name": "Broglio", "end_km": 15, "direction": "Nord"},
        {"highway": "A26", "start_name": "Masone", "start_km": 12, "end_name": "Massimorisso", "end_km": 5, "direction": "Sud"},
        # A27
        {"highway": "A27", "start_name": "Treviso Sud", "start_km": 10.1, "end_name": "Treviso Nord", "end_km": 21.5, "direction": "Nord"},
        {"highway": "A27", "start_name": "Treviso Nord", "start_km": 21.5, "end_name": "All. SPV A27", "end_km": 24, "direction": "Nord"},
        {"highway": "A27", "start_name": "Vittorio Veneto Nord", "start_km": 58.2, "end_name": "Fadalto", "end_km": 62.3, "direction": "Nord"},
        {"highway": "A27", "start_name": "Treviso Sud", "start_km": 16.025, "end_name": "All. A4 A27", "end_km": 10.2, "direction": "Sud"},
        {"highway": "A27", "start_name": "Treviso Nord", "start_km": 24.45, "end_name": "Treviso Sud", "end_km": 16.025, "direction": "Sud"},
        # A28
        {"highway": "A28", "start_name": "Azzano Decimo", "start_km": 0, "end_name": "Villotta", "end_km": 10, "direction": "Sud"},
        # A30
        {"highway": "A30", "start_name": "Nola", "start_km": 19.28, "end_name": "All A30 A1", "end_km": 1.3, "direction": "Nord"},
        {"highway": "A30", "start_name": "Palma Campania", "start_km": 32.14, "end_name": "All A30 A16", "end_km": 22.1, "direction": "Nord"},
        {"highway": "A30", "start_name": "Sarno", "start_km": 38.33, "end_name": "Palma Campania", "end_km": 32.14, "direction": "Nord"},
        {"highway": "A30", "start_name": "Nocera Pagani", "start_km": 40.94, "end_name": "Sarno", "end_km": 38.33, "direction": "Nord"},
        {"highway": "A30", "start_name": "Salerno San Severino", "start_km": 49.47, "end_name": "Nocera Pagani", "end_km": 40.94, "direction": "Nord"},
        {"highway": "A30", "start_name": "All A30 A1", "start_km": 1.85, "end_name": "Nola", "end_km": 17.2, "direction": "Sud"},
        {"highway": "A30", "start_name": "All A30 A16", "start_km": 22.16, "end_name": "Palma Campania", "end_km": 29, "direction": "Sud"},
        {"highway": "A30", "start_name": "Palma Campania", "start_km": 29, "end_name": "Sarno", "end_km": 34.365, "direction": "Sud"},
        {"highway": "A30", "start_name": "Sarno", "start_km": 34.365, "end_name": "Nocera Pagani", "end_km": 38.4, "direction": "Sud"},
        {"highway": "A30", "start_name": "Nocera Pagani", "start_km": 38.4, "end_name": "Castel San Giorgio", "end_km": 42.765, "direction": "Sud"},
        # A56 Tangenziale Napoli (km approssimati)
        {"highway": "A56", "start_name": "Viadotto Corso Malta", "start_km": 0, "end_name": "Capodimonte", "end_km": 2, "direction": "Ovest"},
        {"highway": "A56", "start_name": "Camaldoli", "start_km": 5, "end_name": "Vomero", "end_km": 7, "direction": "Ovest"},
        {"highway": "A56", "start_name": "Fuorigrotta", "start_km": 9, "end_name": "Agnano", "end_km": 11, "direction": "Ovest"},
        {"highway": "A56", "start_name": "Solfatare", "start_km": 13, "end_name": "Arco Felice", "end_km": 15, "direction": "Ovest"},
        {"highway": "A56", "start_name": "Arco Felice", "start_km": 15, "end_name": "Astroni", "end_km": 13, "direction": "Est"},
        {"highway": "A56", "start_name": "Solfatare", "start_km": 13, "end_name": "Agnano", "end_km": 11, "direction": "Est"},
        {"highway": "A56", "start_name": "Agnano", "start_km": 11, "end_name": "Fuorigrotta", "end_km": 9, "direction": "Est"},
        {"highway": "A56", "start_name": "Arenella", "start_km": 3, "end_name": "Capodimonte", "end_km": 2, "direction": "Est"},
    ]

    # Segna i Tutor 3.0
    tutor30_names = {
        ("A1", "Chiusi", "Valdichiana"), ("A1", "Valdichiana", "Monte San Savino"),
        ("A1", "Valdichiana", "Chiusi"), ("A1", "Monte San Savino", "Valdichiana"),
        ("A1", "Orvieto", "Fabro"), ("A1", "Area Tevere", "Fabro"),
        ("A1", "Castelnuovo Di Porto", "Settebagni"),
        ("A9", "Turate", "All. A9 A36"), ("A9", "Lomazzo Sud", "Lomazzo Nord"),
        ("A9", "Lomazzo Nord", "Fino Mornasco"), ("A9", "Lomazzo Nord", "Lomazzo Sud"),
        ("A9", "Turate", "Saronno"),
        ("A11", "Prato Est", "Prato Ovest"), ("A11", "Prato Ovest", "Pistoia"),
        ("A11", "Montecatini", "Pistoia"), ("A11", "Pistoia", "Prato Ovest"),
        ("A11", "Prato Ovest", "Prato Est"),
        ("A14", "Pesaro", "Cattolica"), ("A14", "Cattolica", "Riccione"),
        ("A14", "Riccione", "Rimini Sud"), ("A14", "Rimini Sud", "Riccione"),
        ("A14", "Riccione", "Cattolica"),
        ("A27", "Treviso Sud", "Treviso Nord"), ("A27", "Treviso Nord", "All. SPV A27"),
        ("A27", "Vittorio Veneto Nord", "Fadalto"), ("A27", "Treviso Nord", "Treviso Sud"),
        ("A27", "Treviso Sud", "All. A4 A27"),
    }

    for seg in segments:
        key = (seg["highway"], seg["start_name"], seg["end_name"])
        seg["type"] = "tutor_3.0" if key in tutor30_names else "standard"

    # Limiti di velocità per autostrada (default 130, eccezioni note)
    speed_limit_overrides = {
        "A56": 80,   # Tangenziale di Napoli
        "A30": 100,  # Caserta-Salerno (tratti urbani)
    }
    for seg in segments:
        seg["speed_limit"] = speed_limit_overrides.get(seg["highway"], 130)

    return segments


def add_coordinates(segments, caselli_db):
    """Aggiunge le coordinate GPS ai segmenti."""
    missing = set()
    for seg in segments:
        start_coords = find_coords(caselli_db, seg["highway"], seg["start_name"])
        end_coords = find_coords(caselli_db, seg["highway"], seg["end_name"])

        if start_coords:
            seg["start_coords"] = start_coords
        else:
            missing.add(f"{seg['highway']}:{seg['start_name']}")
            seg["start_coords"] = None

        if end_coords:
            seg["end_coords"] = end_coords
        else:
            missing.add(f"{seg['highway']}:{seg['end_name']}")
            seg["end_coords"] = None

    if missing:
        print(f"⚠️  Caselli senza coordinate ({len(missing)}):")
        for m in sorted(missing):
            print(f"   - {m}")

    return segments


def fetch_route_geometry(start_coords, end_coords):
    """Usa OSRM per ottenere il tracciato reale della strada tra due punti."""
    # OSRM usa lon,lat (invertito rispetto a lat,lon)
    url = (
        f"https://router.project-osrm.org/route/v1/driving/"
        f"{start_coords[1]},{start_coords[0]};{end_coords[1]},{end_coords[0]}"
        f"?overview=full&geometries=geojson"
    )
    try:
        resp = requests.get(url, timeout=10)
        resp.raise_for_status()
        data = resp.json()
        if data.get("code") == "Ok" and data.get("routes"):
            # Restituisce lista di [lat, lng] dal GeoJSON [lng, lat]
            coords = data["routes"][0]["geometry"]["coordinates"]
            return [[c[1], c[0]] for c in coords]
    except Exception:
        pass
    return None


def add_route_geometries(segments):
    """Aggiunge il tracciato reale (polyline) a ogni segmento via OSRM."""
    import time

    total = len(segments)
    routed = 0
    failed = 0

    print(f"\n🗺️  Recupero tracciati stradali reali da OSRM ({total} tratti)...")

    for i, seg in enumerate(segments):
        if not seg.get("start_coords") or not seg.get("end_coords"):
            continue

        route = fetch_route_geometry(seg["start_coords"], seg["end_coords"])
        if route and len(route) > 2:
            seg["route_coords"] = route
            routed += 1
        else:
            # Fallback: linea retta
            seg["route_coords"] = [seg["start_coords"], seg["end_coords"]]
            failed += 1

        # Progresso ogni 20 tratti
        if (i + 1) % 20 == 0:
            print(f"   {i + 1}/{total} completati...")

        # Rate limiting: OSRM pubblico richiede max 1 req/sec
        time.sleep(1.1)

    print(f"   Tracciati reali: {routed}, fallback linea retta: {failed}")
    return segments


ROUTE_CACHE_FILE = BASE_DIR / "route_cache.json"


def load_route_cache():
    if ROUTE_CACHE_FILE.exists():
        with open(ROUTE_CACHE_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    return {}


def save_route_cache(cache):
    with open(ROUTE_CACHE_FILE, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False)


def add_route_geometries_cached(segments):
    """Versione con cache: non ri-scarica tracciati già ottenuti."""
    import time

    cache = load_route_cache()
    total = len(segments)
    routed = 0
    cached = 0
    failed = 0
    new_requests = 0

    print(f"\n🗺️  Recupero tracciati stradali reali ({total} tratti, {len(cache)} in cache)...")

    for i, seg in enumerate(segments):
        if not seg.get("start_coords") or not seg.get("end_coords"):
            continue

        # Chiave cache basata su coordinate
        cache_key = f"{seg['start_coords'][0]:.4f},{seg['start_coords'][1]:.4f}-{seg['end_coords'][0]:.4f},{seg['end_coords'][1]:.4f}"

        if cache_key in cache:
            seg["route_coords"] = cache[cache_key]
            cached += 1
            continue

        route = fetch_route_geometry(seg["start_coords"], seg["end_coords"])
        if route and len(route) > 2:
            seg["route_coords"] = route
            cache[cache_key] = route
            routed += 1
        else:
            seg["route_coords"] = [seg["start_coords"], seg["end_coords"]]
            failed += 1

        new_requests += 1
        if (new_requests) % 20 == 0:
            print(f"   {i + 1}/{total} completati ({new_requests} nuove richieste)...")
            save_route_cache(cache)  # Salva cache periodicamente

        # Rate limiting OSRM pubblico
        time.sleep(1.1)

    save_route_cache(cache)
    print(f"   Da cache: {cached}, nuovi tracciati: {routed}, fallback: {failed}")
    return segments


def find_polizia_pdf_url():
    """Cerca il link al PDF aggiornato sulla pagina della Polizia di Stato."""
    try:
        resp = requests.get(POLIZIA_PAGE_URL, headers=HEADERS, timeout=30)
        resp.raise_for_status()
        soup = BeautifulSoup(resp.text, "html.parser")
        for link in soup.find_all("a", href=True):
            href = link["href"]
            if "tutor" in href.lower() and href.endswith(".pdf"):
                if not href.startswith("http"):
                    href = "https://www.poliziadistato.it" + href
                return href
    except Exception as e:
        print(f"   Errore ricerca PDF: {e}")
    # Fallback URL noto
    return "https://www.poliziadistato.it/statics/30/elenco-tratte-controllate-con-il-tutor---marzo-2025.pdf"


def scrape_polizia_pdf():
    """Scarica e analizza il PDF della Polizia di Stato per contare i tratti."""
    print("📄 Cross-check PDF Polizia di Stato...")
    try:
        import pdfplumber
    except ImportError:
        print("   pdfplumber non installato, skip cross-check PDF")
        return None, None

    url = find_polizia_pdf_url()
    print(f"   URL: {url}")

    # Estrai data dal nome file
    pdf_date = None
    date_match = re.search(r'---(\w+-\d{4})\.pdf', url)
    if date_match:
        pdf_date = date_match.group(1)

    try:
        resp = requests.get(url, headers=HEADERS, timeout=60)
        resp.raise_for_status()

        with tempfile.NamedTemporaryFile(suffix='.pdf', delete=False) as f:
            f.write(resp.content)
            tmp_path = f.name

        count = 0
        highways = set()
        with pdfplumber.open(tmp_path) as pdf:
            for page in pdf.pages:
                # Usa extract_tables (il PDF e' una tabella Excel convertita)
                for table in (page.extract_tables() or []):
                    for row in table:
                        if not row or not any(row):
                            continue
                        row_text = ' '.join(str(c) for c in row if c)
                        if re.search(r'A\d+', row_text):
                            count += 1
                            hw = re.search(r'(A\d+)', row_text)
                            if hw:
                                highways.add(hw.group(1))

                # Fallback: analisi testo se nessuna tabella trovata
                if count == 0:
                    text = page.extract_text() or ""
                    for line in text.split('\n'):
                        if re.search(r'A\d+', line) and len(line) > 10:
                            count += 1
                            hw = re.search(r'(A\d+)', line)
                            if hw:
                                highways.add(hw.group(1))

        os.unlink(tmp_path)
        print(f"   Trovati ~{count} tratti in {len(highways)} autostrade (data PDF: {pdf_date or '?'})")
        return count, pdf_date
    except Exception as e:
        print(f"   Errore PDF: {e}")
        return None, pdf_date


def make_seg_key(highway, start_name, end_name):
    """Crea chiave normalizzata per matching tra fonti."""
    return f"{highway}|{normalize_name(start_name).lower()}|{normalize_name(end_name).lower()}"


def build_source_lookup(segments):
    """Costruisce un set di chiavi dai segmenti scrappati."""
    keys = set()
    for seg in segments:
        key = make_seg_key(seg.get("highway", ""), seg.get("start_name", ""), seg.get("end_name", ""))
        keys.add(key)
    return keys


def validate_sources(hardcoded_count, segments):
    """Valida i dati incrociando le fonti, logga risultati, genera alert.
    Restituisce i risultati con il mapping per-segmento delle fonti."""
    print("\n" + "=" * 50)
    print("  VALIDAZIONE FONTI")
    print("=" * 50 + "\n")

    results = {
        "timestamp": datetime.now().isoformat(),
        "hardcoded_count": hardcoded_count,
        "alerts": []
    }

    # 1. Scrape Autostrade.it
    autostrade_segs, autostrade_date = scrape_autostrade()
    results["autostrade_count"] = len(autostrade_segs)
    results["autostrade_date"] = autostrade_date

    # 2. Scrape Sicurauto.it
    sicurauto_segs, tutor30, sicurauto_date = scrape_sicurauto()
    results["sicurauto_count"] = len(sicurauto_segs)
    results["tutor30_count"] = len(tutor30)
    results["sicurauto_date"] = sicurauto_date

    # 3. PDF Polizia di Stato
    pdf_count, pdf_date = scrape_polizia_pdf()
    results["polizia_pdf_count"] = pdf_count
    results["polizia_pdf_date"] = pdf_date

    # --- Matching per-segmento: quale fonte conferma ogni tratto? ---
    autostrade_keys = build_source_lookup(autostrade_segs)
    sicurauto_keys = build_source_lookup(sicurauto_segs)

    # Per ogni segmento, trova la fonte piu' recente che lo conferma
    segment_sources = {}
    for seg in segments:
        key = make_seg_key(seg.get("highway", ""), seg.get("start_name", ""), seg.get("end_name", ""))
        sources_found = []

        if key in autostrade_keys and autostrade_date:
            sources_found.append(("Autostrade per l'Italia", autostrade_date))
        if key in sicurauto_keys and sicurauto_date:
            sources_found.append(("Sicurauto.it", sicurauto_date))

        # Scegli la fonte con data piu' recente
        if sources_found:
            sources_found.sort(key=lambda x: x[1], reverse=True)
            best_source, best_date = sources_found[0]
        else:
            # Non trovato in nessuna fonte live, usa PDF date o "dati pre-caricati"
            if pdf_date:
                best_source = "Polizia di Stato (PDF)"
                best_date = pdf_date
            else:
                best_source = "dati pre-caricati"
                best_date = None

        display_key = f"{seg.get('highway', '')}|{seg.get('start_name', '')}|{seg.get('end_name', '')}"
        segment_sources[display_key] = {
            "source": best_source,
            "date": best_date
        }

    results["segment_sources"] = segment_sources

    matched_auto = sum(1 for k in segment_sources.values() if "Autostrade" in k["source"])
    matched_sicura = sum(1 for k in segment_sources.values() if "Sicurauto" in k["source"])
    matched_preloaded = sum(1 for k in segment_sources.values() if "pre-caricati" in k["source"])
    print(f"\n📊 Matching per-segmento:")
    print(f"   Confermati da Autostrade.it: {matched_auto}")
    print(f"   Confermati da Sicurauto.it:  {matched_sicura}")
    print(f"   Solo dati pre-caricati:      {matched_preloaded}")

    # --- Alert logic ---

    if len(autostrade_segs) == 0:
        results["alerts"].append({
            "level": "warning",
            "source": "Autostrade.it",
            "message": "0 tratti estratti - sito irraggiungibile o struttura HTML cambiata"
        })
    elif abs(len(autostrade_segs) - hardcoded_count) > 10:
        results["alerts"].append({
            "level": "info",
            "source": "Autostrade.it",
            "message": f"Discrepanza: {len(autostrade_segs)} tratti online vs {hardcoded_count} hardcoded"
        })

    if len(sicurauto_segs) == 0:
        results["alerts"].append({
            "level": "warning",
            "source": "Sicurauto.it",
            "message": "0 tratti estratti - sito irraggiungibile o struttura HTML cambiata"
        })
    elif len(sicurauto_segs) > hardcoded_count + 5:
        results["alerts"].append({
            "level": "info",
            "source": "Sicurauto.it",
            "message": f"Possibili nuovi tratti: {len(sicurauto_segs)} online vs {hardcoded_count} hardcoded"
        })

    if pdf_count is not None and abs(pdf_count - hardcoded_count) > 15:
        results["alerts"].append({
            "level": "info",
            "source": "Polizia di Stato",
            "message": f"Discrepanza: ~{pdf_count} tratti nel PDF vs {hardcoded_count} hardcoded"
        })

    # Confronto con run precedente (calo improvviso)
    prev = None
    if LOG_FILE.exists():
        try:
            with open(LOG_FILE, "r") as f:
                prev = json.load(f)
        except Exception:
            pass

    if prev:
        for key, label in [("autostrade_count", "Autostrade.it"), ("sicurauto_count", "Sicurauto.it")]:
            prev_val = prev.get(key, 0)
            curr_val = results.get(key, 0)
            if prev_val > 10 and curr_val > 0 and curr_val < prev_val * 0.9:
                drop_pct = (prev_val - curr_val) / prev_val * 100
                results["alerts"].append({
                    "level": "warning",
                    "source": label,
                    "message": f"Calo improvviso: {prev_val} -> {curr_val} (-{drop_pct:.0f}%)"
                })

    # Salva log (persiste tra i run per confronto) — senza segment_sources (troppo grande)
    log_data = {k: v for k, v in results.items() if k != "segment_sources"}
    with open(LOG_FILE, "w", encoding="utf-8") as f:
        json.dump(log_data, f, indent=2, ensure_ascii=False)

    # Salva alert separatamente per il workflow
    if results["alerts"]:
        with open(ALERTS_FILE, "w", encoding="utf-8") as f:
            json.dump(results["alerts"], f, indent=2, ensure_ascii=False)
        print(f"\n⚠️  {len(results['alerts'])} alert generati!")
        for a in results["alerts"]:
            print(f"   [{a['level'].upper()}] {a['source']}: {a['message']}")
    else:
        if ALERTS_FILE.exists():
            ALERTS_FILE.unlink()
        print("\n✅ Nessun alert - tutte le fonti coerenti")

    # Riepilogo
    print(f"\n--- Riepilogo fonti ---")
    print(f"   Dati in uso (hardcoded): {hardcoded_count} tratti")
    print(f"   Autostrade.it (scrape):  {results['autostrade_count']} tratti (pagina: {autostrade_date or '?'})")
    print(f"   Sicurauto.it (scrape):   {results['sicurauto_count']} tratti (pagina: {sicurauto_date or '?'})")
    pdf_str = f"~{pdf_count}" if pdf_count else "N/A"
    print(f"   Polizia di Stato (PDF):  {pdf_str} tratti ({pdf_date or '?'})")

    return results


def save_metadata(segment_count, validation_results=None):
    """Salva metadata.json con info aggiornamento per la web app,
    incluso il mapping per-segmento delle fonti."""
    meta = {
        "last_update": datetime.now().strftime("%Y-%m-%d"),
        "segment_count": segment_count,
        "sources": {
            "primary": {
                "name": "Autostrade per l'Italia",
                "url": AUTOSTRADE_URL,
                "date": None
            },
            "secondary": {
                "name": "Sicurauto.it",
                "url": SICURAUTO_URL,
                "date": None
            },
            "cross_check": {
                "name": "Polizia di Stato (PDF)",
                "date": None
            }
        },
        "segment_sources": {}
    }

    if validation_results:
        meta["sources"]["primary"]["date"] = validation_results.get("autostrade_date")
        meta["sources"]["secondary"]["date"] = validation_results.get("sicurauto_date")
        meta["sources"]["cross_check"]["date"] = validation_results.get("polizia_pdf_date")
        meta["segment_sources"] = validation_results.get("segment_sources", {})
        if validation_results.get("alerts"):
            meta["has_alerts"] = True
            meta["alert_count"] = len(validation_results["alerts"])

    with open(METADATA_FILE, "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2, ensure_ascii=False)
    print(f"📋 Metadata salvato in {METADATA_FILE}")


def main():
    print("🚗 Tutor Map Scraper - Aggiornamento dati\n")

    # Carica database caselli
    caselli_db = load_caselli_coords()
    print(f"📍 Database caselli caricato: {sum(len(v) for v in caselli_db.values())} entries\n")

    # Usa dati hardcoded (fonte: Autostrade per l'Italia)
    segments = use_hardcoded_data()

    # Aggiungi coordinate GPS
    segments = add_coordinates(segments, caselli_db)

    # Filtra segmenti senza coordinate
    valid = [s for s in segments if s.get("start_coords") and s.get("end_coords")]
    invalid = len(segments) - len(valid)

    print(f"\n✅ Tratti totali: {len(segments)}")
    print(f"✅ Tratti con coordinate: {len(valid)}")
    if invalid:
        print(f"⚠️  Tratti senza coordinate: {invalid}")

    # Aggiungi tracciati reali via OSRM (con cache)
    valid = add_route_geometries_cached(valid)

    # Salva output
    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        json.dump(valid, f, indent=2, ensure_ascii=False)

    print(f"\n💾 Salvato in {OUTPUT_FILE}")

    # Validazione incrociata fonti + logging
    validation = validate_sources(len(valid), valid)

    # Salva metadata per la web app
    save_metadata(len(valid), validation)


if __name__ == "__main__":
    main()
