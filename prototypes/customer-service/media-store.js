(() => {
  'use strict';
  let opening;
  const urls = new Map();
  function database() {
    return opening ||= new Promise((resolve, reject) => {
      const request = indexedDB.open('qingjing-support-prototype-media', 1);
      request.onupgradeneeded = () => request.result.createObjectStore('files');
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(new Error('素材保存失败，请检查浏览器存储权限。'));
      request.onblocked = () => reject(new Error('素材存储暂时无法打开，请关闭旧的原型页面后重试。'));
    });
  }
  async function save(file, type) {
    const allowed = type === 'image' ? ['image/jpeg', 'image/png', 'image/webp'] : ['video/mp4', 'video/webm'];
    if (!allowed.includes(file.type)) throw new Error(type === 'image' ? '请选择 JPG、PNG 或 WebP 图片。' : '请选择 MP4 或 WebM 视频。');
    if (file.size > 100 * 1024 * 1024) throw new Error('素材不能超过 100 MB，请选择较小的文件。');
    let blob = file;
    if (type === 'image') {
      const image = new Image();
      const source = URL.createObjectURL(file);
      try {
        image.src = source;
        await image.decode();
        const scale = Math.min(1, 1600 / Math.max(image.naturalWidth, image.naturalHeight));
        const canvas = document.createElement('canvas');
        canvas.width = Math.max(1, Math.round(image.naturalWidth * scale));
        canvas.height = Math.max(1, Math.round(image.naturalHeight * scale));
        const ctx = canvas.getContext('2d');
        ctx.fillStyle = '#fff';
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        ctx.drawImage(image, 0, 0, canvas.width, canvas.height);
        blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/jpeg', .85));
        if (!blob) throw new Error('图片读取失败，请重新选择。');
      } finally { URL.revokeObjectURL(source); }
    } else {
      const video = document.createElement('video');
      const source = URL.createObjectURL(file);
      try {
        await new Promise((resolve, reject) => {
          const timer = setTimeout(() => reject(new Error('视频读取超时，请重新选择。')), 10000);
          video.onloadedmetadata = () => { clearTimeout(timer); resolve(); };
          video.onerror = () => { clearTimeout(timer); reject(new Error('视频无法播放，请选择可播放的 MP4 或 WebM 文件。')); };
          video.preload = 'metadata';
          video.src = source;
        });
      } finally { video.removeAttribute('src'); video.load(); URL.revokeObjectURL(source); }
    }
    const id = crypto.randomUUID?.() || 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, character => {
      const value = Math.floor(Math.random() * 16);
      return (character === 'x' ? value : (value & 3) | 8).toString(16);
    });
    const db = await database();
    await new Promise((resolve, reject) => {
      const tx = db.transaction('files', 'readwrite');
      tx.objectStore('files').put(blob, id);
      tx.oncomplete = resolve;
      tx.onerror = tx.onabort = () => reject(new Error('素材保存失败，浏览器存储空间可能不足。'));
    });
    return 'local-media:' + id;
  }
  async function url(source) {
    if (/^assets\/(mountain\.svg|coast\.svg|support-demo\.mp4)$/.test(source || '')) return source;
    if (/^data:image\/(jpeg|png|webp);base64,[A-Za-z0-9+/=]+$/.test(source || '')) return source;
    if (!/^local-media:[a-f0-9-]{36}$/.test(source || '')) throw new Error('素材地址无效。');
    if (!urls.has(source)) {
      const db = await database();
      const blob = await new Promise((resolve, reject) => {
        const request = db.transaction('files').objectStore('files').get(source.slice(12));
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(new Error('素材读取失败，请重试。'));
      });
      if (!blob) throw new Error('素材文件已不可用，请在后台重新导入。');
      urls.set(source, URL.createObjectURL(blob));
    }
    return urls.get(source);
  }
  function hydrate(root = document) {
    root.querySelectorAll('[data-media-source]').forEach(element => {
      if (element.dataset.mediaLoading) return;
      element.dataset.mediaLoading = 'true';
      if (element.dataset.mediaPoster) url(element.dataset.mediaPoster).then(source => { if (element.isConnected) element.poster = source; }).catch(() => {});
      url(element.dataset.mediaSource).then(source => {
        if (element.isConnected) element.src = source;
      }).catch(error => {
        if (!element.isConnected) return;
        element.classList.add('media-unavailable');
        element.setAttribute('title', error.message);
        element.closest('.media-preview-button')?.setAttribute('data-media-error', error.message);
      });
    });
  }
  window.addEventListener('pagehide', () => { urls.forEach(URL.revokeObjectURL); urls.clear(); });
  window.SupportMediaStore = {save, url, hydrate};
})();
