#!/usr/bin/env python3
"""
Menghitung ulang tabel pada pantai-untuk-memotret-matahari-hijau.html memakai
NREL/NLR Solar Position Algorithm (SPA) + GeographicLib.

Persiapan
---------
1. Unduh spa.c dan spa.h dari https://midcdmz.nrel.gov/spa/ (isi surel dulu).
   Kode SPA tidak boleh disebarkan ulang, jadi ambil sendiri dari sana.
2. Bangun pustaka bersama di folder yang sama dengan berkas ini:
       gcc -O2 -fPIC -shared -o libspa.so spa.c -lm
3. pip install geographiclib

Patokan yang dipakai
--------------------
* Waktu dicari saat  e0 = -14'0,4"  (sudut elevasi toposentris SPA SEBELUM
  koreksi refraksi). Skala e0 dipilih supaya sebanding dengan ambang matahari
  terbenam milik SPA sendiri, h0' = -(SUN_RADIUS + atmos_refract) = -0,83337 deg.
  Catatan: e (setelah koreksi refraksi) tidak pernah bisa mencapai -14'0,4";
  nilai paling rendah yang bisa dihasilkannya sekitar -12'54".
* Titik subsolar diambil dari alpha, delta, dan nu keluaran SPA pada detik yang
  sama, lalu azimut geodetik dihitung dengan GeographicLib pada elipsoid WGS 84.
* Waktu dibulatkan ke detik penuh LEBIH DULU, baru titik subsolar dan azimut
  dihitung pada detik bulat itu, supaya satu baris tabel konsisten satu sama
  lain. Bujur subsolar bergeser sekitar 0,0042 deg per detik waktu, jadi jam,
  titik subsolar, dan azimut tidak boleh dihitung terpisah.
"""
import ctypes
import datetime
import os

from geographiclib.geodesic import Geodesic

G = Geodesic.WGS84
WIB = datetime.timedelta(hours=7)   # lokasi pemotretan
WAB = datetime.timedelta(hours=1)   # Angola, UTC+1

TARGET = -(14 / 60 + 0.4 / 3600)          # -14'0,4"
SUNSET = -(0.26667 + 0.5667)              # h0' milik SPA = -50'0,1"
DELTA_T = 69.2                            # detik, untuk 2026

PANTAI = [
    ("pantai cidatu",                  -6.261877, 105.828659, (2026, 10,  9)),
    ("tambak udang malimping selatan", -6.822167, 106.033626, (2026, 10, 10)),
    ("pantai batu nunggul",            -7.198822, 106.396109, (2026, 10, 11)),
    ("pantai cicalengka",              -7.572917, 107.603328, (2026, 10, 12)),
    ("pantai mlarangan asri",          -7.943882, 110.136861, (2026, 10, 13)),
    ("pantai sidomulyo",               -8.312341, 113.312736, (2026, 10, 14)),
    ("pantai pancur",                  -8.681033, 114.372853, (2026, 10, 15)),
]

_D, _I = ctypes.c_double, ctypes.c_int


class SpaData(ctypes.Structure):
    _fields_ = [
        ("year", _I), ("month", _I), ("day", _I), ("hour", _I), ("minute", _I),
        ("second", _D), ("delta_ut1", _D), ("delta_t", _D), ("timezone", _D),
        ("longitude", _D), ("latitude", _D), ("elevation", _D), ("pressure", _D),
        ("temperature", _D), ("slope", _D), ("azm_rotation", _D), ("atmos_refract", _D),
        ("function", _I),
        ("jd", _D), ("jc", _D), ("jde", _D), ("jce", _D), ("jme", _D),
        ("l", _D), ("b", _D), ("r", _D), ("theta", _D), ("beta", _D),
        ("x0", _D), ("x1", _D), ("x2", _D), ("x3", _D), ("x4", _D),
        ("del_psi", _D), ("del_epsilon", _D), ("epsilon0", _D), ("epsilon", _D),
        ("del_tau", _D), ("lamda", _D), ("nu0", _D), ("nu", _D),
        ("alpha", _D), ("delta", _D), ("h", _D), ("xi", _D), ("del_alpha", _D),
        ("delta_prime", _D), ("alpha_prime", _D), ("h_prime", _D),
        ("e0", _D), ("del_e", _D), ("e", _D),
        ("eot", _D), ("srha", _D), ("ssha", _D), ("sta", _D),
        ("zenith", _D), ("azimuth_astro", _D), ("azimuth", _D), ("incidence", _D),
        ("suntransit", _D), ("sunrise", _D), ("sunset", _D),
    ]


_lib = ctypes.CDLL(os.path.join(os.path.dirname(os.path.abspath(__file__)), "libspa.so"))
_lib.spa_calculate.argtypes = [ctypes.POINTER(SpaData)]
_lib.spa_calculate.restype = ctypes.c_int


def spa_at(dt_utc, lat, lon):
    s = SpaData()
    s.year, s.month, s.day = dt_utc.year, dt_utc.month, dt_utc.day
    s.hour, s.minute = dt_utc.hour, dt_utc.minute
    s.second = dt_utc.second + dt_utc.microsecond / 1e6
    s.timezone, s.delta_ut1, s.delta_t = 0.0, 0.0, DELTA_T
    s.longitude, s.latitude, s.elevation = lon, lat, 0.0
    s.pressure, s.temperature = 1010.0, 15.0
    s.slope, s.azm_rotation, s.atmos_refract = 0.0, 0.0, 0.5667
    s.function = 3                                  # SPA_ALL
    rc = _lib.spa_calculate(ctypes.byref(s))
    if rc:
        raise ValueError(f"SPA error code {rc}")
    return s


def cari_waktu(lat, lon, y, m, d, target):
    """Waktu UTC saat e0 turun melewati target (sore hari), lewat bagi dua."""
    lo, hi = datetime.datetime(y, m, d, 8), datetime.datetime(y, m, d, 14)
    for _ in range(64):
        mid = lo + (hi - lo) / 2
        if spa_at(mid, lat, lon).e0 > target:
            lo = mid
        else:
            hi = mid
    return lo + (hi - lo) / 2


def titik_subsolar(dt_utc):
    s = spa_at(dt_utc, 0.0, 0.0)
    return s.delta, ((s.alpha - s.nu + 180.0) % 360.0) - 180.0


def dms(x, dec=1):
    tanda = "-" if x < 0 else ""
    x = abs(x)
    d = int(x)
    m = int((x - d) * 60)
    s = (x - d - m / 60) * 3600
    return f"{tanda}{d}\u00b0{m:02d}\u2032{s:0{3 + dec}.{dec}f}\u2033"


if __name__ == "__main__":
    print(f"{'pantai':<32} {'WIB':>9} {'WAB':>9} {'azimut':>16} "
          f"{'lintang':>11} {'bujur':>11} {'ke -50\u2032':>9}")
    for nama, lat, lon, (Y, M, D) in PANTAI:
        t = cari_waktu(lat, lon, Y, M, D, TARGET)
        t = (t + datetime.timedelta(seconds=0.5)).replace(microsecond=0)   # bulatkan dulu
        sub_lat, sub_lon = titik_subsolar(t)                              # baru hitung
        azimut = G.Inverse(lat, lon, sub_lat, sub_lon)["azi1"] % 360
        t50 = cari_waktu(lat, lon, Y, M, D, SUNSET)
        print(f"{nama:<32} {t + WIB:%H:%M:%S} {t + WAB:%H:%M:%S} {dms(azimut):>16} "
              f"{sub_lat:11.6f} {sub_lon:11.6f} {(t50 - t).total_seconds():>8.0f}s")
