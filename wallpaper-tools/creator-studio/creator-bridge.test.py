"""Restart safety covers browser preparation, queueing and the encoding worker."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('qa_bridge', Path(__file__).with_name('creator-bridge.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PendingGenerationTests(unittest.TestCase):
    def test_restart_stays_blocked_through_all_active_stages(self):
        bridge = module.CreatorBridge()
        for index, state in enumerate(['PREPARING', 'QUEUED', 'RUNNING', 'CANCEL_REQUESTED']):
            bridge.observe_tasks({'id': 'task-a', 'state': state, 'updatedAt': str(index)})
            self.assertTrue(bridge.has_pending_generation(), state)
        bridge.observe_tasks({'id': 'task-a', 'state': 'CANCELLED', 'updatedAt': '5'})
        self.assertFalse(bridge.has_pending_generation())

    def test_final_state_wins_after_clock_correction_and_cannot_reactivate(self):
        for final in ['SUCCEEDED', 'CANCELLED']:
            bridge = module.CreatorBridge()
            bridge.observe_tasks({'id': 'task-a', 'state': 'RUNNING', 'updatedAt': '9'})
            bridge.observe_tasks({'id': 'task-a', 'state': final, 'updatedAt': '8'})
            self.assertFalse(bridge.has_pending_generation(), final)
            bridge.observe_tasks({'id': 'task-a', 'state': 'RUNNING', 'updatedAt': '10'})
            self.assertFalse(bridge.has_pending_generation(), final)

    def test_failed_task_can_still_be_explicitly_retried(self):
        bridge = module.CreatorBridge()
        bridge.observe_tasks({'id': 'task-a', 'state': 'FAILED', 'updatedAt': '1'})
        self.assertFalse(bridge.has_pending_generation())
        bridge.observe_tasks({'id': 'task-a', 'state': 'QUEUED', 'updatedAt': '2'})
        self.assertTrue(bridge.has_pending_generation())

    def test_older_response_cannot_reactivate_completed_task(self):
        bridge = module.CreatorBridge()
        bridge.observe_tasks({'id': 'task-a', 'state': 'SUCCEEDED', 'updatedAt': '2'})
        bridge.observe_tasks([{'id': 'task-a', 'state': 'RUNNING', 'updatedAt': '1'},
                              {'id': 'task-b', 'state': 'PREPARING', 'updatedAt': '1'}])
        self.assertTrue(bridge.has_pending_generation())
        bridge.observe_tasks({'id': 'task-b', 'state': 'FAILED', 'updatedAt': '2'})
        self.assertFalse(bridge.has_pending_generation())


if __name__ == '__main__':
    unittest.main()
