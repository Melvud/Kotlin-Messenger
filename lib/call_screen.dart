import 'dart:async';

import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:audioplayers/audioplayers.dart';
import 'package:audioplayers_platform_interface/audioplayers_platform_interface.dart'
    as ap; // AudioContext + enums
import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'package:cloud_firestore/cloud_firestore.dart';

import 'signaling.dart';
import 'audio_mode_util.dart';
import 'android_call_util.dart';

import 'ui/buttons/call_action_button.dart';
import 'widgets/permission_gate.dart';

class CallScreen extends StatefulWidget {
  final bool isCaller;
  final String peerId;
  final String peerUsername;
  final String myId;
  final String myUsername;
  final String docId;
  final String callType; // 'audio' or 'video'

  const CallScreen({
    super.key,
    required this.isCaller,
    required this.peerId,
    required this.peerUsername,
    required this.myId,
    required this.myUsername,
    required this.docId,
    required this.callType,
  });

  @override
  State<CallScreen> createState() => _CallScreenState();
}

class _CallScreenState extends State<CallScreen> {
  late final bool _isVideo;
  bool _permissionsGranted = false;

  bool _micOn = true;
  bool _camOn = true;
  bool _speakerOn = true;
  bool _connected = false;
  bool _ended = false;

  final _localRenderer = RTCVideoRenderer();
  final _remoteRenderer = RTCVideoRenderer();

  Signaling? _signaling;
  bool _started = false;

  Timer? _ringTimeout; // 60s
  final Stopwatch _duration = Stopwatch();

  final _ringPlayer = AudioPlayer();     // ringback у звонящего
  final _ringtonePlayer = AudioPlayer(); // ringtone у принимающего

  StreamSubscription<DocumentSnapshot>? _callDocSub;

  @override
  void initState() {
    super.initState();
    _isVideo = widget.callType == 'video';
    _init();
  }

  @override
  void dispose() {
    _ringTimeout?.cancel();
    _duration.stop();
    _callDocSub?.cancel();

    _stopSounds();
    _disposeRenderers();

    AudioModeUtil.setInCallMode(false);
    _signaling?.hangUp();

    super.dispose();
  }

  Future<void> _init() async {
    await _localRenderer.initialize();
    await _remoteRenderer.initialize();

    final mic = await Permission.microphone.request();
    final cam = _isVideo ? await Permission.camera.request() : PermissionStatus.granted;
    _permissionsGranted = mic.isGranted && cam.isGranted;

    if (!mounted) return;
    if (!_permissionsGranted) {
      setState(() {});
      return;
    }

    await AudioModeUtil.setInCallMode(true);

    _signaling = Signaling(
      myId: widget.myId,
      peerId: widget.peerId,
      docId: widget.docId,
      isCaller: widget.isCaller,
      callType: widget.callType,
      onAddRemoteStream: (MediaStream stream) {
        if (!mounted) return;
        _remoteRenderer.srcObject = stream;
        setState(() {
          _connected = true;
          if (!_duration.isRunning) _duration.start();
        });
        _stopSounds();
      },
      onRemoveRemoteStream: () {
        if (!mounted) return;
        _remoteRenderer.srcObject = null;
        setState(() => _connected = false);
      },
    );

    // 1) сначала локальный стрим и превью
    await _signaling!.initRenderers(_localRenderer);

    // 2) затем старт роли
    await _ensureSignalingStart();

    // 3) тоны + таймаут
    if (widget.isCaller) {
      await _playRingback();
      _ringTimeout = Timer(const Duration(seconds: 60), () {
        if (!_connected && mounted) {
          ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Таймаут вызова')));
          _endCall();
        }
      });
    } else {
      await _playRingtone();
      try { await AndroidCallUtil.simulateIncomingCall(widget.docId); } catch (_) {}
    }

    // 4) слежение за call-документом
    _callDocSub = FirebaseFirestore.instance
        .collection('calls')
        .doc(widget.docId)
        .snapshots()
        .listen((doc) {
      if (!doc.exists) return;
      final data = doc.data()! as Map<String, dynamic>;
      final calleeStatus = data['calleeStatus'] as String?;
      final callerStatus = data['callerStatus'] as String?;

      if (!_connected && (calleeStatus == 'accepted' || callerStatus == 'accepted')) {
        setState(() {
          _connected = true;
          if (!_duration.isRunning) _duration.start();
        });
        _stopSounds();
      }

      if ((calleeStatus == 'declined' || calleeStatus == 'ended' || callerStatus == 'ended') && !_ended) {
        _endCall();
      }
    });
  }

  Future<void> _ensureSignalingStart() async {
    if (_started || _signaling == null) return;
    _started = true;
    try {
      if (widget.isCaller) {
        await _signaling!.startCall();
      } else {
        await _signaling!.waitForCall();
      }
    } catch (e) {
      // Повторная попытка
      try {
        if (widget.isCaller) {
          await _signaling!.startCall();
        } else {
          await _signaling!.waitForCall();
        }
      } catch (e2) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Ошибка запуска звонка: $e2')));
        }
      }
    }
  }

  void _disposeRenderers() {
    try {
      _localRenderer.srcObject = null;
      _remoteRenderer.srcObject = null;
      _localRenderer.dispose();
      _remoteRenderer.dispose();
    } catch (_) {}
  }

  // ---------- ringtones / ringback (совместимо с audioplayers 6.5.0) ----------

  Future<void> _configureRingtoneContext() async {
    try {
      await _ringtonePlayer.setAudioContext(
        ap.AudioContext(
          iOS: ap.AudioContextIOS(
            category: ap.AVAudioSessionCategory.playback,
            options: {ap.AVAudioSessionOptions.mixWithOthers}, // Set<...>
          ),
          android: ap.AudioContextAndroid(
            isSpeakerphoneOn: true,
            stayAwake: false,
            // В вашей версии отсутствуют enum’ы contentType/usageType — опускаем.
            audioFocus: ap.AndroidAudioFocus.gainTransientMayDuck,
          ),
        ),
      );
    } catch (_) {}
  }

  Future<void> _configureRingbackContext() async {
    try {
      await _ringPlayer.setAudioContext(
        ap.AudioContext(
          iOS: ap.AudioContextIOS(
            category: ap.AVAudioSessionCategory.playback,
            options: {ap.AVAudioSessionOptions.mixWithOthers},
          ),
          android: ap.AudioContextAndroid(
            isSpeakerphoneOn: true,
            stayAwake: true,
            // Без contentType/usageType для совместимости
            audioFocus: ap.AndroidAudioFocus.gainTransientMayDuck,
          ),
        ),
      );
    } catch (_) {}
  }

  Future<void> _playRingback() async {
    try {
      await _configureRingbackContext();
      await _ringPlayer.setReleaseMode(ReleaseMode.loop);
      await _ringPlayer.play(AssetSource('audio/ringback.mp3'), volume: 1.0);
    } catch (_) {}
  }

  Future<void> _playRingtone() async {
    try {
      await _configureRingtoneContext();
      await _ringtonePlayer.setReleaseMode(ReleaseMode.loop);
      // В вашем pubspec — ringing.mp3
      await _ringtonePlayer.play(AssetSource('audio/ringing.mp3'), volume: 1.0);
    } catch (_) {}
  }

  Future<void> _stopSounds() async {
    try { await _ringPlayer.stop(); } catch (_) {}
    try { await _ringtonePlayer.stop(); } catch (_) {}
  }

  // ---------- controls ----------

  void _endCall() async {
    if (_ended) return;
    setState(() => _ended = true);
    _duration.stop();
    await _stopSounds();

    try {
      final doc = FirebaseFirestore.instance.collection('calls').doc(widget.docId);
      await doc.update({ widget.isCaller ? 'callerStatus' : 'calleeStatus': 'ended' });
    } catch (_) {}

    _signaling?.hangUp();

    if (mounted) Navigator.pop(context);
  }

  Future<void> _toggleMic() async {
    setState(() => _micOn = !_micOn);
    try {
      final stream = _localRenderer.srcObject;
      if (stream != null) {
        for (final t in stream.getAudioTracks()) {
          t.enabled = _micOn;
        }
      }
    } catch (_) {}
  }

  Future<void> _toggleCam() async {
    setState(() => _camOn = !_camOn);
    try {
      final stream = _localRenderer.srcObject;
      if (stream != null) {
        for (final t in stream.getVideoTracks()) {
          t.enabled = _camOn;
        }
      }
    } catch (_) {}
  }

  Future<void> _switchCam() async {
    try { await _signaling?.switchCamera(); } catch (_) {}
  }

  Future<void> _toggleSpeaker() async {
    setState(() => _speakerOn = !_speakerOn);
    try {
      await _signaling?.setSpeakerphoneOn(_speakerOn);
    } catch (_) {}
  }

  String _format(Duration d) {
    final mm = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final ss = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    final hh = d.inHours;
    return hh > 0 ? '$hh:$mm:$ss' : '$mm:$ss';
  }

  @override
  Widget build(BuildContext context) {
    if (!_permissionsGranted) {
      return PermissionGate(
        message: 'Требуются разрешения на микрофон/камеру. Разрешите в системных настройках и повторите.',
        onRetry: () async {
          final mic = await Permission.microphone.request();
          final cam = _isVideo ? await Permission.camera.request() : PermissionStatus.granted;
          setState(() => _permissionsGranted = mic.isGranted && cam.isGranted);
          if (_permissionsGranted) _init();
        },
      );
    }

    final header = SafeArea(
      bottom: false,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12.0, vertical: 8),
        child: Row(
          children: [
            IconButton(
              tooltip: MaterialLocalizations.of(context).backButtonTooltip,
              onPressed: () async {
                final res = await showDialog<bool>(
                  context: context,
                  builder: (_) => AlertDialog(
                    title: const Text('Завершить вызов?'),
                    content: const Text('Вы уверены, что хотите завершить текущий вызов?'),
                    actions: [
                      TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
                      FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Завершить')),
                    ],
                  ),
                );
                if (res == true) _endCall();
              },
              icon: const Icon(Icons.arrow_back),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(widget.peerUsername, style: Theme.of(context).textTheme.titleMedium, overflow: TextOverflow.ellipsis),
                const SizedBox(height: 2),
                Text(_connected ? _format(_duration.elapsed) : (widget.isCaller ? 'Звонок…' : 'Соединение…'),
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant)),
              ]),
            ),
            if (_isVideo)
              IconButton(
                tooltip: 'Минимизировать',
                onPressed: () {/* PiP (опционально) */},
                icon: const Icon(Icons.picture_in_picture_alt_outlined),
              ),
          ],
        ),
      ),
    );

    final controls = SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceEvenly,
          children: [
            CallActionButton(
              icon: _micOn ? Icons.mic : Icons.mic_off,
              active: _micOn,
              semanticsLabel: 'Микрофон',
              onPressed: _toggleMic,
            ),
            if (_isVideo)
              CallActionButton(
                icon: _camOn ? Icons.videocam : Icons.videocam_off,
                active: _camOn,
                semanticsLabel: 'Камера',
                onPressed: _toggleCam,
              ),
            if (_isVideo)
              CallActionButton(
                icon: Icons.cameraswitch,
                semanticsLabel: 'Переключить камеру',
                onPressed: _switchCam,
              ),
            CallActionButton(
              icon: _speakerOn ? Icons.volume_up : Icons.hearing,
              active: _speakerOn,
              semanticsLabel: 'Динамик',
              onPressed: _toggleSpeaker,
            ),
            CallActionButton(
              icon: Icons.call_end,
              semanticsLabel: 'Завершить вызов',
              destructive: true,
              onPressed: _endCall,
            ),
          ],
        ),
      ),
    );

    if (_isVideo) {
      return Scaffold(
        backgroundColor: Colors.black,
        body: Stack(
          children: [
            Positioned.fill(
              child: _remoteRenderer.srcObject != null
                  ? RTCVideoView(
                      _remoteRenderer,
                      objectFit: RTCVideoViewObjectFit.RTCVideoViewObjectFitCover,
                    )
                  : const ColoredBox(color: Colors.black),
            ),
            Positioned(top: 0, left: 0, right: 0, child: header),
            Positioned(
              right: 16,
              bottom: 112,
              child: Draggable(
                feedback: _buildLocalPreview(),
                childWhenDragging: const SizedBox.shrink(),
                onDragEnd: (_) {},
                child: _buildLocalPreview(),
              ),
            ),
            Positioned(left: 0, right: 0, bottom: 0, child: controls),
          ],
        ),
      );
    } else {
      return Scaffold(
        body: Column(
          children: [
            header,
            const Spacer(),
            CircleAvatar(
              radius: 48,
              child: Text(
                widget.peerUsername.isNotEmpty ? widget.peerUsername[0].toUpperCase() : '?',
                style: const TextStyle(fontSize: 36),
              ),
            ),
            const SizedBox(height: 16),
            Text(widget.peerUsername, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Text(widget.isCaller ? 'Аудиозвонок' : 'Соединение…', style: Theme.of(context).textTheme.bodyMedium),
            const Spacer(),
            controls,
          ],
        ),
      );
    }
  }

  Widget _buildLocalPreview() {
    return Container(
      width: 140,
      height: 220,
      decoration: BoxDecoration(
        color: Colors.black,
        borderRadius: BorderRadius.circular(16),
        boxShadow: const [BoxShadow(color: Colors.black26, blurRadius: 12)],
      ),
      clipBehavior: Clip.antiAlias,
      child: _localRenderer.srcObject != null
          ? RTCVideoView(
              _localRenderer,
              mirror: true,
              objectFit: RTCVideoViewObjectFit.RTCVideoViewObjectFitCover,
            )
          : const Center(child: Icon(Icons.videocam, color: Colors.white70)),
    );
  }
}
