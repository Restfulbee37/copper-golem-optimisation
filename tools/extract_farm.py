#!/usr/bin/env python3
"""Extract a copper golem farm from a Minecraft world into a version-independent JSON fixture.

The workload benchmark GameTest (src/gametest) rebuilds the farm from this file, so CI measures a real
farm design instead of an invented one. The world is only read, never modified. Standard library only.

Usage:
    python3 tools/extract_farm.py --world /path/to/world --box 46,-61,-48,72,-33,-15 \\
        --out src/gametest/resources/copper_golem_mod_test/workload/farm.json

The box is two opposite corners (inclusive) in world block coordinates. Everything inside is copied:
non-air blocks, copper golems and villagers. Container contents are not copied. Instead each container is
classified so the benchmark can fill it deterministically:
    input   copper chests (golems take items from these)
    output  chests and trapped chests, with "marker" = the most common item they held (or null if empty)
Other containers (hoppers, droppers, ...) start empty. No player data is read.
"""
import argparse
import collections
import gzip
import io
import json
import struct
import sys
import zlib
from pathlib import Path


# ---- NBT / Anvil reading -------------------------------------------------------------------------

def _read(f, fmt):
    return struct.unpack('>' + fmt, f.read(struct.calcsize(fmt)))[0]


def _string(f):
    return f.read(_read(f, 'H')).decode('utf-8', 'replace')


def _payload(f, tag):
    if tag == 1: return _read(f, 'b')
    if tag == 2: return _read(f, 'h')
    if tag == 3: return _read(f, 'i')
    if tag == 4: return _read(f, 'q')
    if tag == 5: return _read(f, 'f')
    if tag == 6: return _read(f, 'd')
    if tag == 7: return f.read(_read(f, 'i'))
    if tag == 8: return _string(f)
    if tag == 9:
        element = _read(f, 'b')
        return [_payload(f, element) for _ in range(_read(f, 'i'))]
    if tag == 10:
        compound = {}
        while (child := _read(f, 'b')) != 0:
            name = _string(f)
            compound[name] = _payload(f, child)
        return compound
    if tag == 11:
        n = _read(f, 'i')
        return list(struct.unpack('>%di' % n, f.read(4 * n)))
    if tag == 12:
        n = _read(f, 'i')
        return list(struct.unpack('>%dq' % n, f.read(8 * n)))
    raise ValueError('unknown NBT tag %d' % tag)


def read_nbt(data):
    f = io.BytesIO(data)
    tag = _read(f, 'b')
    _string(f)
    return _payload(f, tag)


def region_chunks(path):
    """Yields (chunk_x, chunk_z, nbt) for every chunk in an Anvil region file."""
    buf = path.read_bytes()
    if len(buf) < 8192:
        return
    region_x, region_z = map(int, path.name.split('.')[1:3])
    for i in range(1024):
        offset = int.from_bytes(buf[i * 4:i * 4 + 3], 'big')
        if offset == 0 or buf[i * 4 + 3] == 0:
            continue
        start = offset * 4096
        length = int.from_bytes(buf[start:start + 4], 'big')
        compression = buf[start + 4]
        raw = buf[start + 5:start + 4 + length]
        if compression == 2:
            data = zlib.decompress(raw)
        elif compression == 1:
            data = gzip.decompress(raw)
        elif compression == 3:
            data = raw
        else:
            print(f'warning: skipping chunk with unsupported compression {compression} in {path.name}', file=sys.stderr)
            continue
        yield region_x * 32 + i % 32, region_z * 32 + i // 32, read_nbt(data)


def section_states(section):
    """Yields (index, state) for a 16x16x16 chunk section; index = y * 256 + z * 16 + x."""
    states = section.get('block_states')
    if not states:
        return
    palette = states['palette']
    if len(palette) == 1 or 'data' not in states:
        for i in range(4096):
            yield i, palette[0]
        return
    bits = max(4, (len(palette) - 1).bit_length())
    per_long = 64 // bits
    mask = (1 << bits) - 1
    data = states['data']
    for i in range(4096):
        word = data[i // per_long] & 0xFFFFFFFFFFFFFFFF
        yield i, palette[(word >> ((i % per_long) * bits)) & mask]


# ---- Extraction ----------------------------------------------------------------------------------

# Block state properties that describe transient or container-derived state; normalized so the fixture
# doesn't depend on what was happening in the world at the moment it was copied.
NORMALIZED_PROPERTIES = {
    'minecraft:lectern': {'has_book': 'false', 'powered': 'false'},
    'bed': {'occupied': 'false'},
}


def state_string(state):
    name = state['Name']
    props = dict(state.get('Properties', {}))
    for key, overrides in NORMALIZED_PROPERTIES.items():
        if name == key or (key == 'bed' and name.endswith('_bed')):
            props.update({k: v for k, v in overrides.items() if k in props})
    if not props:
        return name
    return name + '[' + ','.join(f'{k}={v}' for k, v in sorted(props.items())) + ']'


def dimension_dir(world, dimension):
    namespace, path = dimension.split(':')
    modern = world / 'dimensions' / namespace / path  # Minecraft 26.1+
    if modern.is_dir():
        return modern
    legacy = {'minecraft:overworld': world, 'minecraft:the_nether': world / 'DIM-1', 'minecraft:the_end': world / 'DIM1'}
    return legacy[dimension]


def extract(world, dimension, box):
    x0, y0, z0, x1, y1, z1 = box
    root = dimension_dir(world, dimension)
    inside = lambda x, y, z: x0 <= x <= x1 and y0 <= y <= y1 and z0 <= z <= z1

    blocks = {}
    containers = []
    for region in sorted((root / 'region').glob('r.*.mca')):
        for chunk_x, chunk_z, chunk in region_chunks(region):
            if chunk_x * 16 > x1 or chunk_x * 16 + 15 < x0 or chunk_z * 16 > z1 or chunk_z * 16 + 15 < z0:
                continue
            for section in chunk.get('sections', []):
                section_y = section['Y']
                if section_y * 16 > y1 or section_y * 16 + 15 < y0:
                    continue
                for index, state in section_states(section):
                    x = chunk_x * 16 + index % 16
                    z = chunk_z * 16 + (index // 16) % 16
                    y = section_y * 16 + index // 256
                    if inside(x, y, z) and state['Name'] not in ('minecraft:air', 'minecraft:cave_air', 'minecraft:void_air'):
                        blocks[(x, y, z)] = state_string(state)
            for entity in chunk.get('block_entities', []):
                x, y, z = entity['x'], entity['y'], entity['z']
                if not inside(x, y, z):
                    continue
                block_id = entity['id']
                if block_id.endswith('copper_chest') or ('copper_chest' in blocks.get((x, y, z), '')):
                    containers.append({'pos': [x - x0, y - y0, z - z0], 'role': 'input', 'marker': None})
                elif block_id in ('minecraft:chest', 'minecraft:trapped_chest'):
                    counts = collections.Counter()
                    for item in entity.get('Items', []):
                        counts[item['id']] += item.get('count', item.get('Count', 1))
                    marker = counts.most_common(1)[0][0] if counts else None
                    containers.append({'pos': [x - x0, y - y0, z - z0], 'role': 'output', 'marker': marker})

    golems = []
    villagers = []
    for region in sorted((root / 'entities').glob('r.*.mca')):
        for _, _, chunk in region_chunks(region):
            for entity in chunk.get('Entities', []):
                x, y, z = entity['Pos']
                if not inside(x // 1, y // 1, z // 1):
                    continue
                relative = [round(x - x0, 4), round(y - y0, 4), round(z - z0, 4)]
                if entity['id'] == 'minecraft:copper_golem':
                    golems.append(relative)
                elif entity['id'] == 'minecraft:villager':
                    data = entity.get('VillagerData', {})
                    villagers.append({
                        'pos': relative,
                        'profession': data.get('profession', 'minecraft:none'),
                        'type': data.get('type', 'minecraft:plains'),
                        'level': data.get('level', 1),
                        'baby': entity.get('Age', 0) < 0,
                    })

    palette = sorted(set(blocks.values()))
    index = {state: i for i, state in enumerate(palette)}
    block_list = sorted([x - x0, y - y0, z - z0, index[state]] for (x, y, z), state in blocks.items())
    containers.sort(key=lambda c: c['pos'])
    golems.sort()
    villagers.sort(key=lambda v: v['pos'])
    return palette, block_list, containers, golems, villagers


def world_version(world):
    try:
        data = read_nbt(gzip.decompress((world / 'level.dat').read_bytes()))['Data']
        return data.get('Version', {}).get('Name', 'unknown'), data.get('DataVersion')
    except (OSError, KeyError):
        return 'unknown', None


def write_fixture(path, meta, palette, blocks, containers, golems, villagers):
    """Writes JSON with one block/entity per line so re-extractions produce readable diffs."""
    dumps = lambda value: json.dumps(value, separators=(',', ':'))
    lines = ['{', f'  "meta": {json.dumps(meta)},', f'  "size": {dumps(meta["size"])},', '  "palette": [']
    lines += [f'    {json.dumps(state)}' + (',' if i < len(palette) - 1 else '') for i, state in enumerate(palette)]
    lines += ['  ],', '  "blocks": [']
    lines += [f'    {dumps(b)}' + (',' if i < len(blocks) - 1 else '') for i, b in enumerate(blocks)]
    lines += ['  ],', '  "containers": [']
    lines += [f'    {dumps(c)}' + (',' if i < len(containers) - 1 else '') for i, c in enumerate(containers)]
    lines += ['  ],', '  "golems": [']
    lines += [f'    {dumps(g)}' + (',' if i < len(golems) - 1 else '') for i, g in enumerate(golems)]
    lines += ['  ],', '  "villagers": [']
    lines += [f'    {dumps(v)}' + (',' if i < len(villagers) - 1 else '') for i, v in enumerate(villagers)]
    lines += ['  ]', '}', '']
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('\n'.join(lines), encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('--world', required=True, type=Path, help='world folder (the one containing level.dat)')
    parser.add_argument('--dimension', default='minecraft:overworld')
    parser.add_argument('--box', required=True, help='x1,y1,z1,x2,y2,z2 (inclusive corners)')
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()

    corners = [int(v) for v in args.box.split(',')]
    box = (min(corners[0], corners[3]), min(corners[1], corners[4]), min(corners[2], corners[5]),
           max(corners[0], corners[3]), max(corners[1], corners[4]), max(corners[2], corners[5]))
    palette, blocks, containers, golems, villagers = extract(args.world, args.dimension, box)
    version_name, data_version = world_version(args.world)
    meta = {
        'description': 'Copper golem sorter with a villager floor above it, copied from a test world for the workload benchmark.',
        'sourceMinecraftVersion': version_name,
        'sourceDataVersion': data_version,
        'size': [box[3] - box[0] + 1, box[4] - box[1] + 1, box[5] - box[2] + 1],
        'extractedWith': 'tools/extract_farm.py',
    }
    write_fixture(args.out, meta, palette, blocks, containers, golems, villagers)

    roles = collections.Counter(c['role'] for c in containers)
    markers = sum(1 for c in containers if c['marker'])
    print(f'size {meta["size"]}, {len(blocks)} blocks ({len(palette)} states), '
          f'{roles["input"]} input / {roles["output"]} output containers ({markers} with markers), '
          f'{len(golems)} golems, {len(villagers)} villagers -> {args.out}')


if __name__ == '__main__':
    main()
