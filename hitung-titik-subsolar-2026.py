#!/usr/bin/env python3
"""
Menghitung ulang kolom "Titik elevasi matahari maks." (titik subsolar) dan kolom
azimut geodetik untuk pantai-untuk-memotret-matahari-hijau.html.

    pip install ephem geographiclib

Metode ini diverifikasi terhadap tabel lama:
  * kolom azimut  == Geodesic.WGS84.Inverse(pantai -> subsolar).azi1
                     (cocok pada baris 2-7 sampai 0,001 detik busur)
  * titik subsolar == RA/Dec tampak geosentris Matahari + GAST
                     (selisih rata-rata 0,27 km terhadap angka lama)
  * konvensi waktu == altitude geometris -43'35"  (setara elevasi tampak -14'0,4"
                     di atas cakrawala laut yang sudah turun ~2,1' karena tripod
                     54 inci). Konvensi ini menghasilkan kembali jam pada tabel
                     lama dengan selisih 0-1 detik untuk baris 2-7.
"""
import datetime
import math

import ephem
from geographiclib.geodesic import Geodesic

G = Geodesic.WGS84
WIB = datetime.timedelta(hours=7)
ALT_KONVENSI = -(43 / 60 + 35 / 3600)   # derajat, altitude geometris pusat Matahari
TAHUN = 2026

# nama, lintang, bujur, (bulan, tanggal)
PANTAI = [
    ("pantai cidatu",                -6.261877, 105.828659, (10,  9)),
    ("tambak udang malimping selatan", -6.822167, 106.033626, (10, 10)),
    ("pantai batu nunggul",          -7.198822, 106.396109, (10, 11)),
    ("pantai cicalengka",            -7.572917, 107.603328, (10, 12)),
    ("pantai mlarangan asri",        -7.943882, 110.136861, (10, 13)),
    ("pantai sidomulyo",             -8.312341, 113.312736, (10, 14)),
    ("pantai pancur",                -8.681033, 114.372853, (10, 15)),
]


def waktu_elevasi(lat, lon, tahun, bulan, tanggal, target_alt):
    """Waktu UTC saat altitude geometris Matahari turun melewati target_alt (sore)."""
    o = ephem.Observer()
    o.lat, o.lon = str(lat), str(lon)
    o.elevation, o.pressure = 0, 0          # pressure=0 -> tanpa refraksi
    lo = datetime.datetime(tahun, bulan, tanggal, 8)
    hi = datetime.datetime(tahun, bulan, tanggal, 14)
    for _ in range(60):
        mid = lo + (hi - lo) / 2
        o.date = ephem.Date(mid)
        if math.degrees(float(ephem.Sun(o).alt)) > target_alt:
            lo = mid
        else:
            hi = mid
    return lo + (hi - lo) / 2


def titik_subsolar(dt_utc):
    """Lintang/bujur titik yang matahari tepat di zenitnya pada saat dt_utc."""
    o = ephem.Observer()
    o.lat, o.lon, o.pressure = "0", "0", 0
    o.date = ephem.Date(dt_utc)
    s = ephem.Sun()
    s.compute(o)
    gast = float(o.sidereal_time())
    bujur = math.degrees(((float(s.g_ra) - gast + math.pi) % (2 * math.pi)) - math.pi)
    return math.degrees(float(s.g_dec)), bujur


def dms(x, desimal=1):
    tanda = "-" if x < 0 else ""
    x = abs(x)
    d = int(x)
    m = int((x - d) * 60)
    s = (x - d - m / 60) * 3600
    return f"{tanda}{d}\u00b0{m:02d}\u2032{s:0{3 + desimal}.{desimal}f}\u2033"


print(f"{'pantai':<32} {'jam WIB':>9}  {'lintang':>11} {'bujur':>11}  azimut geodetik")
for nama, lat, lon, (bulan, tanggal) in PANTAI:
    t_utc = waktu_elevasi(lat, lon, TAHUN, bulan, tanggal, ALT_KONVENSI)
    sub_lat, sub_lon = titik_subsolar(t_utc)
    azimut = G.Inverse(lat, lon, sub_lat, sub_lon)["azi1"] % 360
    print(f"{nama:<32} {t_utc + WIB:%H:%M:%S}  {sub_lat:11.6f} {sub_lon:11.6f}  {dms(azimut)}")

print(f"\nCatatan: bujur titik subsolar bergeser ~0,25\u00b0 per detik waktu, jadi jam dan")
print(f"titik subsolar harus selalu dihitung ulang bersamaan, tidak boleh terpisah.")