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
    'https://raw.githubusercontent.com/Melvud/antimax/version.json'
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
        final dir = await getExternalStorageDirectory();
        final filePath = '${dir!.path}/update.apk';
        final apkResponse = await http.get(Uri.parse(apkUrl));
        final file = File(filePath);
        await file.writeAsBytes(apkResponse.bodyBytes);

        // Укажите ваш package name!
        await InstallPlugin.installApk(filePath, 'com.example.messenger_app');
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