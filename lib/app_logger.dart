import 'dart:io';
import 'package:path_provider/path_provider.dart';
import 'package:logger/logger.dart';

class FileLogOutput extends LogOutput {
  IOSink? _sink;
  Future<void> _init() async {
    if (_sink != null) return;
    final dir = await getApplicationDocumentsDirectory();
    final file = File('${dir.path}/app_log.txt');
    _sink = file.openWrite(mode: FileMode.append);
  }

  @override
  void output(OutputEvent event) async {
    await _init();
    final message = event.lines.join('\n') + '\n';
    _sink?.write(message);
    _sink?.flush();
  }

  Future<void> close() async {
    await _sink?.flush();
    await _sink?.close();
  }
}

final appLogger = Logger(
  printer: PrettyPrinter(),
  output: MultiOutput([
    ConsoleOutput(),
    FileLogOutput(),
  ]),
);

void logInfo(String msg) => appLogger.i(msg);
void logError(String msg) => appLogger.e(msg);
void logDebug(String msg) => appLogger.d(msg);