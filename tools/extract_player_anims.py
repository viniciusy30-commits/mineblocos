#!/usr/bin/env python3
"""Extrai as animacoes do jogador do pack 'Actions And Stuff' (.mcpack) para
app/src/main/assets/player_anims.json.

Uso:  python3 tools/extract_player_anims.py "Actions-And-Stuff-2.0.mcpack.zip"

O pack guarda os JSONs dentro de arquivos .brarchive (formato do Bedrock):
  cabecalho 16 bytes (magic, qtd, versao) + N entradas de 256 bytes
  [1 byte tamanho do nome | nome | ... | u32 offset | u32 tamanho] + dados.
"""
import io, json, os, struct, sys, zipfile

KEEP = [  # nome no pack (animation.oreville_ans.vayhkdd.<nome>) -> papel no jogo
    "dhyaxuw",          # respiracao parado (idle)
    "move",             # andar / correr
    "move_pqnqsct",     # andar de costas
    "vcnwlon",          # andar agachado
    "vcnwlon_pqnqsct",  # andar agachado de costas
    "jczgdau_tsailnj",  # entrar na pose agachada
    "jczgdau_bghnmsg",  # levantar do agachado
    "qlobuam",          # pulo
    "qlobuam_right",    # pulo correndo (pe direito)
    "qlobuam_left",     # pulo correndo (pe esquerdo)
    "cpjhrkw",          # queda
    "kadhbmz_cpjhrkw",  # queda longa (bracos balancando)
    "zijlcyt",          # aterrissagem parado
    "zijlcyt_move",     # aterrissagem andando
    "swim_arms",        # nadar (superficie): bracos
    "swim_legs",        # nadar (superficie): pernas e corpo
    "ozrmicj",          # nadar (corpo todo, inclinando com a subida/descida)
    "vslwwno.riding_legs",  # sentado: pernas dobradas para frente
]
PREFIX = "animation.oreville_ans.vayhkdd."

def brarchive(data):
    cnt = struct.unpack_from("<I", data, 8)[0]
    base = 16 + cnt * 256
    out = {}
    for i in range(cnt):
        p = 16 + i * 256
        name = data[p + 1:p + 1 + data[p]].decode()
        off, size = struct.unpack_from("<II", data, p + 248)
        out[name] = data[base + off:base + off + size]
    return out

def strip(a):
    a = dict(a)
    for k in ("particle_effects", "sound_effects", "timeline"):
        a.pop(k, None)
    return a

def main(path):
    z = zipfile.ZipFile(path)
    arc = [n for n in z.namelist() if n.endswith("/__brarchive/animations.brarchive") and "/subpacks/" not in n]
    anims = {}
    for f in brarchive(z.read(arc[0])).values():
        anims.update(json.loads(f)["animations"])
    out = {}
    for n in KEEP:
        full = PREFIX + n if "." not in n else "animation.oreville_ans." + n
        out[n.split(".")[-1]] = strip(anims[full])
    dst = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "player_anims.json")
    with open(dst, "w") as fh:
        json.dump(out, fh, separators=(",", ":"))
    print("ok:", len(out), "animacoes ->", os.path.normpath(dst), os.path.getsize(dst), "bytes")

if __name__ == "__main__":
    main(sys.argv[1])
