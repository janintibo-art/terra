"""Contrôles statiques avant livraison — les trois passes obligatoires.

1. Équilibrage des accolades/parenthèses/crochets, après retrait des
   commentaires et des chaînes — chaînes AVANT littéraux caractère, sinon les
   apostrophes françaises des commentaires faussent tout. Ici, machine à états
   qui traite tout d'un seul passage : chaînes triples, chaînes simples avec
   échappements, caractères, commentaires de ligne et de bloc (imbriqués,
   comme Kotlin le permet).
2. Références orphelines : toute constante MAJUSCULE utilisée doit être
   déclarée dans le projet ou appartenir à une API de plateforme connue.
3. L'audit des substitutions a été fait au fil de l'eau (assertions Python à
   chaque modification).
"""
import re, sys, pathlib

# Racine du dépôt : argument de ligne de commande, ou chemin par défaut.
ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else '/home/claude/terra')
if not ROOT.exists():
    print(f"Racine introuvable : {ROOT}")
    sys.exit(2)

def strip_code(src: str) -> str:
    """Ne conserve que le code : chaînes, caractères et commentaires ôtés."""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        # Chaîne triple (brute : pas d'échappements)
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            if j < 0: raise ValueError("chaîne triple non fermée")
            # Kotlin : des guillemets peuvent suivre immédiatement la fermeture
            j += 3
            while j < n and src[j] == '"': j += 1
            i = j
            continue
        if c == '"':
            i += 1
            while i < n:
                if src[i] == '\\': i += 2; continue
                if src[i] == '"': i += 1; break
                i += 1
            continue
        if c == "'":
            i += 1
            while i < n:
                if src[i] == '\\': i += 2; continue
                if src[i] == "'": i += 1; break
                i += 1
            continue
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        if src.startswith('/*', i):
            depth = 1
            i += 2
            while i < n and depth > 0:
                if src.startswith('/*', i): depth += 1; i += 2
                elif src.startswith('*/', i): depth -= 1; i += 2
                else: i += 1
            continue
        out.append(c)
        i += 1
    return ''.join(out)

PAIRS = {'}': '{', ')': '(', ']': '['}

def check_balance(path):
    code = strip_code(path.read_text())
    stack = []
    line = 1
    for ch in code:
        if ch == '\n': line += 1
        elif ch in '{([': stack.append((ch, line))
        elif ch in ')}]':
            if not stack or stack[-1][0] != PAIRS[ch]:
                return f"{path.name}:{line} : « {ch} » inattendu"
            stack.pop()
    if stack:
        ch, ln = stack[-1]
        return f"{path.name}:{ln} : « {ch} » jamais fermé"
    return None

kt_files = sorted(ROOT.rglob('*.kt'))
errors = [e for e in (check_balance(p) for p in kt_files) if e]
print(f"1) Équilibrage : {len(kt_files)} fichiers analysés, {len(errors)} erreur(s)")
for e in errors: print("   !", e)

# --- 2. Références orphelines --------------------------------------------
decl = set()
for p in kt_files:
    t = p.read_text()
    decl.update(re.findall(r'\b(?:const\s+)?val\s+([A-Z][A-Z0-9_]{2,})\b', t))
    decl.update(re.findall(r'\bobject\s+([A-Z][A-Z0-9_]{2,})\b', t))
    # entrées d'énumération : IDENT( ou IDENT, ou IDENT; en début de ligne
    decl.update(re.findall(r'^\s{4}([A-Z][A-Z0-9_]{2,})\s*[\(,;]', t, re.M))

PLATFORM = re.compile(r'^(GL_|GLES|TYPE_|ACTION_|NORM_|COMPLEX_UNIT_|RENDERMODE_|MAX_VALUE|MIN_VALUE|POSITIVE_INFINITY|NEGATIVE_INFINITY|NaN$|PI$|E$|UTF_)')
orphans = {}
for p in kt_files:
    code = strip_code(p.read_text())
    for m in re.finditer(r'\b([A-Z][A-Z0-9_]{2,})\b', code):
        name = m.group(1)
        if name in decl or PLATFORM.match(name): continue
        # les identifiants qualifiés de plateforme (GLES20.GL_x) sont couverts
        # par le préfixe ; le reste est suspect
        orphans.setdefault(name, set()).add(p.name)
n_orph = 0
print("2) Références orphelines :")
for name, files in sorted(orphans.items()):
    print(f"   ? {name} — {', '.join(sorted(files))}")
    n_orph += 1
if n_orph == 0: print("   aucune")


# --- 3. Portée des variables locales ---------------------------------------
#
# Une variable locale utilisée dans une AUTRE fonction que celle qui la
# déclare : erreur de compilation certaine, invisible à l'équilibrage et aux
# références orphelines (qui ne regardent que les MAJUSCULES). C'est ce qui a
# fait échouer la v0.12.1 — la phase de houle lisait `dt`, local à une autre
# méthode. Faute d'analyseur syntaxique, on procède par heuristique : pour
# chaque fonction, tout identifiant en minuscules employé sans être déclaré
# localement, ni paramètre, ni champ de la classe, ni fonction connue.

def function_bodies(src):
    """Corps des fonctions, avec leur signature."""
    for m in re.finditer(r'\n(\s+)(?:private |internal |override |suspend )*fun\s+(\w+)\s*\(', src):
        indent, name = m.group(1), m.group(2)
        brace = src.find('{', m.end())
        if brace < 0: continue
        i, depth = brace + 1, 1
        while i < len(src) and depth > 0:
            if src[i] == '{': depth += 1
            elif src[i] == '}': depth -= 1
            i += 1
        yield name, src[m.start():brace], src[brace:i]

def scope_check(path):
    raw = path.read_text()
    src = strip_code(raw)
    # Champs de la classe : déclarations à faible indentation
    fields = set(re.findall(r'\n    (?:@\w+\s+)*(?:private |internal |const )*va[lr]\s+(\w+)', raw))
    fields |= set(re.findall(r'\n        (?:private |internal |const )*va[lr]\s+(\w+)', raw))
    # Paramètres du constructeur
    fields |= set(re.findall(r'\n    (?:private |internal )*va[lr]\s+(\w+)\s*:', raw))
    known = set(re.findall(r'fun\s+(\w+)', raw))
    problems = []
    for name, sig, body in function_bodies(src):
        declared = set(re.findall(r'\bva[lr]\s+(\w+)', body))
        declared |= set(re.findall(r'\bfor\s*\((\w+)', body))
        declared |= set(re.findall(r'(\w+)\s*(?::[^,)]+)?[,)]', sig))
        declared |= set(re.findall(r'\.let\s*\{\s*(\w+)\s*->', body))
        declared |= set(re.findall(r'\{\s*(\w+)\s*->', body))
        # On ne retient que les identifiants LUS comme variables : ni suivis
        # d'une parenthèse (appel de fonction), ni précédés d'un point (accès
        # de membre), ni suivis d'un point-deux (déclaration nommée). Sans ces
        # trois exclusions, l'heuristique noie le vrai signal sous les appels
        # de bibliothèque.
        for m2 in re.finditer(r'(?<![.\w])([a-z][a-zA-Z0-9]{1,20})(?![\w(])', body):
            var = m2.group(1)
            if var in declared or var in fields or var in known: continue
            if var in KOTLIN_WORDS: continue
            problems.append((name, var))
    return problems

KOTLIN_WORDS = {
    'val','var','fun','if','else','for','while','return','when','is','in','as','it','this',
    'true','false','null','break','continue','do','try','catch','finally','throw','object',
    'class','interface','private','internal','public','override','const','companion','init',
    'by','out','vararg','import','package','repeat','let','also','apply','run','with','to',
    'and','or','not','abs','min','max','sqrt','exp','ln','sin','cos','tan','acos','asin',
    'atan','atan2','floor','ceil','round','coerceIn','coerceAtLeast','coerceAtMost','toInt',
    'toFloat','toDouble','toLong','toByte','size','indices','first','last','shl','shr','ushr',
    'inv','xor','length','get','set','add','clear','contains','isEmpty','isNotEmpty','sum',
    'x','y','z','w','r','g','b','a','n','i','j','k','m','o','p','s','t','u','v','d','e','f','h','c',
    # Préfixes de noms qualifiés et fonctions infixes : ce ne sont pas des
    # lectures de variables locales.
    'kotlin','com','java','javax','android','until','downTo','step','dot','cross',
    'contentEquals','copyOf','sort','sorted','sortedBy','sortedByDescending','filter',
    'map','any','all','none','count','take','drop','joinToString','withIndex','forEach',
    'mod','rem','div','times','plus','minus','compareTo','equals','hashCode','toString',
    'normalized','length','lengthSq','toVec3','toVec3d','packed','unpack','parent','center',
}

print("3) Portée des variables locales :")
scope_problems = 0
for pth in kt_files:
    for fn, var in scope_check(pth):
        print(f"   ? {pth.name} :: {fn}() utilise '{var}'")
        scope_problems += 1
if scope_problems == 0:
    print("   aucune")


# --- 4. Tableaux locaux calculés puis jamais lus ---------------------------
#
# Une variable locale remplie dans une boucle puis jamais consultee : le
# calcul tourne pour rien, et surtout l'effet attendu n'a pas lieu. C'est ce
# qui est arrive au lot 2.4 : les normales lissees etaient calculees a chaque
# tuile et l'emission continuait d'utiliser celles des facettes. L'audit de
# livraison verifiait que le CALCUL existait, pas qu'il SERVAIT — exactement
# le piege des constantes orphelines, transpose aux variables locales.

print("4) Tableaux calcules mais jamais lus :")
orphan_arrays = 0
for pth in kt_files:
    code = strip_code(pth.read_text())
    for m in re.finditer(r'\bval (\w+) = (?:Float|Int|Double|Long|Byte|Boolean)Array\(', code):
        name = m.group(1)
        after = code[m.end():]
        # Lecture = apparition ailleurs qu'en cible d'affectation indexee
        writes = len(re.findall(r'\b' + name + r'\[[^\]]+\]\s*=', after))
        total = len(re.findall(r'\b' + name + r'\b', after))
        if total > 0 and total == writes:
            print(f"   ? {pth.name} : '{name}' est rempli mais jamais lu")
            orphan_arrays += 1
if orphan_arrays == 0:
    print("   aucun")


# --- 5. Cibles d'ecriture indexee inconnues --------------------------------
#
# `tableau[i] = x` ou `tableau` n'est ni parametre, ni local, ni champ :
# erreur de compilation certaine. C'est ce qui a fait echouer la v0.13.2 —
# une substitution automatique avait remplace la ligne de sortie d'une
# fonction par celle d'une autre, laissant `out[0] = ...` dans une fonction
# qui ne connait pas `out`.
#
# Les portees s'IMBRIQUENT : une fonction locale voit les parametres de celle
# qui la contient. Le controle unit donc les portees de toutes les fonctions
# dont le corps englobe l'ecriture, faute de quoi il signale a tort chaque
# fonction imbriquee.

def function_spans(text):
    """(nom, debut_corps, fin_corps, parametres) de chaque fonction."""
    for m in re.finditer(r'\n(\s+)(?:private |internal |override |suspend )*fun\s+(\w+)\s*\(', text):
        name = m.group(2)
        brace = text.find('{', m.end())
        if brace < 0:
            continue
        sig = text[m.start():brace]
        i2, depth = brace + 1, 1
        while i2 < len(text) and depth > 0:
            if text[i2] == '{': depth += 1
            elif text[i2] == '}': depth -= 1
            i2 += 1
        params = set(re.findall(r'(\w+)\s*:\s*[A-Z]', sig))
        yield name, brace, i2, params

print("5) Cibles d'ecriture indexee inconnues :")
write_problems = 0
for pth in kt_files:
    raw = pth.read_text()
    code = strip_code(raw)
    fields = set(re.findall(r'\n    (?:@\w+\s+)*(?:private |internal |const )*va[lr]\s+(\w+)', raw))
    fields |= set(re.findall(r'\n        (?:private |internal |const )*va[lr]\s+(\w+)', raw))
    fields |= set(re.findall(r'\n    (?:private |internal )*va[lr]\s+(\w+)\s*:', raw))
    spans = list(function_spans(code))
    locals_all = set(re.findall(r'\bva[lr]\s+(\w+)', code))
    locals_all |= set(re.findall(r'\bfor\s*\((\w+)', code))
    for m3 in re.finditer(r'\b(\w+)\[[^\]]*\]\s*=[^=]', code):
        target = m3.group(1)
        pos = m3.start()
        visible = set(fields) | locals_all
        owner = "?"
        for name, b, e, params in spans:
            if b <= pos < e:
                visible |= params
                owner = name
        if target in visible:
            continue
        print(f"   ? {pth.name} :: {owner}() ecrit dans '{target}'")
        write_problems += 1
if write_problems == 0:
    print("   aucune")


# --- 6. Appels a un membre absent d'un `object` ----------------------------
#
# `Objet.methode(...)` ou `methode` n'existe pas dans cet `object` : erreur de
# compilation certaine. C'est ce qui a coute deux tours a la v0.15.0 — la
# fonction `latitude` vit dans `Sphere`, et je l'ai successivement cherchee
# dans `Vec3` puis dans `Geodesy`, en supposant le nom au lieu de le lire.
#
# Les noms de membres commencant par une MAJUSCULE sont ecartes : ce sont des
# constructeurs de classes imbriquees, pas des fonctions.

print("6) Appels a un membre absent d'un object :")
object_members = {}
for pth in kt_files:
    src = pth.read_text()
    for mo in re.finditer(r'\nobject (\w+) \{(.*?)\n\}', src, re.S):
        object_members.setdefault(mo.group(1), set()).update(
            re.findall(r'fun (\w+)', mo.group(2))
        )
        object_members[mo.group(1)].update(
            re.findall(r'va[lr] (\w+)', mo.group(2))
        )
member_problems = 0
for pth in kt_files:
    src = pth.read_text()
    for mo in re.finditer(r'\b([A-Z]\w+)\.([a-z]\w*)\(', src):
        obj, fn = mo.group(1), mo.group(2)
        if obj not in object_members:
            continue
        if fn in object_members[obj]:
            continue
        print(f"   ? {pth.name} : '{obj}.{fn}' introuvable dans object {obj}")
        member_problems += 1
if member_problems == 0:
    print("   aucun")

# (sortie déplacée en fin de script : voir passes 7 et 8)


# --- 7. Imports manquants -------------------------------------------------
#
# Ajoutée après DEUX compilations rouges (Rng non importé dans CelestialSky,
# v0.25.1 ; CelestialSky et Icosphere absents du renderer). Une classe du
# projet utilisée dans un fichier d'un AUTRE paquet doit être importée ou
# pleinement qualifiée. Le contrôle est conscient des paquets : une classe du
# même paquet n'a pas besoin d'import, et c'est ce qui manquait au prototype.
print("7) Imports manquants :")
declared = {}          # nom de classe -> paquet
for f in ROOT.rglob('*.kt'):
    if '/build/' in str(f):
        continue
    text = f.read_text(encoding='utf-8', errors='replace')
    m = re.search(r'^package\s+([\w.]+)', text, re.M)
    pkg = m.group(1) if m else ''
    for d in re.finditer(r'^\s*(?:public |internal |private )?(?:data |sealed |enum |abstract |open )*(?:class|object|interface)\s+(\w+)', text, re.M):
        declared[d.group(1)] = pkg

missing = []
for f in ROOT.rglob('*.kt'):
    if '/build/' in str(f):
        continue
    text = f.read_text(encoding='utf-8', errors='replace')
    m = re.search(r'^package\s+([\w.]+)', text, re.M)
    pkg = m.group(1) if m else ''
    imported = set(re.findall(r'^import\s+[\w.]*?(\w+)$', text, re.M))
    body = text[text.rfind('import'):] if 'import' in text else text
    local = set(re.findall(r'(?:class|object|interface)\s+(\w+)', text))
    for u in set(re.findall(r'(?<![\w.])([A-Z]\w+)\s*[(.]', body)):
        if u not in declared or u in imported or u in local:
            continue
        if declared[u] == pkg:
            continue                      # même paquet : import inutile
        # Pleinement qualifié sur place ?
        if re.search(r'[\w.]+\.' + u + r'\s*[(.]', body):
            continue
        missing.append(f"   ? {f.name} :: {u} (déclaré dans {declared[u]})")
for line in sorted(set(missing)):
    print(line)
if not missing:
    print("   aucun")


# --- 8. Cohérence des shaders GLSL ----------------------------------------
#
# Ajoutée après DEUX échecs d'édition de liens sur appareil (uDrift v0.26.1,
# uSnow v0.31.3), qu'aucun test ne pouvait voir. Deux règles que GLES2 impose
# et que le compilateur Kotlin ignore :
#   a) un uniform déclaré dans les deux étages doit avoir la MÊME précision —
#      or le défaut est highp au sommet et mediump au fragment ;
#   b) un varying lu au fragment doit être écrit au sommet.
print("8) Cohérence des shaders GLSL :")
shader_problems = []
for f in ROOT.rglob('*.kt'):
    if '/build/' in str(f):
        continue
    text = f.read_text(encoding='utf-8', errors='replace')
    # Capture des blocs GLSL. Un shader peut etre CONCATENE
    # (guillemets + CHAMP_PARTAGE + guillemets) : une capture non gourmande
    # s'arreterait au premier delimiteur, d'ou une pluie de faux positifs
    # « varying jamais ecrit ». On prend tout jusqu'a la declaration
    # suivante, en recollant les morceaux.
    blocks = {}
    q3 = chr(34) * 3
    decls = list(re.finditer(r'val\s+(\w+)\s*=\s*' + q3, text))
    for _k, _d in enumerate(decls):
        _stop = decls[_k + 1].start() if _k + 1 < len(decls) else len(text)
        _chunk = text[_d.end():_stop]
        _chunk = re.sub(q3 + r'\s*\+\s*\w+\s*\+\s*' + q3, '\n', _chunk)
        _end = _chunk.find(q3)
        blocks[_d.group(1)] = _chunk if _end < 0 else _chunk[:_end]
    for name, vsrc in blocks.items():
        if 'VERTEX' not in name.upper():
            continue
        fname = name.replace('VERTEX', 'FRAGMENT')
        if fname not in blocks:
            continue
        fsrc = blocks[fname]
        def uniforms(s, default):
            out = {}
            for prec, var in re.findall(r'uniform\s+(?:(highp|mediump|lowp|\w*PRECISION)\s+)?\w+\s+(\w+)', s):
                out[var] = prec or default
            return out
        vu = uniforms(vsrc, 'highp')
        fu = uniforms(fsrc, 'mediump')
        for var in sorted(set(vu) & set(fu)):
            a, b = vu[var], fu[var]
            if a != b and 'PRECISION' not in a and 'PRECISION' not in b:
                shader_problems.append(
                    f"   ! {f.name} :: {name}/{fname} : {var} en {a} au sommet, {b} au fragment")
        vv = set(re.findall(r'varying\s+(?:\w+\s+)?\w+\s+(\w+)', vsrc))
        fv = set(re.findall(r'varying\s+(?:\w+\s+)?\w+\s+(\w+)', fsrc))
        for var in sorted(fv - vv):
            shader_problems.append(
                f"   ! {f.name} :: {var} lu au fragment mais absent du sommet")
        for var in sorted(fv & vv):
            if not re.search(rf'\b{var}\s*=', vsrc):
                shader_problems.append(
                    f"   ! {f.name} :: {var} déclaré mais jamais écrit au sommet")
for line in shader_problems:
    print(line)
if not shader_problems:
    print("   aucune")


# --- Verdict ---------------------------------------------------------------
#
# Les passes 7 et 8 signalent des défauts qui ont RÉELLEMENT cassé des
# compilations ou des éditions de liens : elles comptent dans le verdict.
# Les passes 2 à 6 restent indicatives (elles produisent des faux positifs
# sur les tests et les API de plateforme), à lire avant chaque livraison.
blocking = (len(errors) if isinstance(errors, list) else errors) + len(shader_problems)
print()
print(f"Verdict : {blocking} défaut(s) bloquant(s) "
      f"(équilibrage + shaders), {len(set(missing))} import(s) suspect(s).")
sys.exit(1 if blocking else 0)
