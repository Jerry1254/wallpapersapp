import unittest
from identity import choose_identity, IdentityError


class IdentityTests(unittest.TestCase):
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
