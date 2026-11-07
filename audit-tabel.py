"""Independent audit: parse the finished HTML and re-derive every number with SPA."""
import re, sys, datetime, math
sys.path.insert(0, "/home/claude/spa")
from spabind import spa_at, subsolar, dms
from geographiclib.geodesic import Geodesic
G = Geodesic.WGS84
WIB = datetime.timedelta(hours=7)
TARGET = -(14/60 + 0.4/3600)
SUNSET = -(0.26667 + 0.5667)

html = open("/mnt/user-data/outputs/pantai-untuk-memotret-matahari-hijau.html", encoding="utf-8").read()
pat = re.compile(
    r'<th scope="row">(\d)</th>\s*<td><a href="[^"]*query=(-?[\d.]+)%2C(-?[\d.]+)"'
    r'.*?<td>(\d+) Okt 2026, (\d\d):(\d\d):(\d\d) WIB</td>'
    r'\s*<td>(\d+)\u00b0(\d+)\u2032([\d,]+)\u2033</td>'
    r'\s*<td><a href="[^"]*query=(-?[\d.]+)%2C(-?[\d.]+)"'
    r'.*?<td>\d+ Okt 2026, (\d\d):(\d\d):(\d\d) WAB</td>', re.S)
rows = pat.findall(html)
assert len(rows) == 7, f"parsed {len(rows)} rows"

print(f"{'#':<2} {'e0 at published time':>21} {'vs -14\u20320,4\u2033':>12} {'subsolar':>10} {'azimuth':>10} {'WAB':>6}")
bad = 0
for (n, blat, blon, day, hh, mm, ss, ad, am, asec, slat, slon, wh, wm, wsec) in rows:
    blat, blon, slat, slon = map(float, (blat, blon, slat, slon))
    t_utc = datetime.datetime(2026, 10, int(day), int(hh), int(mm), int(ss)) - WIB

    # 1. does the published time really hit the -14'0.4" criterion?
    e0 = spa_at(t_utc, blat, blon).e0
    err_s = (e0 - TARGET) / ((spa_at(t_utc + datetime.timedelta(seconds=30), blat, blon).e0 - e0) / 30)

    # 2. subsolar point at that exact published second
    sl, sn = subsolar(t_utc)
    sep = G.Inverse(slat, slon, sl, sn)['s12']

    # 3. azimuth from the published beach coord to the published subsolar coord
    listed_az = int(ad) + int(am)/60 + float(asec.replace(",", "."))/3600
    calc_az = G.Inverse(blat, blon, slat, slon)['azi1'] % 360
    daz = (calc_az - listed_az) * 3600

    # 4. WAB column must be WIB - 6h (Angola UTC+1)
    wab_ok = (t_utc + datetime.timedelta(hours=1)).strftime("%H:%M:%S") == f"{wh}:{wm}:{wsec}"

    ok = abs(err_s) < 1.0 and sep < 20 and abs(daz) < 0.06 and wab_ok
    bad += not ok
    print(f"{n:<2} {dms(e0,1):>21} {err_s:>+10.2f}s {sep:>8.1f} m {daz:>+8.3f}\" "
          f"{'OK' if wab_ok else 'BAD':>6}  {'' if ok else '<-- CHECK'}")

# 5. caption claim: -14'0.4" is 2 min 26 s before -50'
print("\ncaption check (gap from \u221214\u20320,4\u2033 to \u221250\u2032):")
def solve(lat, lon, y, m, d, tgt):
    lo, hi = datetime.datetime(y,m,d,8), datetime.datetime(y,m,d,14)
    for _ in range(60):
        mid = lo + (hi-lo)/2
        if spa_at(mid, lat, lon).e0 > tgt: lo = mid
        else: hi = mid
    return lo + (hi-lo)/2
gaps = []
for (n, blat, blon, day, *rest) in rows:
    blat, blon = float(blat), float(blon)
    g = (solve(blat, blon, 2026, 10, int(day), SUNSET)
         - solve(blat, blon, 2026, 10, int(day), TARGET)).total_seconds()
    gaps.append(g)
print(f"  range {min(gaps):.1f}\u2013{max(gaps):.1f} s  = 2 min {min(gaps)-120:.0f}\u2013{max(gaps)-120:.0f} s"
      f"   -> caption '2 menit 26 detik' {'OK' if 140 <= min(gaps) and max(gaps) <= 152 else 'WRONG'}")

print("\nAUDIT PASSED" if bad == 0 else f"\n{bad} ROW(S) FAILED")
