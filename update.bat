@echo off
echo ================================
echo  Tutor Map - Aggiornamento dati
echo ================================
echo.

cd /d "%~dp0"

echo [1/2] Aggiornamento tratti tutor...
python scraper.py
if errorlevel 1 (
    echo ERRORE: scraper.py fallito!
    pause
    exit /b 1
)

echo.
echo [2/2] Generazione file KML...
python generate_kml.py
if errorlevel 1 (
    echo ERRORE: generate_kml.py fallito!
    pause
    exit /b 1
)

echo.
echo ================================
echo  Aggiornamento completato!
echo  - tutor_segments.json aggiornato
echo  - tutor.kml aggiornato
echo  - Apri index.html per la mappa
echo ================================
pause
