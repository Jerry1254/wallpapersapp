"""Launcher guards: no persistent environment access or process termination in tests."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('launcher',Path(__file__).with_name('creator-studio.py'))
launcher=importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


class LauncherTests(unittest.TestCase):
    def config(self, root, environment='LOCAL_DEV'):
        directory=Path(root)/'.runtime/local-api';directory.mkdir(parents=True)
        (directory/'compose.env').write_text('QJ_MYSQL_DATABASE=wallpaper_app\nQJ_API_HOST_PORT=8080\nQJ_MYSQL_HOST_PORT=3307\nQJ_REDIS_HOST_PORT=6380\n')
        identity={key:'test' for key in launcher.IDENTITY.values()}
        (directory/'creator-compose.json').write_text(json.dumps({'services':{'api':{'image':'qingjing-wallpaper-local-api:creator-test','environment':{'QJ_ENVIRONMENT_ID':environment,'QJ_DEPLOYMENT_STAGE':'LOCAL',**identity}}}}))
    def test_rejects_other_environment_without_modifying_configuration(self):
        with tempfile.TemporaryDirectory() as folder,patch.object(launcher,'RUNTIME',Path(folder)/'.runtime'):
            self.config(folder,'ONLINE_MAIN')
            with self.assertRaisesRegex(ValueError,'不属于本地'):launcher.configuration()
            value=json.loads((Path(folder)/'.runtime/local-api/creator-compose.json').read_text())
            self.assertEqual(value['services']['api']['environment']['QJ_ENVIRONMENT_ID'],'ONLINE_MAIN')
    def test_read_only_check_does_not_start_or_stop_services(self):
        with tempfile.TemporaryDirectory() as folder,patch.object(launcher,'RUNTIME',Path(folder)/'.runtime'),patch.object(launcher,'read',return_value=None),patch.object(launcher.subprocess,'run') as run,patch.object(launcher.os,'kill') as kill,patch('sys.argv',['creator-studio.py','--check']):
            self.config(folder)
            self.assertEqual(launcher.main(),1)
            run.assert_not_called();kill.assert_not_called()
    def test_active_task_blocks_restart(self):
        values=[{'name':'qingjing-creator-studio','pid':123},{'activeTaskId':'task'}]
        with patch.object(launcher,'read',side_effect=values),patch.object(launcher.os,'kill') as kill:
            with self.assertRaisesRegex(ValueError,'仍有'):launcher.restart_server()
            kill.assert_not_called()


if __name__=='__main__':unittest.main()
