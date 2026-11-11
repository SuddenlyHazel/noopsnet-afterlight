"""Check Iris routing and blend targets after actual properties preprocessing."""
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile
from build_shader import build
import verify_shader_mac as checker


class TranslucencyTest(unittest.TestCase):
    def test_iris_1211_routes_translucency_and_glint_consistently(self):
        with tempfile.TemporaryDirectory() as tmp:
            with ZipFile(build()) as archive:
                archive.extractall(tmp)
            checker.ROOT = str(Path(tmp) / 'shaders')
            for mac in (True, False):
                props = checker.preprocess_properties(checker.iris_macros(mac, False, 12101))
                self.assertIn('separateEntityDraws = true', props)
                for world in ('world0', 'world-1', 'world1'):
                    enabled = checker.enabled_programs(world, props, {})
                    for stage in ('entities', 'block', 'particles'):
                        self.assertNotEqual(enabled.get(f'gbuffers_{stage}_translucent'), 'false')
                self.assertIn('blend.gbuffers_spidereyes.colortex13 = ONE ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA', props)
                self.assertIn('blend.gbuffers_armor_glint.colortex13 = ONE ONE ZERO ONE', props)
            # Preserve the older fallback when Iris isn't supplying the feature.
            macros = [m for m in checker.iris_macros(False, False, 12101) if not m.startswith('IS_IRIS')]
            props = checker.preprocess_properties(macros)
            self.assertNotIn('separateEntityDraws = true', props)
            self.assertEqual(checker.enabled_programs('world0', props, {})['gbuffers_entities_translucent'], 'false')


if __name__ == '__main__':
    unittest.main()
