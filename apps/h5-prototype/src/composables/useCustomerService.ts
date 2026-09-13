import QRCode from 'qrcode';
import { showImagePreview, showToast } from 'vant';
import { ref } from 'vue';

const wechatId = 'qingjing_service';
const qrCodeUrl = ref('');
let qrPromise: Promise<string> | null = null;

const ensureQrCode = async () => {
  if (qrCodeUrl.value) return qrCodeUrl.value;
  qrPromise ??= QRCode.toDataURL(`倾境壁纸客服微信：${wechatId}`, {
    width: 520,
    margin: 2,
    color: { dark: '#191817', light: '#FFFFFF' }
  });
  qrCodeUrl.value = await qrPromise;
  return qrCodeUrl.value;
};

const copyText = async (value: string, message: string) => {
  try {
    await navigator.clipboard.writeText(value);
  } catch {
    const input = document.createElement('textarea');
    input.value = value;
    document.body.append(input);
    try {
      input.select();
      if (!document.execCommand('copy')) throw new Error('Clipboard unavailable');
    } catch {
      showToast({ message: '复制失败，请手动选择文字复制', position: 'bottom' });
      return;
    } finally {
      input.remove();
    }
  }
  showToast({ message, position: 'bottom' });
};

export const useCustomerService = () => {
  const previewQrCode = async () => {
    const image = await ensureQrCode();
    showImagePreview({ images: [image], closeable: true });
  };

  const saveQrCode = async () => {
    const image = await ensureQrCode();
    const anchor = document.createElement('a');
    anchor.href = image;
    anchor.download = '倾境壁纸客服二维码.png';
    anchor.click();
    showToast({ message: '已发起二维码下载，请检查浏览器下载记录', position: 'bottom' });
  };

  return {
    wechatId,
    qrCodeUrl,
    ensureQrCode,
    previewQrCode,
    saveQrCode,
    copyWechat: () => copyText(wechatId, '客服微信已复制'),
    copySupportId: (supportId: string) => copyText(supportId, '设备支持编号已复制')
  };
};
