#!/usr/bin/env python3
"""Generate post office NBT structures for each village biome."""

import gzip, struct, io


# ── NBT Writer ────────────────────────────────────────────────

class NBTWriter:
    def __init__(self):
        self.buf = io.BytesIO()

    def write_byte(self, v):  self.buf.write(struct.pack('b', v))
    def write_ubyte(self, v): self.buf.write(struct.pack('B', v))
    def write_short(self, v): self.buf.write(struct.pack('>h', v))
    def write_int(self, v):   self.buf.write(struct.pack('>i', v))
    def write_long(self, v):  self.buf.write(struct.pack('>q', v))
    def write_float(self, v): self.buf.write(struct.pack('>f', v))
    def write_double(self, v):self.buf.write(struct.pack('>d', v))

    def write_string(self, s):
        encoded = s.encode('utf-8')
        self.write_short(len(encoded))
        self.buf.write(encoded)

    def write_named_tag(self, tag_type, name, value):
        self.write_byte(tag_type)
        self.write_string(name)
        self._write_payload(tag_type, value)

    def _write_payload(self, tag_type, value):
        if tag_type == 1:   self.write_byte(value)
        elif tag_type == 2: self.write_short(value)
        elif tag_type == 3: self.write_int(value)
        elif tag_type == 4: self.write_long(value)
        elif tag_type == 5: self.write_float(value)
        elif tag_type == 6: self.write_double(value)
        elif tag_type == 7:
            self.write_int(len(value))
            self.buf.write(bytes(value))
        elif tag_type == 8:
            self.write_string(value)
        elif tag_type == 9:  # TAG_List
            list_type, items = value
            self.write_byte(list_type)
            self.write_int(len(items))
            for item in items:
                self._write_payload(list_type, item)
        elif tag_type == 10:  # TAG_Compound — value is list of (tag_type, name, value)
            for child in value:
                self.write_named_tag(*child)
            self.write_byte(0)  # TAG_End
        elif tag_type == 11:
            self.write_int(len(value))
            for v in value: self.write_int(v)
        elif tag_type == 12:
            self.write_int(len(value))
            for v in value: self.write_long(v)

    def get_bytes(self):
        return self.buf.getvalue()


# ── Structure helpers ─────────────────────────────────────────

def palette_entry(name, **props):
    """Build a palette compound: [{Name: str}, {Properties: {k: str}}]"""
    children = [(8, 'Name', name)]
    if props:
        prop_tags = [(8, k, str(v)) for k, v in props.items()]
        children.append((10, 'Properties', prop_tags))
    return children


def block(x, y, z, state, nbt_children=None):
    """Build a blocks list entry."""
    children = [
        (9, 'pos', (3, [x, y, z])),
        (3, 'state', state),
    ]
    if nbt_children:
        children.append((10, 'nbt', nbt_children))
    return children


def entrance_jigsaw_block(x, y, z, state, pool, final_state):
    """Street-connection jigsaw, vanilla houses-pool convention.

    Verified against the 26.3-snapshot-8 vanilla village templates: street
    pieces select houses via jigsaws whose target is
    minecraft:building_entrance, matched against the house jigsaw's NAME.
    So the house jigsaw must be named minecraft:building_entrance, with
    joint aligned, oriented out the door face, placed in the foundation at
    the doorway, and its pool pointing back at the biome's streets pool.
    """
    nbt = [
        (8, 'id', 'minecraft:jigsaw'),
        (8, 'name', 'minecraft:building_entrance'),
        (8, 'target', 'minecraft:building_entrance'),
        (8, 'pool', pool),
        (8, 'joint', 'aligned'),
        (8, 'final_state', final_state),
    ]
    return block(x, y, z, state, nbt)


def write_structure(path, size, palette, blocks):
    """Write a complete structure NBT file."""
    # palette entries and blocks are already lists of (tag_type, name, value) tuples
    # TAG_List of TAG_Compound: list_type=10, items are the compound children lists
    root = [
        (3, 'DataVersion', 4325),
        (9, 'size', (3, list(size))),
        (9, 'palette', (10, palette)),
        (9, 'blocks', (10, blocks)),
        (9, 'entities', (10, [])),
    ]

    w = NBTWriter()
    w.write_named_tag(10, '', root)
    raw = w.get_bytes()

    with gzip.open(path, 'wb') as f:
        f.write(raw)
    print(f"  {path}: {len(raw)} bytes ({len(palette)} palette, {len(blocks)} blocks)")


# ── Grid builder ──────────────────────────────────────────────

class StructureBuilder:
    """Accumulate blocks on a grid, resolve palette indices automatically."""

    def __init__(self):
        self.palette = []       # list of (name, props_dict)
        self.palette_idx = {}   # (name, frozenset(props)) -> idx
        self.blocks = []        # list of block() results
        self.max = [0, 0, 0]

    def _get_state(self, name, props=None):
        key = (name, frozenset((props or {}).items()))
        if key not in self.palette_idx:
            idx = len(self.palette)
            self.palette_idx[key] = idx
            self.palette.append(palette_entry(name, **(props or {})))
        return self.palette_idx[key]

    def put(self, x, y, z, name, props=None):
        state = self._get_state(name, props)
        self.blocks.append(block(x, y, z, state))
        self._grow(x, y, z)

    def put_entrance_jigsaw(self, x, y, z, biome, final_state):
        """Street-connection jigsaw at the doorway. Faces north (out of the
        z=0 face, which is where every post office's door is)."""
        state = self._get_state('minecraft:jigsaw', {'orientation': 'north_up'})
        pool = 'minecraft:village/' + biome + '/streets'
        self.blocks.append(entrance_jigsaw_block(x, y, z, state, pool, final_state))
        self._grow(x, y, z)

    def _grow(self, x, y, z):
        self.max[0] = max(self.max[0], x + 1)
        self.max[1] = max(self.max[1], y + 1)
        self.max[2] = max(self.max[2], z + 1)

    def fill(self, x1, y1, z1, x2, y2, z2, name, props=None):
        for x in range(x1, x2 + 1):
            for y in range(y1, y2 + 1):
                for z in range(z1, z2 + 1):
                    self.put(x, y, z, name, props)

    def save(self, path):
        size = tuple(self.max)
        write_structure(path, size, self.palette, self.blocks)


# ── Shared interior layout ────────────────────────────────────
# All post offices share: blue carpet runner, public mailbox counter,
# chiseled bookshelves (mail sorting), chest, lantern, flower pot.
# The blue concrete block goes above the door as a postal sign.

def add_interior(b, W, D, carpet_z_start, carpet_z_end, mailbox_z, facing='south', lantern_y=4):
    """Add shared postal interior elements."""
    cx = W // 2  # center x

    # Blue carpet runner
    for z in range(carpet_z_start, carpet_z_end + 1):
        b.put(cx, 2, z, 'minecraft:blue_carpet')

    # Public mailbox (counter position)
    b.put(cx, 2, mailbox_z, 'village-mail:public_mailbox', {'facing': facing})

    # Chiseled bookshelves flanking mailbox (mail sorting shelves)
    shelf_props = {
        'facing': facing,
        'slot_0_occupied': 'false', 'slot_1_occupied': 'false',
        'slot_2_occupied': 'false', 'slot_3_occupied': 'false',
        'slot_4_occupied': 'false', 'slot_5_occupied': 'false',
    }
    b.put(cx - 2, 2, mailbox_z, 'minecraft:chiseled_bookshelf', shelf_props)
    b.put(cx + 2, 2, mailbox_z, 'minecraft:chiseled_bookshelf', shelf_props)
    b.put(cx - 2, 3, mailbox_z, 'minecraft:chiseled_bookshelf', shelf_props)
    b.put(cx + 2, 3, mailbox_z, 'minecraft:chiseled_bookshelf', shelf_props)

    # Chest
    b.put(cx + 3, 2, mailbox_z, 'minecraft:chest', {'facing': facing, 'type': 'single'})

    # Hanging lantern (needs solid block above at lantern_y+1)
    b.put(cx, lantern_y, D // 2, 'minecraft:lantern', {'hanging': 'true'})

    # Blue concrete postal sign above door on south wall (visible from approach)
    b.put(cx, lantern_y, 0, 'minecraft:blue_concrete')


# ══════════════════════════════════════════════════════════════
# PLAINS POST OFFICE — 9w x 7h x 9d
# Cobblestone foundation, oak planks + stripped oak log frame,
# oak stairs gable roof, glass panes, oak door.
# ══════════════════════════════════════════════════════════════

def build_plains():
    b = StructureBuilder()
    W, D = 9, 9  # width (x), depth (z)

    # y=0: cobblestone foundation pad (entrance jigsaw replaces the doorway block)
    for x in range(W):
        for z in range(D):
            if (x, z) != (W//2, 0):
                b.put(x, 0, z, 'minecraft:cobblestone')

    # y=1: floor — cobblestone border, oak planks inside
    for x in range(W):
        for z in range(D):
            if x == 0 or x == W-1 or z == 0 or z == D-1:
                b.put(x, 1, z, 'minecraft:cobblestone')
            else:
                b.put(x, 1, z, 'minecraft:oak_planks')

    # y=2..3: walls
    for y in [2, 3]:
        for x in range(W):
            for z in range(D):
                is_corner = (x in (0, W-1)) and (z in (0, D-1))
                is_wall = x in (0, W-1) or z in (0, D-1)

                if is_corner:
                    b.put(x, y, z, 'minecraft:stripped_oak_log', {'axis': 'y'})
                elif z == 0:  # south wall — door
                    if x == W//2 and y == 2:
                        b.put(x, y, z, 'minecraft:oak_door', {'facing': 'south', 'half': 'lower', 'hinge': 'left', 'open': 'false'})
                    elif x == W//2 and y == 3:
                        b.put(x, y, z, 'minecraft:oak_door', {'facing': 'south', 'half': 'upper', 'hinge': 'left', 'open': 'false'})
                    elif y in (2, 3) and x in (2, 6):
                        b.put(x, y, z, 'minecraft:glass_pane', {'north': 'false', 'south': 'false', 'east': 'true', 'west': 'true'})
                    elif y == 3 and x == W//2 - 1:
                        b.put(x, y, z, 'minecraft:cobblestone')  # beside door
                    elif y == 3 and x == W//2 + 1:
                        b.put(x, y, z, 'minecraft:cobblestone')
                    else:
                        b.put(x, y, z, 'minecraft:oak_planks')
                elif z == D-1:  # north wall
                    if y == 2 and x in (3, 5):
                        b.put(x, y, z, 'minecraft:glass_pane', {'north': 'false', 'south': 'false', 'east': 'true', 'west': 'true'})
                    elif y == 3 and x in (3, 5):
                        b.put(x, y, z, 'minecraft:glass_pane', {'north': 'false', 'south': 'false', 'east': 'true', 'west': 'true'})
                    else:
                        b.put(x, y, z, 'minecraft:oak_planks')
                elif x == 0 or x == W-1:  # east/west walls
                    if y in (2, 3) and z in (3, 5):
                        b.put(x, y, z, 'minecraft:glass_pane', {'north': 'true', 'south': 'true', 'east': 'false', 'west': 'false'})
                    else:
                        b.put(x, y, z, 'minecraft:oak_planks')

    # Interior
    add_interior(b, W, D, carpet_z_start=2, carpet_z_end=6, mailbox_z=7)
    b.put(1, 2, 7, 'minecraft:potted_cornflower')

    # y=4: log beam ring at top of walls
    for x in range(W):
        for z in [0, D-1]:
            if not ((x in (0, W-1)) and (z in (0, D-1))):
                b.put(x, 4, z, 'minecraft:oak_log', {'axis': 'x'})
    for z in range(1, D-1):
        b.put(0, 4, z, 'minecraft:oak_log', {'axis': 'z'})
        b.put(W-1, 4, z, 'minecraft:oak_log', {'axis': 'z'})
    # Corners
    for x in (0, W-1):
        for z in (0, D-1):
            b.put(x, 4, z, 'minecraft:stripped_oak_log', {'axis': 'y'})

    # y=5: Roof — stairs sloping inward on all 4 sides, planks in middle
    for x in range(W):
        for z in range(D):
            if x == 0:
                b.put(x, 5, z, 'minecraft:oak_stairs', {'facing': 'east', 'half': 'bottom', 'shape': 'straight'})
            elif x == W-1:
                b.put(x, 5, z, 'minecraft:oak_stairs', {'facing': 'west', 'half': 'bottom', 'shape': 'straight'})
            elif z == 0:
                b.put(x, 5, z, 'minecraft:oak_stairs', {'facing': 'south', 'half': 'bottom', 'shape': 'straight'})
            elif z == D-1:
                b.put(x, 5, z, 'minecraft:oak_stairs', {'facing': 'north', 'half': 'bottom', 'shape': 'straight'})
            else:
                b.put(x, 5, z, 'minecraft:oak_planks')

    # y=6: Roof cap — slabs
    for x in range(1, W-1):
        for z in range(1, D-1):
            b.put(x, 6, z, 'minecraft:oak_slab', {'type': 'top'})

    # Street-connection jigsaw in the foundation at the doorway
    b.put_entrance_jigsaw(W//2, 0, 0, 'plains', 'minecraft:cobblestone')

    b.save('src/main/resources/data/village-mail/structure/post_office_plains.nbt')


# ══════════════════════════════════════════════════════════════
# DESERT POST OFFICE — 9w x 6h x 9d
# Sandstone + smooth sandstone walls, cut sandstone banding,
# FLAT roof (smooth_sandstone_slab), jungle door, no pitched roof.
# ══════════════════════════════════════════════════════════════

def build_desert():
    b = StructureBuilder()
    W, D = 9, 9

    # y=0: sandstone foundation (entrance jigsaw replaces the doorway block)
    for x in range(W):
        for z in range(D):
            if (x, z) != (W//2, 0):
                b.put(x, 0, z, 'minecraft:sandstone')

    # y=1: floor — cut sandstone border, smooth sandstone interior
    for x in range(W):
        for z in range(D):
            if x == 0 or x == W-1 or z == 0 or z == D-1:
                b.put(x, 1, z, 'minecraft:cut_sandstone')
            else:
                b.put(x, 1, z, 'minecraft:smooth_sandstone')

    # y=2..3: walls — sandstone main, cut sandstone band at y=3
    for y in [2, 3]:
        wall_block = 'minecraft:sandstone' if y == 2 else 'minecraft:cut_sandstone'
        for x in range(W):
            for z in range(D):
                is_wall = x in (0, W-1) or z in (0, D-1)
                if not is_wall:
                    continue

                if z == 0 and x == W//2:
                    if y == 2:
                        b.put(x, y, z, 'minecraft:jungle_door', {'facing': 'south', 'half': 'lower', 'hinge': 'left', 'open': 'false'})
                    else:
                        b.put(x, y, z, 'minecraft:jungle_door', {'facing': 'south', 'half': 'upper', 'hinge': 'left', 'open': 'false'})
                elif y == 3 and z == 0 and x in (2, 6):
                    # Desert: jungle_trapdoor as window shutters (open-air, hinge on wall, swing outward)
                    b.put(x, y, z, 'minecraft:jungle_trapdoor', {'facing': 'north', 'half': 'top', 'open': 'true'})
                elif y == 2 and z == D-1 and x in (3, 5):
                    b.put(x, y, z, 'minecraft:jungle_trapdoor', {'facing': 'south', 'half': 'top', 'open': 'true'})
                elif y == 2 and (x == 0 or x == W-1) and z in (3, 5):
                    facing = 'east' if x == 0 else 'west'
                    b.put(x, y, z, 'minecraft:jungle_trapdoor', {'facing': facing, 'half': 'top', 'open': 'true'})
                else:
                    b.put(x, y, z, wall_block)

    # Interior (desert ceiling is at y=4, so lantern hangs from y=3)
    add_interior(b, W, D, carpet_z_start=2, carpet_z_end=6, mailbox_z=7, lantern_y=3)
    b.put(1, 2, 7, 'minecraft:potted_dead_bush')
    b.put(7, 2, 7, 'minecraft:potted_cactus')

    # y=4: Flat roof — smooth sandstone slab across entire top, with terracotta trim
    for x in range(W):
        for z in range(D):
            if x in (0, W-1) or z in (0, D-1):
                b.put(x, 4, z, 'minecraft:terracotta')
            else:
                b.put(x, 4, z, 'minecraft:smooth_sandstone_slab', {'type': 'top'})

    # y=5: Parapet — cut sandstone border on top
    for x in range(W):
        for z in [0, D-1]:
            b.put(x, 5, z, 'minecraft:cut_sandstone')
    for z in range(1, D-1):
        b.put(0, 5, z, 'minecraft:cut_sandstone')
        b.put(W-1, 5, z, 'minecraft:cut_sandstone')

    b.put_entrance_jigsaw(W//2, 0, 0, 'desert', 'minecraft:sandstone')
    b.save('src/main/resources/data/village-mail/structure/post_office_desert.nbt')


# ══════════════════════════════════════════════════════════════
# SAVANNA POST OFFICE — 9w x 7h x 9d
# Acacia planks + log frame, orange terracotta accents,
# acacia stairs roof, acacia door, orange stained glass.
# ══════════════════════════════════════════════════════════════

def build_savanna():
    b = StructureBuilder()
    W, D = 9, 9

    # y=0: cobblestone foundation (savanna uses cobblestone base like plains;
    # entrance jigsaw replaces the doorway block)
    for x in range(W):
        for z in range(D):
            if (x, z) != (W//2, 0):
                b.put(x, 0, z, 'minecraft:cobblestone')

    # y=1: acacia plank floor
    for x in range(W):
        for z in range(D):
            if x == 0 or x == W-1 or z == 0 or z == D-1:
                b.put(x, 1, z, 'minecraft:orange_terracotta')
            else:
                b.put(x, 1, z, 'minecraft:acacia_planks')

    # y=2..3: walls — acacia log corners, acacia planks walls
    for y in [2, 3]:
        for x in range(W):
            for z in range(D):
                is_corner = (x in (0, W-1)) and (z in (0, D-1))
                is_wall = x in (0, W-1) or z in (0, D-1)

                if is_corner:
                    b.put(x, y, z, 'minecraft:acacia_log', {'axis': 'y'})
                elif z == 0 and x == W//2:
                    half = 'lower' if y == 2 else 'upper'
                    b.put(x, y, z, 'minecraft:acacia_door', {'facing': 'south', 'half': half, 'hinge': 'left', 'open': 'false'})
                elif is_wall:
                    # Windows — orange stained glass (signature savanna)
                    if y in (2, 3) and z == 0 and x in (2, 6):
                        b.put(x, y, z, 'minecraft:orange_stained_glass_pane', {'north': 'false', 'south': 'false', 'east': 'true', 'west': 'true'})
                    elif y in (2, 3) and z == D-1 and x in (3, 5):
                        b.put(x, y, z, 'minecraft:orange_stained_glass_pane', {'north': 'false', 'south': 'false', 'east': 'true', 'west': 'true'})
                    elif y in (2, 3) and (x == 0 or x == W-1) and z in (3, 5):
                        b.put(x, y, z, 'minecraft:orange_stained_glass_pane', {'north': 'true', 'south': 'true', 'east': 'false', 'west': 'false'})
                    elif y == 2 and (x in (0, W-1) or z in (0, D-1)):
                        # Orange terracotta accent band at lower wall
                        b.put(x, y, z, 'minecraft:orange_terracotta')
                    else:
                        b.put(x, y, z, 'minecraft:acacia_planks')

    add_interior(b, W, D, carpet_z_start=2, carpet_z_end=6, mailbox_z=7)

    # y=4: acacia log beam ring
    for x in range(W):
        for z in [0, D-1]:
            if not ((x in (0, W-1)) and (z in (0, D-1))):
                b.put(x, 4, z, 'minecraft:acacia_log', {'axis': 'x'})
    for z in range(1, D-1):
        b.put(0, 4, z, 'minecraft:acacia_log', {'axis': 'z'})
        b.put(W-1, 4, z, 'minecraft:acacia_log', {'axis': 'z'})
    for x in (0, W-1):
        for z in (0, D-1):
            b.put(x, 4, z, 'minecraft:acacia_log', {'axis': 'y'})

    # y=5: acacia stairs roof
    for x in range(W):
        for z in range(D):
            if x == 0:
                b.put(x, 5, z, 'minecraft:acacia_stairs', {'facing': 'east', 'half': 'bottom', 'shape': 'straight'})
            elif x == W-1:
                b.put(x, 5, z, 'minecraft:acacia_stairs', {'facing': 'west', 'half': 'bottom', 'shape': 'straight'})
            elif z == 0:
                b.put(x, 5, z, 'minecraft:acacia_stairs', {'facing': 'south', 'half': 'bottom', 'shape': 'straight'})
            elif z == D-1:
                b.put(x, 5, z, 'minecraft:acacia_stairs', {'facing': 'north', 'half': 'bottom', 'shape': 'straight'})
            else:
                b.put(x, 5, z, 'minecraft:acacia_planks')

    # y=6: roof cap
    for x in range(1, W-1):
        for z in range(1, D-1):
            b.put(x, 6, z, 'minecraft:acacia_slab', {'type': 'top'})

    b.put_entrance_jigsaw(W//2, 0, 0, 'savanna', 'minecraft:cobblestone')
    b.save('src/main/resources/data/village-mail/structure/post_office_savanna.nbt')


# ══════════════════════════════════════════════════════════════
# SNOWY POST OFFICE — 9w x 8h x 9d
# Spruce planks + stripped spruce log frame, packed ice + snow accents,
# spruce stairs steep roof, snow layers on top, lanterns, glass panes.
# ══════════════════════════════════════════════════════════════

def build_snowy():
    b = StructureBuilder()
    W, D = 9, 9

    # y=0: cobblestone foundation (under the snow;
    # entrance jigsaw replaces the doorway block)
    for x in range(W):
        for z in range(D):
            if (x, z) != (W//2, 0):
                b.put(x, 0, z, 'minecraft:cobblestone')

    # y=1: floor — packed ice border, spruce planks inside
    for x in range(W):
        for z in range(D):
            if x == 0 or x == W-1 or z == 0 or z == D-1:
                b.put(x, 1, z, 'minecraft:packed_ice')
            else:
                b.put(x, 1, z, 'minecraft:spruce_planks')

    # y=2..3: walls
    for y in [2, 3]:
        for x in range(W):
            for z in range(D):
                is_corner = (x in (0, W-1)) and (z in (0, D-1))
                is_wall = x in (0, W-1) or z in (0, D-1)

                if is_corner:
                    b.put(x, y, z, 'minecraft:stripped_spruce_log', {'axis': 'y'})
                elif z == 0 and x == W//2:
                    half = 'lower' if y == 2 else 'upper'
                    b.put(x, y, z, 'minecraft:spruce_door', {'facing': 'south', 'half': half, 'hinge': 'left', 'open': 'false'})
                elif is_wall:
                    if y in (2, 3) and z == D-1 and x in (3, 5):
                        b.put(x, y, z, 'minecraft:glass_pane', {'north': 'false', 'south': 'false', 'east': 'true', 'west': 'true'})
                    elif y in (2, 3) and z == 0 and x in (2, 6):
                        b.put(x, y, z, 'minecraft:glass_pane', {'north': 'false', 'south': 'false', 'east': 'true', 'west': 'true'})
                    elif y in (2, 3) and (x == 0 or x == W-1) and z in (3, 5):
                        b.put(x, y, z, 'minecraft:glass_pane', {'north': 'true', 'south': 'true', 'east': 'false', 'west': 'false'})
                    elif y == 2:
                        b.put(x, y, z, 'minecraft:packed_ice')  # lower walls are ice
                    else:
                        b.put(x, y, z, 'minecraft:spruce_planks')

    add_interior(b, W, D, carpet_z_start=2, carpet_z_end=6, mailbox_z=7)
    # White carpet instead of cornflower for snowy
    b.put(1, 2, 1, 'minecraft:white_carpet')
    b.put(7, 2, 1, 'minecraft:white_carpet')

    # y=4: stripped spruce log beam ring
    for x in range(W):
        for z in [0, D-1]:
            b.put(x, 4, z, 'minecraft:spruce_log', {'axis': 'x'})
    for z in range(1, D-1):
        b.put(0, 4, z, 'minecraft:spruce_log', {'axis': 'z'})
        b.put(W-1, 4, z, 'minecraft:spruce_log', {'axis': 'z'})
    for x in (0, W-1):
        for z in (0, D-1):
            b.put(x, 4, z, 'minecraft:stripped_spruce_log', {'axis': 'y'})

    # y=5: spruce stairs roof
    for x in range(W):
        for z in range(D):
            if x == 0:
                b.put(x, 5, z, 'minecraft:spruce_stairs', {'facing': 'east', 'half': 'bottom', 'shape': 'straight'})
            elif x == W-1:
                b.put(x, 5, z, 'minecraft:spruce_stairs', {'facing': 'west', 'half': 'bottom', 'shape': 'straight'})
            elif z == 0:
                b.put(x, 5, z, 'minecraft:spruce_stairs', {'facing': 'south', 'half': 'bottom', 'shape': 'straight'})
            elif z == D-1:
                b.put(x, 5, z, 'minecraft:spruce_stairs', {'facing': 'north', 'half': 'bottom', 'shape': 'straight'})
            else:
                b.put(x, 5, z, 'minecraft:spruce_planks')

    # y=6: roof cap — spruce slabs
    for x in range(1, W-1):
        for z in range(1, D-1):
            b.put(x, 6, z, 'minecraft:spruce_slab', {'type': 'top'})

    # y=7: snow layers on roof
    for x in range(1, W-1):
        for z in range(1, D-1):
            b.put(x, 7, z, 'minecraft:snow', {'layers': '3'})

    b.put_entrance_jigsaw(W//2, 0, 0, 'snowy', 'minecraft:cobblestone')
    b.save('src/main/resources/data/village-mail/structure/post_office_snowy.nbt')


# ══════════════════════════════════════════════════════════════
# TAIGA POST OFFICE — 9w x 8h x 9d
# Spruce planks walls, cobblestone + mossy cobblestone foundation,
# spruce log frame, spruce stairs roof, spruce trapdoor shutters,
# campfire outside, glass panes.
# ══════════════════════════════════════════════════════════════

def build_taiga():
    b = StructureBuilder()
    W, D = 9, 9

    # y=0: cobblestone + mossy cobblestone foundation (overgrown feel;
    # entrance jigsaw replaces the doorway block, which the pattern makes plain cobble)
    for x in range(W):
        for z in range(D):
            if (x, z) == (W//2, 0):
                continue
            if (x + z) % 3 == 0:
                b.put(x, 0, z, 'minecraft:mossy_cobblestone')
            else:
                b.put(x, 0, z, 'minecraft:cobblestone')

    # y=1: floor — cobblestone border (some mossy), spruce planks inside
    for x in range(W):
        for z in range(D):
            if x == 0 or x == W-1 or z == 0 or z == D-1:
                if (x + z) % 4 == 0:
                    b.put(x, 1, z, 'minecraft:mossy_cobblestone')
                else:
                    b.put(x, 1, z, 'minecraft:cobblestone')
            else:
                b.put(x, 1, z, 'minecraft:spruce_planks')

    # y=2..3: walls — spruce log corners, cobblestone lower, spruce planks upper
    for y in [2, 3]:
        for x in range(W):
            for z in range(D):
                is_corner = (x in (0, W-1)) and (z in (0, D-1))
                is_wall = x in (0, W-1) or z in (0, D-1)

                if is_corner:
                    b.put(x, y, z, 'minecraft:spruce_log', {'axis': 'y'})
                elif z == 0 and x == W//2:
                    half = 'lower' if y == 2 else 'upper'
                    b.put(x, y, z, 'minecraft:spruce_door', {'facing': 'south', 'half': half, 'hinge': 'left', 'open': 'false'})
                elif is_wall:
                    # Shutter positions on east/west walls (z=2,4,6 at y=3) — placed below
                    shutter_zs = {2, 4, 6}
                    if y == 3 and (x == 0 or x == W-1) and z in shutter_zs:
                        # Trapdoor shutters flanking windows (hinge toward nearest window)
                        if z == 2:
                            b.put(x, y, z, 'minecraft:spruce_trapdoor', {'facing': 'south', 'half': 'top', 'open': 'true'})
                        elif z == 4:
                            # Between the two windows — hinge toward z=3 window
                            b.put(x, y, z, 'minecraft:spruce_trapdoor', {'facing': 'south', 'half': 'top', 'open': 'true'})
                        elif z == 6:
                            b.put(x, y, z, 'minecraft:spruce_trapdoor', {'facing': 'north', 'half': 'top', 'open': 'true'})
                    # Windows
                    elif y in (2, 3) and z == 0 and x in (2, 6):
                        b.put(x, y, z, 'minecraft:glass_pane', {'north': 'false', 'south': 'false', 'east': 'true', 'west': 'true'})
                    elif y in (2, 3) and z == D-1 and x in (3, 5):
                        b.put(x, y, z, 'minecraft:glass_pane', {'north': 'false', 'south': 'false', 'east': 'true', 'west': 'true'})
                    elif y in (2, 3) and (x == 0 or x == W-1) and z in (3, 5):
                        b.put(x, y, z, 'minecraft:glass_pane', {'north': 'true', 'south': 'true', 'east': 'false', 'west': 'false'})
                    elif y == 2:
                        # Lower walls: cobblestone (taiga style)
                        b.put(x, y, z, 'minecraft:cobblestone')
                    else:
                        b.put(x, y, z, 'minecraft:spruce_planks')

    add_interior(b, W, D, carpet_z_start=2, carpet_z_end=6, mailbox_z=7)
    b.put(1, 2, 7, 'minecraft:potted_fern')

    # y=4: spruce log beam ring with vertical corners
    for x in range(W):
        for z in [0, D-1]:
            if not ((x in (0, W-1)) and (z in (0, D-1))):
                b.put(x, 4, z, 'minecraft:spruce_log', {'axis': 'x'})
    for z in range(1, D-1):
        b.put(0, 4, z, 'minecraft:spruce_log', {'axis': 'z'})
        b.put(W-1, 4, z, 'minecraft:spruce_log', {'axis': 'z'})
    for x in (0, W-1):
        for z in (0, D-1):
            b.put(x, 4, z, 'minecraft:spruce_log', {'axis': 'y'})

    # y=5: spruce stairs roof
    for x in range(W):
        for z in range(D):
            if x == 0:
                b.put(x, 5, z, 'minecraft:spruce_stairs', {'facing': 'east', 'half': 'bottom', 'shape': 'straight'})
            elif x == W-1:
                b.put(x, 5, z, 'minecraft:spruce_stairs', {'facing': 'west', 'half': 'bottom', 'shape': 'straight'})
            elif z == 0:
                b.put(x, 5, z, 'minecraft:spruce_stairs', {'facing': 'south', 'half': 'bottom', 'shape': 'straight'})
            elif z == D-1:
                b.put(x, 5, z, 'minecraft:spruce_stairs', {'facing': 'north', 'half': 'bottom', 'shape': 'straight'})
            else:
                b.put(x, 5, z, 'minecraft:spruce_planks')

    # y=6: roof cap
    for x in range(1, W-1):
        for z in range(1, D-1):
            b.put(x, 6, z, 'minecraft:spruce_slab', {'type': 'top'})

    b.put_entrance_jigsaw(W//2, 0, 0, 'taiga', 'minecraft:cobblestone')
    b.save('src/main/resources/data/village-mail/structure/post_office_taiga.nbt')


# ══════════════════════════════════════════════════════════════

if __name__ == '__main__':
    print("Generating post office structures...")
    build_plains()
    build_desert()
    build_savanna()
    build_snowy()
    build_taiga()
    print("Done.")
