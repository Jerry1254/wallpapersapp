"""Read only the signed-in creator's visible account card, never infer identity from a nickname."""
import re
from urllib.parse import urlsplit

HOME = {'douyin': 'https://creator.douyin.com/creator-micro/home',
        'xhs': 'https://creator.xiaohongshu.com/new/home'}

ACCOUNT_CARD_READY = r'''platform => {
  const label = platform === 'douyin'
    ? /抖音号\s*[：:]\s*[A-Za-z0-9_.-]+/
    : /小红书(?:账号|号)\s*[：:]\s*[A-Za-z0-9_.-]+/;
  return label.test(document.body.innerText);
}'''

# Restrict candidates to a labelled account card. Recent posts, followers and
# recommendation cards must never become the identity of the signed-in account.
ACCOUNT_CARD = r'''(platform) => {
  const visible=e=>!!(e.getClientRects().length&&getComputedStyle(e).visibility!=='hidden');
  const label=platform==='douyin'?/抖音号\s*[：:]\s*([A-Za-z0-9_.-]+)/:/小红书(?:账号|号)\s*[：:]\s*([A-Za-z0-9_.-]+)/;
  const rows=[];
  for(const element of document.body.querySelectorAll('*')) {
    if(!visible(element))continue;
    const text=(element.innerText||'').trim();
    if(text.length>180)continue;
    const match=text.match(label);if(!match)continue;
    if([...element.children].some(child=>visible(child)&&label.test(child.innerText||'')))continue;
    let card=element;
    for(let depth=0;depth<6;depth++) {
      if(!card.parentElement||card.parentElement===document.body||(card.parentElement.innerText||'').length>800)break;
      card=card.parentElement;
      if(card.querySelector('img'))break;
    }
    const profilePattern=platform==='douyin'?/^https:\/\/www\.douyin\.com\/user\/([A-Za-z0-9_-]+)(?:[/?#]|$)/:/^https:\/\/www\.xiaohongshu\.com\/user\/profile\/([A-Za-z0-9_-]+)(?:[/?#]|$)/;
    const profiles=[...card.querySelectorAll('a[href]')].filter(visible).map(a=>a.href.match(profilePattern)?.[1]).filter(Boolean);
    const names=[...card.querySelectorAll('.name,[class*="name"],[class*="Name"]')].filter(visible).map(e=>(e.innerText||'').trim()).filter(v=>v&&v.length<=160&&!label.test(v));
    const images=[...card.querySelectorAll('img')].filter(visible);
    const avatar=images.find(e=>/avatar|头像/i.test(e.className+' '+e.alt))||(images.length===1?images[0]:null);
    rows.push({handle:match[1],profiles:[...new Set(profiles)],nickname:[...new Set(names)].length===1?names[0]:'',avatarUrl:avatar?.currentSrc||''});
  }
  return rows;
}'''


class IdentityError(Exception):
    pass


def choose_identity(rows, expected=None):
    handles={row.get('handle') for row in rows if re.fullmatch(r'[A-Za-z0-9_.-]{1,110}', row.get('handle', ''))}
    if len(handles)!=1:
        raise IdentityError('未能明确读取当前平台账号，请打开创作者中心确认账号信息后重新检查')
    handle=next(iter(handles))
    rows=[row for row in rows if row.get('handle')==handle]
    profiles={value for row in rows for value in row.get('profiles', []) if re.fullmatch(r'[A-Za-z0-9_-]{1,110}', value)}
    if len(profiles)>1:
        raise IdentityError('平台页面存在多个账号标识，已停止身份绑定，请重新检查')
    candidates={'handle:'+handle}|{'profile:'+value for value in profiles}
    if expected and expected not in candidates:
        raise IdentityError('本次登录与原平台账号不一致，原登录信息未被覆盖；请登录原账号，或单独添加新账号')
    # Use the labelled platform number consistently. Alternating between a public
    # profile link and a number when a link is absent would bypass duplicate checks.
    user_id=expected or 'handle:'+handle
    names={row.get('nickname','').strip() for row in rows if row.get('nickname','').strip()}
    nickname=next(iter(names)) if len(names)==1 else ''
    avatars=[row.get('avatarUrl','') for row in rows]
    avatar=next((url for url in avatars if urlsplit(url).scheme=='https' and urlsplit(url).hostname and not urlsplit(url).username),'')
    return {'platformUserId':user_id,'nickname':nickname[:160],'avatarUrl':avatar[:2000]}


async def read_identity(page, platform, expected=None, timeout=20000):
    await page.goto(HOME[platform], wait_until='domcontentloaded', timeout=90000)
    try:
        # The home page hydrates after navigation; wait for its labelled account card.
        await page.wait_for_function(ACCOUNT_CARD_READY,arg=platform,timeout=timeout)
    except Exception:
        raise IdentityError('尚未读取到平台账号身份；登录可能已失效或页面需要验证，请重新登录后检查') from None
    return choose_identity(await page.evaluate(ACCOUNT_CARD, platform), expected)
