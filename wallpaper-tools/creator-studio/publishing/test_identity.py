import unittest
import json
import shutil
import subprocess
from identity import ACCOUNT_CARD, ACCOUNT_CARD_READY, choose_identity, IdentityError


class IdentityTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which('node'), 'Requires Node for the actual page predicate')
    def test_real_account_label_variants_match_both_wait_and_extractor(self):
        # Execute the shipped JS, rather than mocking wait_for_function. The
        # current XHS creator card says 小红书账号, while older cards say 小红书号.
        script = r'''
          const {ready, card} = JSON.parse(process.argv[1]);
          const wait = new Function('document', 'return (' + ready + ')');
          const extract = new Function('document', 'getComputedStyle', 'return (' + card + ')');
          const cases = [
            ['xhs', '小红书账号： 679084946', true],
            ['xhs', '小红书号: test_123', true],
            ['douyin', '抖音号： baking_tree', true],
            ['xhs', '粉丝数： 679084946', false],
            ['douyin', '小红书账号： 679084946', false],
            ['xhs', '小红书账号：', false],
          ];
          for (const [platform, text, expected] of cases) {
            const element = {innerText: text, children: [], parentElement: null,
              getClientRects: () => [{}], querySelectorAll: () => []};
            const document = {body: {innerText: text, querySelectorAll: () => [element]}};
            if (wait(document)(platform) !== expected) throw Error('wait: ' + text);
            const rows = extract(document, () => ({visibility: 'visible'}))(platform);
            if (Boolean(rows.length) !== expected) throw Error('card: ' + text);
            if (expected && !rows[0].handle) throw Error('missing handle');
          }
          const visible = {getClientRects: () => [{}]};
          const name = {...visible, innerText: '像牛的驴'};
          const avatar = {...visible, className: 'avatar', alt: '', currentSrc: 'https://example.com/avatar.jpg'};
          const leaf = {...visible, innerText: '小红书账号：679084946', children: []};
          const document = {body: {innerText: leaf.innerText, querySelectorAll: () => [leaf]}};
          const account = {...visible, innerText: '像牛的驴\n小红书账号：679084946', parentElement: document.body,
            querySelector: selector => selector === 'img' ? avatar : null,
            querySelectorAll: selector => selector === 'img' ? [avatar] : selector.includes('.name') ? [name] : []};
          let parent = account;
          for (let i=0; i<4; i++) parent = {...visible, innerText: leaf.innerText, parentElement: parent, querySelector: () => null};
          leaf.parentElement = parent;
          const rows = extract(document, () => ({visibility: 'visible'}))('xhs');
          if (rows[0].nickname !== '像牛的驴' || rows[0].avatarUrl !== avatar.currentSrc) throw Error('nested account metadata missing');
        '''
        subprocess.run(['node', '-e', script, json.dumps({'ready': ACCOUNT_CARD_READY, 'card': ACCOUNT_CARD})], check=True, capture_output=True, text=True)

    def test_duplicate_dom_nodes_are_one_account(self):
        row={'handle':'wallpaper_01','profiles':['stable123'],'nickname':'倾境','avatarUrl':'https://example.com/avatar.png'}
        self.assertEqual(choose_identity([row,row])['platformUserId'],'handle:wallpaper_01')
        self.assertEqual(choose_identity([{'handle':'wallpaper_01'}])['platformUserId'],'handle:wallpaper_01')

    def test_same_nickname_never_allows_switching_accounts(self):
        with self.assertRaises(IdentityError):
            choose_identity([{'handle':'different','profiles':['other'],'nickname':'倾境'}],'profile:original')

    def test_a_handle_binding_stays_stable_when_a_profile_link_appears(self):
        result=choose_identity([{'handle':'original','profiles':['stable123']}],'handle:original')
        self.assertEqual(result['platformUserId'],'handle:original')

    def test_ambiguous_or_missing_identity_cannot_authorize_publishing(self):
        for rows in ([],[{'nickname':'倾境'}],[{'handle':'one'},{'handle':'two'}],[{'handle':'one','profiles':['a','b']}],[{'handle':'非法/值'}]):
            with self.assertRaises(IdentityError):choose_identity(rows)

    def test_optional_metadata_does_not_fabricate_identity_or_allow_local_avatar(self):
        result=choose_identity([{'handle':'known','avatarUrl':'file:///private/photo.png'}])
        self.assertEqual(result,{'platformUserId':'handle:known','nickname':'','avatarUrl':''})


if __name__=='__main__':unittest.main()
