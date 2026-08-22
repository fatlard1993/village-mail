#!/usr/bin/env python3
"""Generate the post office template for each village biome.

Measured against the vanilla village houses of the target version rather than
invented. What that reference settles, and what earlier versions of these
templates got wrong:

  Scale       vanilla profession houses are a 7x5 building inside a ~10x8x7 box
              (plains_cartographer_1 is exactly that). These used to be a solid
              9x9 with a 7x7 room, which is larger than anything in a vanilla
              village.

  Air         vanilla writes every position in the box, air included, and the
              houses pools place pieces as legacy elements so that air is placed
              and clears the ground it lands in. Omitting it left terrain
              standing inside the room.

  Grade       the floor is flush with the street: floor at y=0, door at y=1, and
              the street-connection jigsaw at y=1 in the air column outside the
              door, which is the row the street pieces put theirs in. A floor at
              y=1 stands the whole building on a plinth.

  Facing      a fitting against the back wall faces the room. Every counter block
              here is on the z=4 row with the wall behind it at z=5, so it faces
              north; they used to face south, into the wall.

Layout, shared by every biome (box 9 x H x 7):

    x: 0 . 1 2 3 4 5 6 7 . 8      walls x=1 and x=7, room x=2..6
    z: 0 apron, 1 front wall (door at x=4), 2..4 room, 5 back wall, 6 eave

Run from the repo root; writes straight into the resource directory.
"""

import gzip, struct, io


# -- NBT writer -----------------------------------------------------------
# Template format as the game itself writes it at this DataVersion: palette
# entries are {id, properties}, not the pre-1.21.6 {Name, Properties}.

DATA_VERSION = 5011


class NBTWriter:
	def __init__(self):
		self.buf = io.BytesIO()

	def write_byte(self, v):  self.buf.write(struct.pack('b', v))
	def write_short(self, v): self.buf.write(struct.pack('>h', v))
	def write_int(self, v):   self.buf.write(struct.pack('>i', v))

	def write_string(self, s):
		encoded = s.encode('utf-8')
		self.buf.write(struct.pack('>H', len(encoded)))
		self.buf.write(encoded)

	def write_named_tag(self, tag_type, name, value):
		self.write_byte(tag_type)
		self.write_string(name)
		self._write_payload(tag_type, value)

	def _write_payload(self, tag_type, value):
		if tag_type == 1:   self.write_byte(value)
		elif tag_type == 3: self.write_int(value)
		elif tag_type == 8: self.write_string(value)
		elif tag_type == 9:  # TAG_List: (element_type, items)
			list_type, items = value
			self.write_byte(list_type)
			self.write_int(len(items))
			for item in items:
				self._write_payload(list_type, item)
		elif tag_type == 10:  # TAG_Compound: list of (tag_type, name, value)
			for child in value:
				self.write_named_tag(*child)
			self.write_byte(0)
		else:
			raise ValueError('unsupported tag type %d' % tag_type)

	def get_bytes(self):
		return self.buf.getvalue()


def write_structure(path, size, palette, blocks):
	root = [
		(3, 'DataVersion', DATA_VERSION),
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
	filled = len(blocks)
	volume = size[0] * size[1] * size[2]
	print('  %-52s %2dx%dx%-2d  %4d/%4d blocks  %2d palette' % (
		path.rsplit('/', 1)[-1], size[0], size[1], size[2], filled, volume, len(palette)))


# -- Grid builder ---------------------------------------------------------

class Builder:
	"""Accumulate blocks on a fixed-size grid, resolving palette indices.

	One entry per position: a later put() replaces an earlier one, so decoration
	passes can be written over structural ones without leaving the template with
	duplicate positions (placement order would silently pick one of them).
	"""

	def __init__(self, size):
		self.size = size
		self.palette = []
		self.palette_idx = {}
		self.grid = {}

	def _state(self, name, props):
		key = (name, tuple(sorted((props or {}).items())))
		if key not in self.palette_idx:
			children = [(8, 'id', name)]
			if props:
				children.append((10, 'properties',
					[(8, k, str(v)) for k, v in sorted(props.items())]))
			self.palette_idx[key] = len(self.palette)
			self.palette.append(children)
		return self.palette_idx[key]

	def put(self, x, y, z, name, props=None, nbt=None):
		sx, sy, sz = self.size
		if not (0 <= x < sx and 0 <= y < sy and 0 <= z < sz):
			raise ValueError('%s at (%d,%d,%d) is outside the %dx%dx%d box'
				% (name, x, y, z, sx, sy, sz))
		self.grid[(x, y, z)] = (self._state(name, props), nbt)

	def fill(self, x1, y1, z1, x2, y2, z2, name, props=None):
		for x in range(x1, x2 + 1):
			for y in range(y1, y2 + 1):
				for z in range(z1, z2 + 1):
					self.put(x, y, z, name, props)

	def air_above_ground(self):
		"""Air into every unwritten position above the ground layer.

		Vanilla writes air explicitly so a piece carves out whatever terrain it
		lands in. y=0 is left alone where nothing was placed: that row is the
		ground itself, and air there would dig a moat around the building.
		"""
		sx, sy, sz = self.size
		for x in range(sx):
			for y in range(1, sy):
				for z in range(sz):
					if (x, y, z) not in self.grid:
						self.put(x, y, z, 'minecraft:air')

	def jigsaw(self, x, y, z, orientation, name, target, pool, final_state, joint):
		nbt = [
			(8, 'id', 'minecraft:jigsaw'),
			(8, 'name', name),
			(8, 'target', target),
			(8, 'pool', pool),
			(8, 'joint', joint),
			(8, 'final_state', final_state),
		]
		self.put(x, y, z, 'minecraft:jigsaw', {'orientation': orientation}, nbt)

	def save(self, path):
		self.air_above_ground()
		blocks = []
		for (x, y, z), (state, nbt) in sorted(self.grid.items()):
			children = [
				(9, 'pos', (3, [x, y, z])),
				(3, 'state', state),
			]
			if nbt:
				children.append((10, 'nbt', nbt))
			blocks.append(children)
		write_structure(path, self.size, self.palette, blocks)


# -- Shared plan ----------------------------------------------------------

BOX_W = 9        # x: 0..8
BOX_D = 7        # z: 0..6
WALL_X0, WALL_X1 = 1, 7
WALL_Z0, WALL_Z1 = 1, 5
ROOM_X0, ROOM_X1 = 2, 6
ROOM_Z0, ROOM_Z1 = 2, 4
DOOR_X = 4
COUNTER_Z = ROOM_Z1     # the row against the back wall
RIDGE_Z = 3             # gable ridge runs along x at this row

PANE_EW = {'east': 'true', 'west': 'true', 'north': 'false', 'south': 'false', 'waterlogged': 'false'}
PANE_NS = {'east': 'false', 'west': 'false', 'north': 'true', 'south': 'true', 'waterlogged': 'false'}

SHELF_SLOTS = {
	'slot_0_occupied': 'true', 'slot_1_occupied': 'false', 'slot_2_occupied': 'true',
	'slot_3_occupied': 'false', 'slot_4_occupied': 'true', 'slot_5_occupied': 'false',
}
SHELF_SLOTS_ALT = {
	'slot_0_occupied': 'false', 'slot_1_occupied': 'true', 'slot_2_occupied': 'false',
	'slot_3_occupied': 'true', 'slot_4_occupied': 'false', 'slot_5_occupied': 'true',
}

POST_OFFICE_LOOT = 'village-mail:chests/post_office'


def stair(mat, facing, half='bottom'):
	return (mat, {'facing': facing, 'half': half, 'shape': 'straight', 'waterlogged': 'false'})


def ground_and_floor(b, m):
	"""y=0: the pad the building stands on, flush with the street."""
	# Apron first, so the building's own footprint overwrites it.
	for x in range(BOX_W):
		for z in range(BOX_D):
			b.put(x, 0, z, *m['ground'])

	# Foundation under the walls, floor inside.
	for x in range(WALL_X0, WALL_X1 + 1):
		for z in range(WALL_Z0, WALL_Z1 + 1):
			inside = ROOM_X0 <= x <= ROOM_X1 and ROOM_Z0 <= z <= ROOM_Z1
			b.put(x, 0, z, m['floor'] if inside else m['base'])

	# Doorstep and the approach the street piece meets.
	for x in (DOOR_X - 1, DOOR_X, DOOR_X + 1):
		b.put(x, 0, 0, m['path'])


def walls(b, m):
	"""y=1..3. Base course, wall above it, corner and door posts full height."""
	for y in range(1, 4):
		for x in range(WALL_X0, WALL_X1 + 1):
			for z in range(WALL_Z0, WALL_Z1 + 1):
				if not (x in (WALL_X0, WALL_X1) or z in (WALL_Z0, WALL_Z1)):
					continue
				b.put(x, y, z, m['base'] if y == 1 else m['wall'])

	for x in (WALL_X0, WALL_X1):
		for z in (WALL_Z0, WALL_Z1):
			b.fill(x, 1, z, x, 3, z, *m['post'])
	door_props = {'half': 'lower', 'hinge': 'left', 'open': 'false', 'powered': 'false', 'facing': 'south'}
	b.put(DOOR_X, 1, WALL_Z0, m['door'], door_props)
	b.put(DOOR_X, 2, WALL_Z0, m['door'], dict(door_props, half='upper'))
	b.put(DOOR_X, 3, WALL_Z0, m['band'])

	for x in (DOOR_X - 1, DOOR_X + 1):
		b.put(x, 2, WALL_Z0, *m['window_ew'])
		b.put(x, 2, WALL_Z1, *m['window_ew'])
	for x in (WALL_X0, WALL_X1):
		b.put(x, 2, RIDGE_Z, *m['window_ns'])


def gable_roof(b, m):
	"""Ridge along x at z=RIDGE_Z, 45 degrees, one block of overhang all round.

	Course by course: the eave row sits at y=4 over the apron, each row inward
	steps up one, and the ridge row is capped with a slab. Under the two gable
	overhangs the slope is closed with upside-down stairs, which is how the
	vanilla roofs finish a rake.
	"""
	plate = m['plate']
	# Wall-top plate ring, and a tie beam across the room to hang a light from.
	for x in range(WALL_X0, WALL_X1 + 1):
		for z in (WALL_Z0, WALL_Z1):
			b.put(x, 4, z, plate[0], dict(plate[1], axis='x'))
	for z in range(WALL_Z0, WALL_Z1 + 1):
		for x in (WALL_X0, WALL_X1):
			b.put(x, 4, z, plate[0], dict(plate[1], axis='z'))
	for x in range(ROOM_X0, ROOM_X1 + 1):
		b.put(x, 4, RIDGE_Z, plate[0], dict(plate[1], axis='x'))

	stairs = m['stairs']
	for step, z in enumerate(range(0, RIDGE_Z)):       # z = 0, 1, 2 -> y = 4, 5, 6
		y = 4 + step
		front, back = z, BOX_D - 1 - z
		for x in range(BOX_W):
			b.put(x, y, front, *stair(stairs, 'south'))
			b.put(x, y, back, *stair(stairs, 'north'))
		# Rake soffit: the underside of the overhang beyond the gable walls.
		if z > 0:
			for x in (0, BOX_W - 1):
				b.put(x, y - 1, front, *stair(stairs, 'north', 'top'))
				b.put(x, y - 1, back, *stair(stairs, 'south', 'top'))

	# Ridge row and its cap.
	for x in range(BOX_W):
		b.put(x, 4 + RIDGE_Z - 1, RIDGE_Z, m['roof'])
		b.put(x, 4 + RIDGE_Z, RIDGE_Z, m['slab'], {'type': 'bottom', 'waterlogged': 'false'})

	# Gable triangles, filling between the wall top and the sloping roof.
	for x in (WALL_X0, WALL_X1):
		for z in range(ROOM_Z0, ROOM_Z1 + 1):
			b.put(x, 5, z, m['wall'])


def flat_roof(b, m):
	"""Desert: a solid deck and a parapet, the way vanilla desert houses finish."""
	for x in range(WALL_X0, WALL_X1 + 1):
		for z in range(WALL_Z0, WALL_Z1 + 1):
			b.put(x, 4, z, m['roof'])
	for x in range(WALL_X0, WALL_X1 + 1):
		for z in range(WALL_Z0, WALL_Z1 + 1):
			if x in (WALL_X0, WALL_X1) or z in (WALL_Z0, WALL_Z1):
				b.put(x, 5, z, m['parapet'][0], m['parapet'][1])


def interior(b, m):
	"""The counter along the back wall, facing the door."""
	back = {'facing': 'north'}

	b.put(ROOM_X0, 1, COUNTER_Z, 'minecraft:chest',
		dict(back, type='single', waterlogged='false'),
		[(8, 'id', 'minecraft:chest'), (8, 'LootTable', POST_OFFICE_LOOT)])
	b.put(DOOR_X - 1, 1, COUNTER_Z, 'minecraft:chiseled_bookshelf', dict(back, **SHELF_SLOTS))
	b.put(DOOR_X, 1, COUNTER_Z, 'village-mail:public_mailbox', back)
	b.put(DOOR_X + 1, 1, COUNTER_Z, 'minecraft:chiseled_bookshelf', dict(back, **SHELF_SLOTS_ALT))
	b.put(ROOM_X1, 1, COUNTER_Z, 'minecraft:bookshelf')

	# Sorting shelves at head height, postal colours behind the counter.
	b.put(DOOR_X - 1, 2, COUNTER_Z, 'minecraft:chiseled_bookshelf', dict(back, **SHELF_SLOTS_ALT))
	b.put(DOOR_X + 1, 2, COUNTER_Z, 'minecraft:chiseled_bookshelf', dict(back, **SHELF_SLOTS))
	b.put(DOOR_X, 2, COUNTER_Z, 'minecraft:blue_wall_banner', back)

	b.put(DOOR_X, 1, ROOM_Z0, m['carpet'])
	b.put(DOOR_X, 1, RIDGE_Z, m['carpet'])
	b.put(ROOM_X0, 1, ROOM_Z0, m['pot'])
	b.put(ROOM_X1, 1, ROOM_Z0, m['pot'])

	b.put(DOOR_X, 3, RIDGE_Z, 'minecraft:lantern', {'hanging': 'true', 'waterlogged': 'false'})

	# A resident to run the counter. Vanilla houses seed villagers exactly this
	# way: a jigsaw in the floor whose piece is a villager standing on it.
	b.jigsaw(ROOM_X0, 0, RIDGE_Z, 'up_north', 'minecraft:bottom', 'minecraft:bottom',
		'minecraft:village/%s/villagers' % m['biome'], m['floor'], 'rollable')


def facade(b, m):
	"""What the street sees: a lit doorway under the eave, and a postal sign."""
	for x in (DOOR_X - 1, DOOR_X + 1):
		b.put(x, 3, 0, 'minecraft:wall_torch', {'facing': 'north'})
	b.put(DOOR_X, 3, 0, 'minecraft:blue_wall_banner', {'facing': 'north'})

	if m.get('verge'):
		b.put(0, 1, ROOM_Z0, m['verge'])
		b.put(BOX_W - 1, 1, ROOM_Z1, m['verge'])

	# Street connection: the street's own jigsaw targets this one by name, and the
	# street pieces carry theirs one row above their surface, so this sits at y=1
	# in the air column in front of the door.
	#
	# The pool is empty because a house has nothing further to generate. Pointing it
	# back at the streets pool - which a third of vanilla's plains houses do and no
	# other biome does - lets the building sprout another street out of its own
	# doorway, which is how one post office ended up with a village growing round it.
	b.jigsaw(DOOR_X, 1, 0, 'north_up', 'minecraft:building_entrance', 'minecraft:building_entrance',
		'minecraft:empty', 'minecraft:structure_void', 'aligned')


def build(m):
	height = 8 if m['roof_style'] == 'gable' else 6
	b = Builder((BOX_W, height, BOX_D))
	ground_and_floor(b, m)
	walls(b, m)
	if m['roof_style'] == 'gable':
		gable_roof(b, m)
	else:
		flat_roof(b, m)
	interior(b, m)
	facade(b, m)
	b.save('src/main/resources/data/village-mail/structure/post_office_%s.nbt' % m['biome'])


# -- Biomes ---------------------------------------------------------------
# Materials are taken from what each biome's vanilla houses are actually built
# of, so a post office reads as one more building on the same street.

GRASS = ('minecraft:grass_block', {'snowy': 'false'})

BIOMES = [
	{
		'biome': 'plains',
		'verge': 'minecraft:poppy',
		'roof_style': 'gable',
		'ground': GRASS,
		'path': 'minecraft:dirt_path',
		'base': 'minecraft:cobblestone',
		'wall': 'minecraft:oak_planks',
		'band': 'minecraft:white_terracotta',
		'post': ('minecraft:oak_log', {'axis': 'y'}),
		'plate': ('minecraft:oak_log', {}),
		'floor': 'minecraft:oak_planks',
		'stairs': 'minecraft:oak_stairs',
		'slab': 'minecraft:oak_slab',
		'roof': 'minecraft:oak_planks',
		'door': 'minecraft:oak_door',
		'window_ew': ('minecraft:glass_pane', PANE_EW),
		'window_ns': ('minecraft:glass_pane', PANE_NS),
		'carpet': 'minecraft:blue_carpet',
		'pot': 'minecraft:potted_cornflower',
	},
	{
		'biome': 'savanna',
		'verge': 'minecraft:short_grass',
		'roof_style': 'gable',
		'ground': GRASS,
		'path': 'minecraft:dirt_path',
		'base': 'minecraft:smooth_stone',
		'wall': 'minecraft:acacia_planks',
		'band': 'minecraft:yellow_terracotta',
		'post': ('minecraft:acacia_log', {'axis': 'y'}),
		'plate': ('minecraft:acacia_log', {}),
		'floor': 'minecraft:acacia_planks',
		'stairs': 'minecraft:acacia_stairs',
		'slab': 'minecraft:acacia_slab',
		'roof': 'minecraft:acacia_planks',
		'door': 'minecraft:acacia_door',
		'window_ew': ('minecraft:glass_pane', PANE_EW),
		'window_ns': ('minecraft:glass_pane', PANE_NS),
		'carpet': 'minecraft:blue_carpet',
		'pot': 'minecraft:potted_acacia_sapling',
	},
	{
		'biome': 'taiga',
		'verge': 'minecraft:fern',
		'roof_style': 'gable',
		'ground': GRASS,
		'path': 'minecraft:dirt_path',
		'base': 'minecraft:cobblestone',
		'wall': 'minecraft:spruce_planks',
		'band': 'minecraft:mossy_cobblestone',
		'post': ('minecraft:spruce_log', {'axis': 'y'}),
		'plate': ('minecraft:spruce_log', {}),
		'floor': 'minecraft:spruce_planks',
		'stairs': 'minecraft:spruce_stairs',
		'slab': 'minecraft:spruce_slab',
		'roof': 'minecraft:spruce_planks',
		'door': 'minecraft:spruce_door',
		'window_ew': ('minecraft:glass_pane', PANE_EW),
		'window_ns': ('minecraft:glass_pane', PANE_NS),
		'carpet': 'minecraft:blue_carpet',
		'pot': 'minecraft:potted_fern',
	},
	{
		'biome': 'snowy',
		'roof_style': 'gable',
		'ground': ('minecraft:snow_block', {}),
		'path': 'minecraft:dirt_path',
		'base': 'minecraft:cobblestone',
		'wall': 'minecraft:spruce_planks',
		'band': 'minecraft:diorite',
		'post': ('minecraft:stripped_spruce_log', {'axis': 'y'}),
		'plate': ('minecraft:stripped_spruce_log', {}),
		'floor': 'minecraft:spruce_planks',
		'stairs': 'minecraft:spruce_stairs',
		'slab': 'minecraft:spruce_slab',
		'roof': 'minecraft:spruce_planks',
		'door': 'minecraft:spruce_door',
		'window_ew': ('minecraft:glass_pane', PANE_EW),
		'window_ns': ('minecraft:glass_pane', PANE_NS),
		'carpet': 'minecraft:blue_carpet',
		'pot': 'minecraft:potted_spruce_sapling',
	},
	{
		'biome': 'desert',
		'verge': 'minecraft:dead_bush',
		'roof_style': 'flat',
		'ground': ('minecraft:sand', {}),
		'path': 'minecraft:smooth_sandstone',
		'base': 'minecraft:cut_sandstone',
		'wall': 'minecraft:smooth_sandstone',
		'band': 'minecraft:terracotta',
		'post': ('minecraft:cut_sandstone', {}),
		'plate': ('minecraft:cut_sandstone', {}),
		'floor': 'minecraft:smooth_sandstone',
		'stairs': 'minecraft:smooth_sandstone_stairs',
		'slab': 'minecraft:smooth_sandstone_slab',
		'roof': 'minecraft:smooth_sandstone',
		'parapet': ('minecraft:sandstone_wall',
			{'east': 'none', 'north': 'none', 'south': 'none', 'west': 'none',
			 'up': 'true', 'waterlogged': 'false'}),
		'door': 'minecraft:jungle_door',
		# Desert houses have open windows, not glazed ones.
		'window_ew': ('minecraft:air', None),
		'window_ns': ('minecraft:air', None),
		'carpet': 'minecraft:blue_carpet',
		'pot': 'minecraft:potted_cactus',
	},
]


def build_public_mailbox():
	"""The street-corner mailbox: one jigsaw that resolves to the block itself."""
	b = Builder((1, 1, 1))
	b.jigsaw(0, 0, 0, 'down_south', 'minecraft:bottom', 'minecraft:bottom',
		'minecraft:empty', 'village-mail:public_mailbox[facing=north]', 'rollable')
	b.save('src/main/resources/data/village-mail/structure/public_mailbox.nbt')


if __name__ == '__main__':
	print('Post offices:')
	for m in BIOMES:
		build(m)
	print('Decor:')
	build_public_mailbox()
