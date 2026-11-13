"""Makes a damaged live feed for the CATWATCH test: drops a few video packets every 10 s (like packet loss)."""
import sys

src, dst = sys.argv[1], sys.argv[2]
data = open(src, "rb").read()
pk = [data[i:i + 188] for i in range(0, len(data) - 187, 188) if data[i] == 0x47]
pmt = vpid = -1
for p in pk:
    pid = ((p[1] & 0x1F) << 8) | p[2]
    afc = (p[3] >> 4) & 3
    pl = 5 + p[4] if afc in (2, 3) else 4
    if p[1] & 0x40 and pid == 0:
        t = pl + 1 + p[pl]
        pmt = ((p[t + 10] & 0x1F) << 8) | p[t + 11]
    elif p[1] & 0x40 and pid == pmt and vpid < 0:
        t = pl + 1 + p[pl]
        ln = ((p[t + 1] & 0x0F) << 8) | p[t + 2]
        i = t + 12 + (((p[t + 10] & 0x0F) << 8) | p[t + 11])
        while i + 5 <= t + 3 + ln - 4:
            if p[i] in (0x1B, 0x24, 0x01, 0x02):
                vpid = ((p[i + 1] & 0x1F) << 8) | p[i + 2]
                break
            i += 5 + (((p[i + 3] & 0x0F) << 8) | p[i + 4])
vids = [k for k, p in enumerate(pk) if (((p[1] & 0x1F) << 8) | p[2]) == vpid]
drop = set()
for frac in [x / 9 for x in range(1, 9)]:  # 8 damaged spots over 90 s
    j = int(len(vids) * frac)
    for n in range(6):
        drop.add(vids[min(j + n * 4, len(vids) - 1)])
open(dst, "wb").write(b"".join(p for k, p in enumerate(pk) if k not in drop))
print("video pid", vpid, "dropped", len(drop), "packets")
