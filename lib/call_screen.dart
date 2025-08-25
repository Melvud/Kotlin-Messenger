import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'signaling.dart';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:audioplayers/audioplayers.dart';

class CallScreen extends StatefulWidget {
  final bool isCaller;
  final String peerId;
  final String peerUsername;
  final String myId;
  final String myUsername;
  final String docId;
  final String callType;

  const CallScreen({
    required this.isCaller,
    required this.peerId,
    required this.peerUsername,
    required this.myId,
    required this.myUsername,
    required this.docId,
    required this.callType,
    super.key,
  });

  @override
  State<CallScreen> createState() => _CallScreenState();
}

class _CallScreenState extends State<CallScreen> {
  final RTCVideoRenderer _localRenderer = RTCVideoRenderer();
  final RTCVideoRenderer _remoteRenderer = RTCVideoRenderer();
  Signaling? signaling;
  StreamSubscription<DocumentSnapshot>? _peerCallSub;
  StreamSubscription<DocumentSnapshot>? _callTimeSub;
  bool _permissionsGranted = false;
  bool _micOn = true;
  bool _camOn = true;
  bool _speakerOn = false;
  bool _callEnded = false;
  bool _connecting = true;
  Timer? _timeoutTimer;

  final AudioPlayer _ringbackPlayer = AudioPlayer();
  bool _isPlayingRingback = false;

  DateTime? _callStartTime;
  Timer? _callTimer;
  Duration _callDuration = Duration.zero;

  bool _isSwapped = false;

  @override
  void initState() {
    super.initState();
    _initCallScreen();
    _listenPeerCallStatus();
    _timeoutTimer = Timer(const Duration(seconds: 60), _onCallTimeout);
    if (widget.isCaller) {
      _playRingback();
    }
  }

  void _onCallTimeout() {
    if (!_callEnded && _connecting) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Время ожидания истекло, звонок завершен.')),
      );
      _hangUp();
    }
  }

  Future<void> _playRingback() async {
    _isPlayingRingback = true;
    await _ringbackPlayer.setAudioContext(AudioContext(
      android: AudioContextAndroid(
        isSpeakerphoneOn: true,
        stayAwake: true,
        contentType: AndroidContentType.music,
        usageType: AndroidUsageType.media,
        audioFocus: AndroidAudioFocus.gain,
      ),
      iOS: AudioContextIOS(
        category: AVAudioSessionCategory.playback,
        options: {AVAudioSessionOptions.defaultToSpeaker},
      ),
    ));
    await _ringbackPlayer.setReleaseMode(ReleaseMode.loop);
    await _ringbackPlayer.play(AssetSource('sounds/ringback.mp3'));
  }

  Future<void> _stopRingback() async {
    if (_isPlayingRingback) {
      await _ringbackPlayer.stop();
      _isPlayingRingback = false;
    }
  }

  Future<void> _initCallScreen() async {
    try {
      final mic = await Permission.microphone.request();
      final cam = widget.callType == "video"
          ? await Permission.camera.request()
          : PermissionStatus.granted;
      if (mic.isGranted && cam.isGranted) {
        _permissionsGranted = true;
        _speakerOn = widget.callType == 'video';
        await _localRenderer.initialize();
        await _remoteRenderer.initialize();
        if (!widget.isCaller) {
          final callDoc = await FirebaseFirestore.instance.collection('calls').doc(widget.docId).get();
          final currentStatus = callDoc.data()?['calleeStatus'];
          if (currentStatus != 'accepted') {
            await FirebaseFirestore.instance
                .collection('calls')
                .doc(widget.docId)
                .update({'calleeStatus': 'accepted'});
            await Future.delayed(const Duration(milliseconds: 300));
          }
        }
        signaling = Signaling(
          myId: widget.myId,
          peerId: widget.peerId,
          docId: widget.docId,
          isCaller: widget.isCaller,
          onAddRemoteStream: (stream) {
            if (mounted) {
              _stopRingback();
              setState(() {
                _remoteRenderer.srcObject = stream;
                _connecting = false;
              });
              _onConnected();
            }
          },
          onRemoveRemoteStream: () {
            if (mounted) setState(() {
              _remoteRenderer.srcObject = null;
            });
          },
          callType: widget.callType,
        );
        try {
          await signaling!.initRenderers(_localRenderer);
          await signaling!.setSpeakerphoneOn(_speakerOn);
          if (!widget.isCaller) {
            await Future.delayed(const Duration(milliseconds: 300));
          }
          if (widget.isCaller) {
            await signaling!.startCall();
          } else {
            await signaling!.waitForCall();
          }
          setState(() {});
        } catch (e, st) {
          debugPrint('Ошибка инициализации звонка: $e\n$st');
          if (mounted) {
            setState(() {
              _permissionsGranted = false;
            });
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(content: Text('Ошибка инициализации звонка: $e')),
            );
          }
        }
      } else {
        setState(() => _permissionsGranted = false);
      }
    } catch (e, st) {
      debugPrint('Ошибка инициализации call screen: $e\n$st');
      setState(() => _permissionsGranted = false);
    }
  }

  void _onConnected() async {
    if (_callStartTime != null) return;
    if (widget.isCaller) {
      await FirebaseFirestore.instance
          .collection('calls')
          .doc(widget.docId)
          .update({'callStartTimestamp': FieldValue.serverTimestamp()});
    }
    _callTimeSub?.cancel();
    _callTimeSub = FirebaseFirestore.instance
        .collection('calls')
        .doc(widget.docId)
        .snapshots()
        .listen((doc) {
      final data = doc.data();
      if (data != null && data['callStartTimestamp'] != null) {
        final ts = data['callStartTimestamp'];
        DateTime tsDate;
        if (ts is Timestamp) {
          tsDate = ts.toDate();
        } else if (ts is DateTime) {
          tsDate = ts;
        } else {
          return;
        }
        if (_callStartTime == null) {
          setState(() {
            _callStartTime = tsDate;
          });
          _startCallTimer();
        }
      }
    });
  }

  void _startCallTimer() {
    _callTimer?.cancel();
    _callTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (_callStartTime != null) {
        setState(() {
          _callDuration = DateTime.now().difference(_callStartTime!);
        });
      }
    });
  }

  void _stopCallTimer() {
    _callTimer?.cancel();
    _callTimeSub?.cancel();
  }

  void _listenPeerCallStatus() {
    _peerCallSub?.cancel();
    _peerCallSub = FirebaseFirestore.instance
        .collection('calls')
        .doc(widget.docId)
        .snapshots()
        .listen((doc) {
      if (!doc.exists) return;
      final data = doc.data();
      if (data == null) return;

      final peerStatus = widget.isCaller ? data['calleeStatus'] : data['callerStatus'];
      if ((peerStatus == 'rejected' || peerStatus == 'ended') && !_callEnded) {
        _stopRingback();
        _callEnded = true;
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(peerStatus == 'rejected'
                ? 'Собеседник отклонил вызов'
                : 'Собеседник завершил вызов')),
          );
          Navigator.pop(context);
        }
      }
      if (widget.isCaller && peerStatus == 'accepted') {
        _stopRingback();
      }
    });
  }

  @override
  void dispose() {
    _timeoutTimer?.cancel();
    _stopRingback();
    _ringbackPlayer.dispose();
    _peerCallSub?.cancel();
    _callTimeSub?.cancel();
    _localRenderer.srcObject = null;
    _remoteRenderer.srcObject = null;
    _localRenderer.dispose();
    _remoteRenderer.dispose();
    signaling?.dispose();
    _stopCallTimer();
    super.dispose();
  }

  void _toggleMic() {
    if (signaling?.localStream != null) {
      final audioTracks = signaling!.localStream!.getAudioTracks();
      if (audioTracks.isNotEmpty) {
        final audioTrack = audioTracks.first;
        audioTrack.enabled = !_micOn;
        setState(() => _micOn = !_micOn);
      }
    }
  }

  void _toggleCam() {
    if (signaling?.localStream != null) {
      final videoTracks = signaling!.localStream!.getVideoTracks();
      if (videoTracks.isNotEmpty) {
        final videoTrack = videoTracks.first;
        videoTrack.enabled = !_camOn;
        setState(() => _camOn = !_camOn);
      }
    }
  }

  void _toggleSpeaker() async {
    setState(() => _speakerOn = !_speakerOn);
    await signaling?.setSpeakerphoneOn(_speakerOn);
  }

  void _switchCamera() async {
    await signaling?.switchCamera();
  }

  Future<void> _hangUp() async {
    if (_callEnded) return;
    _callEnded = true;
    await _stopRingback();
    final field = widget.isCaller ? 'callerStatus' : 'calleeStatus';
    await FirebaseFirestore.instance.collection('calls').doc(widget.docId).update({field: 'ended'});
    await signaling?.hangUp();
    if (mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    final isVideo = widget.callType == "video";
    if (!_permissionsGranted) {
      return Scaffold(
        appBar: AppBar(title: const Text('Call')),
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Text('Необходимо разрешение на микрофон и камеру.'),
              const SizedBox(height: 12),
              ElevatedButton(
                onPressed: _initCallScreen,
                child: const Text('Повторить запрос разрешений'),
              ),
            ],
          ),
        ),
      );
    }
    return Scaffold(
      backgroundColor: const Color(0xFF202b3a),
      body: SafeArea(
        bottom: false,
        child: Stack(
          children: [
            if (isVideo)
              _buildVideoLayout()
            else
              _buildAudioLayout(),

            if (_callStartTime != null)
              Positioned(
                left: 0,
                right: 0,
                top: 42,
                child: Center(
                  child: Text(
                    _formatDuration(_callDuration),
                    style: const TextStyle(
                        color: Colors.white70,
                        fontSize: 20,
                        fontWeight: FontWeight.bold),
                  ),
                ),
              ),

            // Кнопки
            Positioned(
              left: 0,
              right: 0,
              bottom: 0,
              child: Padding(
                padding: EdgeInsets.only(
                  bottom: MediaQuery.of(context).viewInsets.bottom +
                      MediaQuery.of(context).padding.bottom +
                      (isVideo ? 32 : 48),
                  top: isVideo ? 0 : 16,
                ),
                child: isVideo
                    ? _buildVideoCallButtons()
                    : _buildAudioCallButtons(),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildAudioCallButtons() {
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        _circleIcon(
          icon: _micOn ? Icons.mic : Icons.mic_off,
          color: Colors.white,
          bg: Colors.blue,
          onTap: _toggleMic,
        ),
        _circleIcon(
          icon: _speakerOn ? Icons.volume_up : Icons.hearing,
          color: Colors.white,
          bg: _speakerOn ? Colors.orange : Colors.blueGrey,
          onTap: _toggleSpeaker,
        ),
        _circleIcon(
          icon: Icons.call_end,
          color: Colors.white,
          bg: Colors.red,
          onTap: _hangUp,
        ),
      ],
    );
  }

  Widget _buildVideoCallButtons() {
    // Верхний ряд: микрофон, камера, смена камеры, динамик
    // Нижний ряд: только call_end по центру
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            _circleIcon(
              icon: _micOn ? Icons.mic : Icons.mic_off,
              color: Colors.white,
              bg: Colors.blue,
              onTap: _toggleMic,
            ),
            _circleIcon(
              icon: _camOn ? Icons.videocam : Icons.videocam_off,
              color: Colors.white,
              bg: Colors.blue,
              onTap: _toggleCam,
            ),
            _circleIcon(
              icon: Icons.cameraswitch,
              color: Colors.white,
              bg: Colors.blueGrey,
              onTap: _switchCamera,
            ),
            _circleIcon(
              icon: _speakerOn ? Icons.volume_up : Icons.hearing,
              color: Colors.white,
              bg: _speakerOn ? Colors.orange : Colors.blueGrey,
              onTap: _toggleSpeaker,
            ),
          ],
        ),
        const SizedBox(height: 18),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            _circleIcon(
              icon: Icons.call_end,
              color: Colors.white,
              bg: Colors.red,
              onTap: _hangUp,
              size: 68,
              iconSize: 40,
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildAudioLayout() {
    final isVideo = widget.callType == "video";
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          CircleAvatar(
            radius: 54,
            backgroundColor: Colors.blueGrey.shade300,
            child: Text(
              widget.peerUsername.isNotEmpty ? widget.peerUsername[0].toUpperCase() : '?',
              style: const TextStyle(fontSize: 44, fontWeight: FontWeight.bold, color: Colors.white),
            ),
          ),
          const SizedBox(height: 24),
          Text(
            widget.peerUsername,
            style: const TextStyle(fontSize: 28, fontWeight: FontWeight.bold, color: Colors.white),
          ),
          const SizedBox(height: 4),
          Text(
            isVideo ? "Видеозвонок" : "Аудиозвонок",
            style: const TextStyle(color: Colors.white70, fontSize: 18),
          ),
          if (_connecting) ...[
            const SizedBox(height: 24),
            const CircularProgressIndicator(),
            const SizedBox(height: 8),
            const Text('Устанавливаем соединение...', style: TextStyle(color: Colors.white70)),
          ],
        ],
      ),
    );
  }

  Widget _buildVideoLayout() {
    return Stack(
      children: [
        Positioned.fill(
          child: _connecting
              ? const Center(child: CircularProgressIndicator())
              : GestureDetector(
                  onTap: () {},
                  child: RTCVideoView(
                    _isSwapped ? _localRenderer : _remoteRenderer,
                    objectFit: RTCVideoViewObjectFit.RTCVideoViewObjectFitCover,
                  ),
                ),
        ),
        Positioned(
          right: 16,
          top: 16,
          child: GestureDetector(
            onTap: () {
              setState(() {
                _isSwapped = !_isSwapped;
              });
            },
            child: SizedBox(
              width: 110,
              height: 180,
              child: RTCVideoView(
                _isSwapped ? _remoteRenderer : _localRenderer,
                mirror: true,
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _circleIcon({
    required IconData icon,
    required Color color,
    required Color bg,
    required VoidCallback onTap,
    double size = 56,
    double iconSize = 34,
  }) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 18),
      child: Ink(
        decoration: ShapeDecoration(
          color: bg,
          shape: const CircleBorder(),
        ),
        child: IconButton(
          icon: Icon(icon, size: iconSize, color: color),
          onPressed: onTap,
          iconSize: size,
        ),
      ),
    );
  }

  String _formatDuration(Duration d) {
    String two(int n) => n.toString().padLeft(2, '0');
    final h = d.inHours;
    final m = d.inMinutes % 60;
    final s = d.inSeconds % 60;
    return h > 0
        ? "${two(h)}:${two(m)}:${two(s)}"
        : "${two(m)}:${two(s)}";
  }
}