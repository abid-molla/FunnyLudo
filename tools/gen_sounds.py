"""Synthesizes the funny sound effects for Funny Ludo (stdlib only)."""
import math, random, struct, wave, sys, os

SR = 22050
TAU = 2 * math.pi
random.seed(7)


def n(x):
    return int(x * SR)


def silence(d):
    return [0.0] * n(d)


def cat(*parts):
    out = []
    for p in parts:
        out.extend(p)
    return out


def mix(*parts):
    m = max(len(p) for p in parts)
    out = [0.0] * m
    for p in parts:
        for i, v in enumerate(p):
            out[i] += v
    return out


def scale(p, g):
    return [v * g for v in p]


def lin(a, b, d):
    return lambda t: a + (b - a) * min(t / d, 1.0)


def expo(a, b, d):
    return lambda t: a * (b / a) ** min(t / d, 1.0)


def vib(f, depth, rate):
    return lambda t: f(t) + depth * math.sin(TAU * rate * t)


sine = math.sin


def saw(p):
    return sum(math.sin(k * p) / k for k in range(1, 7)) * 0.55


def sq(p):
    return sum(math.sin(k * p) / k for k in (1, 3, 5, 7)) * 0.8


def squeak_shape(p):
    return math.sin(p) + 0.4 * math.sin(2 * p)


def env_ad(a=0.005, r=0.05):
    def e(t, d):
        if t < a:
            return t / a
        if t > d - r:
            return max(0.0, (d - t) / r)
        return 1.0
    return e


def env_exp(a, tau):
    def e(t, d):
        tail = 1.0 if t < d - 0.01 else max(0.0, (d - t) / 0.01)
        return min(1.0, t / a) * math.exp(-t / tau) * tail
    return e


def osc(dur, f, shape=sine, env=None, amp=1.0):
    env = env or env_ad()
    out = []
    ph = 0.0
    for i in range(n(dur)):
        t = i / SR
        ph += TAU * f(t) / SR
        out.append(amp * env(t, dur) * shape(ph))
    return out


def noise_burst(dur, tau):
    return [random.uniform(-1, 1) * math.exp(-(i / SR) / tau) for i in range(n(dur))]


# ---------- individual sounds ----------

def make_roll():
    parts = []
    for _ in range(9):
        f0 = random.choice([900, 1300, 1700, 2200])
        dur = 0.05
        click = [(random.uniform(-1, 1) * 0.6 + math.sin(TAU * f0 * (i / SR)) * 0.6)
                 * math.exp(-(i / SR) / 0.012) for i in range(n(dur))]
        parts.append(click)
        parts.append(silence(random.uniform(0.01, 0.04)))
    return cat(*parts)


def make_step():
    return osc(0.09, lin(700, 1100, 0.09), sine, env_exp(0.003, 0.035))


def make_out():  # boing
    d = 0.6
    f = lambda t: 170 + 520 * math.exp(-t / 0.15) + 70 * math.sin(TAU * 14 * t) * math.exp(-t / 0.3)
    a = osc(d, f, lambda p: math.sin(p) + 0.3 * saw(p), env_exp(0.004, 0.25))
    return a


def make_six():  # slide whistle up + tweet
    up = osc(0.5, vib(lin(500, 1800, 0.5), 25, 8), sine, env_ad(0.02, 0.1), 0.6)
    tweet = osc(0.14, vib(lambda t: 1800, 40, 18), sine, env_ad(0.01, 0.05), 0.5)
    return cat(up, tweet)


def make_cap1():  # bonk + slide whistle down
    thump = osc(0.25, expo(150, 55, 0.25), sine, env_exp(0.002, 0.08), 1.0)
    bonk = mix(thump, scale(noise_burst(0.1, 0.02), 0.5))
    slide = osc(0.75, vib(expo(1500, 180, 0.75), 30, 9), sine, env_ad(0.02, 0.15), 0.6)
    return cat(bonk, silence(0.05), slide)


def make_cap2():  # sad trombone
    notes = [(233, 0.38), (220, 0.38), (208, 0.38), (196, 1.0)]
    parts = []
    for idx, (fr, d) in enumerate(notes):
        last = idx == len(notes) - 1
        if last:
            f = lambda t, fr=fr: vib(expo(fr, fr * 0.82, 1.0), fr * 0.025, 5.5)(t)
        else:
            f = lambda t, fr=fr: fr * (1 + 0.012 * math.sin(TAU * 5 * t))
        parts.append(osc(d, f, saw, env_ad(0.05, 0.08), 0.7))
        parts.append(silence(0.04))
    return cat(*parts)


def vowel(dur, f0, formant=850):
    out = []
    ph = 0.0
    for i in range(n(dur)):
        t = i / SR
        f = f0 * (1 - 0.15 * t / dur)
        ph += TAU * f / SR
        s = 0.0
        k = 1
        while f * k < 4000 and k < 20:
            w = math.exp(-((f * k - formant) / 450.0) ** 2) + 0.05
            s += w * math.sin(k * ph)
            k += 1
        a = min(1.0, t / 0.01) * math.exp(-t / (dur * 0.7))
        out.append(s * a * 0.35)
    return out


def make_cap3():  # ha ha ha ha ha
    parts = []
    for i in range(5):
        parts.append(vowel(0.13, 330 - 22 * i))
        parts.append(silence(0.07))
    return cat(*parts)


def make_cap4():  # squeaky toy "eek eek"
    a = osc(0.14, vib(lin(900, 1700, 0.14), 30, 25), squeak_shape, env_ad(0.01, 0.04), 0.6)
    b = osc(0.22, vib(lin(1200, 2100, 0.22), 40, 25), squeak_shape, env_ad(0.01, 0.06), 0.6)
    return cat(a, silence(0.05), b, silence(0.04), a)


def make_raspberry():
    d = 0.65
    jit = []
    v = 0.0
    for _ in range(n(d)):
        v = 0.995 * v + random.uniform(-1, 1) * 0.6
        jit.append(v)

    def f(t):
        i = min(int(t * SR), len(jit) - 1)
        return 85 + 22 * math.sin(TAU * 3 * t) + 18 * jit[i] - 25 * t

    def env(t, dd):
        flutter = 0.6 + 0.4 * (1 if math.sin(TAU * 30 * t) > 0 else -1)
        return flutter * env_ad(0.02, 0.15)(t, dd)
    body = osc(d, f, saw, env, 0.9)
    return mix(body, scale([random.uniform(-1, 1) * 0.15 * (1 if math.sin(TAU * 30 * (i / SR)) > 0 else 0.2)
                            for i in range(n(d))], 1.0))


def make_finish():  # ta-da
    notes = [523, 659, 784]
    parts = [osc(0.09, lambda t, fr=fr: fr, sq, env_ad(0.005, 0.03), 0.5) for fr in notes]
    last = osc(0.45, vib(lambda t: 1047, 12, 6), sq, env_ad(0.005, 0.15), 0.5)
    return cat(*parts, last)


def make_win():
    seq = [(392, .15), (523, .15), (659, .15), (784, .3), (659, .12), (784, .7)]
    parts = []
    for i, (fr, d) in enumerate(seq):
        f = vib(lambda t, fr=fr: fr, 10 if i == len(seq) - 1 else 0, 6)
        parts.append(osc(d, f, sq, env_ad(0.005, 0.05), 0.5))
    fan = cat(*parts)
    whistle = osc(0.5, vib(lin(600, 2200, 0.5), 30, 8), sine, env_ad(0.02, 0.1), 0.5)
    return cat(fan, silence(0.05), whistle)


SOUNDS = {
    "snd_roll": make_roll,
    "snd_step": make_step,
    "snd_out": make_out,
}


def write(path, samples):
    peak = max(abs(s) for s in samples) or 1.0
    g = 0.9 / peak
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s * g)) * 32767)) for s in samples))


if __name__ == "__main__":
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for name, fn in SOUNDS.items():
        s = fn()
        write(os.path.join(out, name + ".wav"), s)
        print(f"{name}.wav  {len(s) / SR:.2f}s")
