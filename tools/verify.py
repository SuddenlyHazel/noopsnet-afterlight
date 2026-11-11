#!/usr/bin/env python3
"""Validate packaged resource closure and LabPBR signal semantics."""
import json
import itertools
from pathlib import Path
import unittest
from PIL import Image
from generate import CATALOG, ID, ROOT

RES = ROOT/'src/main/resources'
ASSETS = RES/f'assets/{ID}'


class Resources(unittest.TestCase):
    def test_no_coplanar_faces_fight_for_the_same_pixels(self):
        for path in (ASSETS/'models/block').glob('*.json'):
            elements = json.loads(path.read_text()).get('elements',[])
            for a,b in itertools.combinations(elements,2):
                for face in set(a['faces']) & set(b['faces']):
                    axis = {'east':0,'west':0,'up':1,'down':1,'north':2,'south':2}[face]
                    bound = 'to' if face in ('east','up','south') else 'from'
                    if abs(a[bound][axis]-b[bound][axis]) > 1e-6: continue
                    overlap = all(min(a['to'][i],b['to'][i])-max(a['from'][i],b['from'][i]) > 1e-6
                                  for i in range(3) if i != axis)
                    self.assertFalse(overlap, f'{path.name}: coincident {face} faces')

    def test_models_resolve_and_states_cover_every_orientation(self):
        names = list(CATALOG['materials']) + [f'{c}_{f}' for c in CATALOG['colors'] for f in CATALOG['forms']]
        self.assertEqual(len(names), 75)
        for name in names:
            with self.subTest(block=name):
                states = json.loads((ASSETS/f'blockstates/{name}.json').read_text())['variants']
                self.assertEqual(len(states), 1 if name in CATALOG['materials'] else 32 if name.rsplit('_',1)[1] in ('channel','strip','elbow') else 16)
                for variant in states.values():
                    model = RES/'assets'/variant['model'].replace(':','/models/')
                    self.assertTrue(model.with_suffix('.json').is_file(), variant)
                self.assertTrue((ASSETS/f'models/item/{name}.json').is_file())
        for path in (ASSETS/'models/block').glob('*.json'):
            model = json.loads(path.read_text())
            for texture in model.get('textures',{}).values():
                self.assertTrue((RES/'assets'/texture.replace(':','/textures/')).with_suffix('.png').is_file(), path)
            for element in model.get('elements',[]):
                for a,b in zip(element['from'],element['to']):
                    self.assertTrue(0 <= a <= b <= 16, path)
                for face in element['faces'].values():
                    self.assertIn(face['texture'][1:],model['textures'],path)
            if path.stem.endswith('_off'):
                self.assertFalse(any(e.get('neoforge_data') for e in model['elements']), path)
            elif path.stem.endswith('_on'):
                self.assertTrue(any(e.get('neoforge_data',{}).get('block_light')==15 for e in model['elements']), path)

    def test_material_maps_have_matching_dimensions_and_valid_emission(self):
        for path in (ASSETS/'textures/block').glob('*.png'):
            if path.stem.endswith(('_n','_s')): continue
            with self.subTest(texture=path.stem):
                image = Image.open(path)
                normal = Image.open(path.with_stem(path.stem+'_n'))
                spec = Image.open(path.with_stem(path.stem+'_s'))
                image.load();normal.load();spec.load()
                self.assertEqual(image.size, (64,64))
                self.assertEqual(image.size,normal.size); self.assertEqual(image.size,spec.size)
                self.assertEqual(spec.mode,'RGBA')
                alpha = spec.getchannel('A').getextrema()
                self.assertLessEqual(alpha[1],254, '255 is NOT full emission in LabPBR')
                if path.stem.endswith(('_off','_glow0')) or path.stem in CATALOG['materials'] or path.stem=='walnut':
                    self.assertEqual(alpha,(0,0))
                else: self.assertGreater(alpha[1],0)
                self.assertGreater(normal.getchannel('A').getextrema()[0],0)
                for y in range(image.height):
                    for x in range(image.width):
                        if image.getpixel((x,y))[3] == 0:
                            self.assertEqual(spec.getpixel((x,y))[3],0,'Invisible pixels must not glow')

    def test_connections_cover_every_state_exactly_once(self):
        for color in CATALOG['colors']:
            for form in ('channel','strip','elbow'):
                states=json.loads((ASSETS/f'blockstates/{color}_{form}.json').read_text())['variants']
                selectors=[dict(part.split('=') for part in key.split(',')) for key in states]
                for facing,lit,wet,joining in itertools.product(('north','east','south','west'),('true','false'),('true','false'),('true','false')):
                    state=dict(facing=facing,lit=lit,waterlogged=wet,joining=joining)
                    self.assertEqual(sum(all(state[k]==v for k,v in selector.items()) for selector in selectors),1,(color,form,state))

    def test_glow_profiles_scale_only_emission(self):
        for kind in ('tube','market','metro','holo'):
            source=Image.open(ASSETS/f'textures/block/cyan_{kind}_s.png').getchannel('A')
            base=Image.open(ASSETS/f'textures/block/cyan_{kind}.png')
            for glow in range(4):
                path=ASSETS/f'textures/block/{kind}_glow{glow}.png'
                self.assertEqual(Image.open(path).tobytes(),base.tobytes())
                profile=Image.open(path.with_stem(path.stem+'_s')).getchannel('A')
                self.assertEqual(profile.tobytes(),source.point(lambda a:a*glow//4).tobytes())

    def test_recipes_and_loot_reference_existing_items(self):
        data = RES/f'data/{ID}'
        ids = {f'{ID}:{p.stem}' for p in (ASSETS/'blockstates').glob('*.json')}
        item_ids=ids|{f'{ID}:palette'}
        for path in (data/'recipe').glob('*.json'):
            recipe = json.loads(path.read_text())
            self.assertIn(recipe['result']['id'],item_ids,path)
            self.assertTrue(1 <= recipe['result']['count'] <= 64,path)
            for ingredient in list(recipe.get('key',{}).values())+recipe.get('ingredients',[]):
                if 'tag' in ingredient:
                    if ingredient['tag'].startswith('c:'):continue
                    target = data/'tags/item'/ingredient['tag'].split(':')[1]
                    tag = json.loads(target.with_suffix('.json').read_text())
                    self.assertTrue(set(tag['values']) <= ids,path)
        for name in ids:
            table = json.loads((data/f'loot_table/blocks/{name.split(":")[1]}.json').read_text())
            self.assertEqual(table['pools'][0]['entries'][0]['name'],name)
        self.assertEqual(len(list((data/'recipe').glob('*.json'))),148)

    def test_only_emissive_geometry_is_tinted_and_loot_preserves_color(self):
        for path in (ASSETS/'models/block').glob('*.json'):
            for element in json.loads(path.read_text()).get('elements',[]):
                for face in element['faces'].values():
                    self.assertEqual(face.get('tintindex',-1),0 if face['texture'] in ('#tube','#glyph') else -1,path)
        for c in CATALOG['colors']:
            for f in CATALOG['forms']:
                path=RES/f'data/{ID}/loot_table/blocks/{c}_{f}.json'
                functions=json.loads(path.read_text())['pools'][0]['entries'][0]['functions']
                self.assertEqual(functions,[{'function':'minecraft:copy_components','source':'block_entity','include':['minecraft:dyed_color','minecraft:custom_data']}])


if __name__ == '__main__': unittest.main(verbosity=2)
