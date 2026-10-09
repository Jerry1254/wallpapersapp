import 'dart:convert';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:path_provider/path_provider.dart';
import 'api.dart';

abstract interface class SupportStore {
  Future<SupportData> load();
  Future<void> save(SupportData data);
}

class FileSupportStore implements SupportStore {
  FileSupportStore(this.file);
  final File file;
  Future<void> _queue = Future.value();
  static Future<FileSupportStore> scoped(String scope) async {
    final dir = await getApplicationSupportDirectory();
    return FileSupportStore(
      File('${dir.path}/support-${sha256.convert(utf8.encode(scope))}.json'),
    );
  }

  @override
  Future<SupportData> load() async {
    if (!await file.exists())
      return {'pending': <dynamic>[], 'drafts': <String, dynamic>{}};
    return jsonDecode(await file.readAsString()) as SupportData;
  }

  @override
  Future<void> save(SupportData data) {
    final bytes = jsonEncode(data);
    final operation = _queue.catchError((Object _) {}).then((_) async {
      await file.parent.create(recursive: true);
      final temp = File('${file.path}.next');
      await temp.writeAsString(bytes, flush: true);
      await temp.rename(file.path);
    });
    _queue = operation;
    return operation;
  }
}
