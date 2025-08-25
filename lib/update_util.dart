import 'dart:io';
import 'package:flutter/material.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';
import 'package:install_plugin/install_plugin.dart';
import 'dart:convert';

Future<void> checkForUpdateAndInstall(BuildContext context) async {
  final info = await PackageInfo.fromPlatform();
  final currentVersion = info.version;
  final response = await http.get(Uri.parse(
    'https://raw.githubusercontent.com/Melvud/antimax/main/version.json'
  ));

  if (response.statusCode == 200) {
    final data = json.decode(response.body);
    final latestVersion = data['version'];
    final apkUrl = data['apk_url'];
    final changelog = data['changelog'] ?? '';

    if (_isNewerVersion(latestVersion, currentVersion)) {
      final confirm = await showDialog<bool>(
        context: context,
        builder: (_) => AlertDialog(
          title: Text('Доступна новая версия $latestVersion!'),
          content: Text('Что нового:\n$changelog\n\nОбновить сейчас?'),
          actions: [
            TextButton(
              child: const Text('Позже'),
              onPressed: () => Navigator.pop(context, false),
            ),
            TextButton(
              child: const Text('Обновить'),
              onPressed: () => Navigator.pop(context, true),
            ),
          ],
        ),
      );
      if (confirm == true) {
        await _downloadAndInstallApk(context, apkUrl, latestVersion);
      }
    }
  }
}

bool _isNewerVersion(String latest, String current) {
  final l = latest.split('.').map(int.parse).toList();
  final c = current.split('.').map(int.parse).toList();
  for (var i = 0; i < l.length; i++) {
    if (i >= c.length || l[i] > c[i]) return true;
    if (l[i] < c[i]) return false;
  }
  return false;
}

Future<void> _downloadAndInstallApk(BuildContext context, String apkUrl, String version) async {
  final dir = await getExternalStorageDirectory();
  final filePath = '${dir!.path}/update_$version.apk';
  final file = File(filePath);

  bool canceled = false;
  final progressNotifier = ValueNotifier<double>(0.0);
  bool started = false;

  await showDialog(
    context: context,
    barrierDismissible: false,
    builder: (ctx) {
      // Запускаем скачивание только один раз!
      if (!started) {
        started = true;
        () async {
          try {
            final request = http.Request('GET', Uri.parse(apkUrl));
            final response = await request.send();

            if (response.statusCode != 200) {
              throw Exception('Не удалось загрузить обновление (ошибка сервера)');
            }

            final total = response.contentLength ?? 0;
            int received = 0;
            final sink = file.openWrite();

            await for (final chunk in response.stream) {
              if (canceled) {
                await sink.close();
                if (file.existsSync()) file.deleteSync();
                Navigator.of(ctx).pop();
                return;
              }
              sink.add(chunk);
              received += chunk.length;
              progressNotifier.value = (total > 0) ? received / total : 0;
            }
            await sink.close();
            if (!canceled) {
              Navigator.of(ctx).pop();
              await InstallPlugin.installApk(filePath);
            }
          } catch (e) {
            if (file.existsSync()) file.deleteSync();
            Navigator.of(ctx).pop();
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(content: Text('Ошибка загрузки: $e')),
            );
          }
        }();
      }

      return AlertDialog(
        title: const Text('Скачивание обновления'),
        content: ValueListenableBuilder<double>(
          valueListenable: progressNotifier,
          builder: (_, progress, __) => Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              LinearProgressIndicator(value: progress),
              const SizedBox(height: 16),
              Text('${(progress * 100).toStringAsFixed(0)} %'),
              const SizedBox(height: 12),
              Text('Пожалуйста, не закрывайте приложение'),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () {
              canceled = true;
            },
            child: const Text('Отмена', style: TextStyle(color: Colors.red)),
          ),
        ],
      );
    },
  );
}