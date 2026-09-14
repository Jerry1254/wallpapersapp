import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class HelpScreen extends StatelessWidget {
  const HelpScreen({super.key, this.customerService = false});
  final bool customerService;
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(customerService ? '微信客服' : '壁纸设计与设置教程')),
    body: ListView(
      padding: const EdgeInsets.all(20),
      children: customerService
          ? [
              const Text(
                '需要帮助？',
                style: TextStyle(fontSize: 28, fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 16),
              const SelectableText('客服微信：qingjing_service'),
              const SizedBox(height: 12),
              FilledButton(
                onPressed: () async {
                  try {
                    await Clipboard.setData(
                      const ClipboardData(text: 'qingjing_service'),
                    );
                    if (context.mounted) {
                      ScaffoldMessenger.of(
                        context,
                      ).showSnackBar(const SnackBar(content: Text('客服微信已复制')));
                    }
                  } catch (_) {
                    if (context.mounted) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(content: Text('复制失败，请手动选择文字复制')),
                      );
                    }
                  }
                },
                child: const Text('复制客服微信'),
              ),
              const SizedBox(height: 20),
              const Text(
                '联系客服时可说明手机型号、系统版本和问题步骤。正式设备支持编号将在身份接入后提供。请勿发送兑换码、密钥或会话凭据。',
              ),
              TextButton(
                onPressed: () => Navigator.push(
                  context,
                  MaterialPageRoute<void>(builder: (_) => const HelpScreen()),
                ),
                child: const Text('查看教程'),
              ),
            ]
          : [
              _lesson(
                '01 静态壁纸',
                '准备适合竖屏显示的完整画面，推荐比例 1:2。主体避开顶部时间与底部手势区域，可使用 JPG、PNG 或 WebP。',
              ),
              _lesson(
                '02 Android 4D 分层',
                '至少准备背景和透明前景，背景四周补全移动安全区域，前景使用透明 PNG。预览开始时以当前姿态校准，轻轻倾斜手机可查看层间视差；没有可用姿态传感器时静态显示。',
              ),
              _lesson('03 动态效果素材', '建议使用 6–12 秒、无声、首尾自然衔接的素材，避免快速闪烁或强烈位移。'),
              _lesson(
                '小米与红米动态壁纸权限',
                '若视频或 4D 系统设置页立即返回，请在手机设置→应用管理→倾境壁纸→权限管理→其他权限中允许动态壁纸服务，再回到作品详情重新检测手机能力。Android 13 或更早系统可能无法读取设置位置；系统返回确认后保留已校验资源，请到桌面和锁屏检查实际效果。',
              ),
              _lesson(
                '04 获得与设置',
                '选择当前设备可用的作品，获得权益后下载并校验资源，再预览或进入系统设置。桌面、锁屏与两者的选项按系统实际提供；取消或无法查询结果时不会显示设置成功。',
              ),
              _lesson(
                '05 真实效果预览',
                '4D 详情画面可通过倾斜手机或拖动交互，动态壁纸详情可循环播放视频。获得权益并下载后，可使用正式资源全屏预览并进入系统壁纸设置。',
              ),
              _lesson(
                '06 提交素材与授权',
                '按壁纸名称整理原图、分层和效果说明，注明平台并保留设计文件。只提交拥有使用权的素材，人物照片须有肖像授权，保留来源与授权记录。',
              ),
              TextButton(
                onPressed: () => Navigator.push(
                  context,
                  MaterialPageRoute<void>(
                    builder: (_) => const HelpScreen(customerService: true),
                  ),
                ),
                child: const Text('联系客服'),
              ),
            ],
    ),
  );
  Widget _lesson(String title, String content) => Card(
    margin: const EdgeInsets.only(bottom: 16),
    child: Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 12),
          Text(content, style: const TextStyle(height: 1.6)),
        ],
      ),
    ),
  );
}
