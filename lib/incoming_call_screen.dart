import 'package:flutter/material.dart';
import 'call_screen.dart';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:audioplayers/audioplayers.dart';

class IncomingCallScreen extends StatefulWidget {
  final String myId;
  final String myUsername;
  final String peerId;
  final String peerUsername;
  final String docId;
  final String callType;
  const IncomingCallScreen({
    required this.myId,
    required this.myUsername,
    required this.peerId,
    required this.peerUsername,
    required this.docId,
    required this.callType,
    super.key,
  });

  @override
  State<IncomingCallScreen> createState() => _IncomingCallScreenState();
}

class _IncomingCallScreenState extends State<IncomingCallScreen> {
  final AudioPlayer _ringPlayer = AudioPlayer();
  bool _isPlaying = false;

  @override
  void initState() {
    super.initState();
    _playRinging();
  }

  Future<void> _playRinging() async {
    _isPlaying = true;
    await _ringPlayer.setAudioContext(const AudioContext(
      android: AudioContextAndroid(
        isSpeakerphoneOn: true,
        stayAwake: true,
        contentType: AndroidContentType.music, // <--- важно!
        usageType: AndroidUsageType.media, // <--- важно!
        audioFocus: AndroidAudioFocus.gain, // <--- важно!
      ),
      iOS: AudioContextIOS(
        category: AVAudioSessionCategory.playback,
        options: [AVAudioSessionOptions.defaultToSpeaker],
      ),
    ));
    await _ringPlayer.setReleaseMode(ReleaseMode.loop);
    await _ringPlayer.play(AssetSource('sounds/ringing.mp3'));
  }

  Future<void> _stopRinging() async {
    if (_isPlaying) {
      await _ringPlayer.stop();
      _isPlaying = false;
    }
  }

  @override
  void dispose() {
    _stopRinging();
    _ringPlayer.dispose();
    super.dispose();
  }

  Future<void> _acceptCall(BuildContext context) async {
    await _stopRinging();
    try {
      await FirebaseFirestore.instance.collection('calls').doc(widget.docId).update({
        'calleeStatus': 'accepted'
      });
      Navigator.pushReplacement(
        context,
        MaterialPageRoute(builder: (_) => CallScreen(
          isCaller: false,
          peerId: widget.peerId,
          peerUsername: widget.peerUsername,
          myId: widget.myId,
          myUsername: widget.myUsername,
          docId: widget.docId,
          callType: widget.callType,
        )),
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Ошибка при принятии вызова: $e')),
      );
    }
  }

  Future<void> _rejectCall(BuildContext context) async {
    await _stopRinging();
    try {
      await FirebaseFirestore.instance.collection('calls').doc(widget.docId).update({
        'calleeStatus': 'rejected'
      });
      Navigator.pop(context);
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Ошибка при отклонении вызова: $e')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final isVideo = widget.callType == "video";
    return Scaffold(
      backgroundColor: const Color(0xFF202b3a),
      body: SafeArea(
        child: Center(
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
              const SizedBox(height: 40),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  _callButton(
                    context,
                    icon: Icons.call_end,
                    color: Colors.red,
                    label: "Отклонить",
                    onTap: () => _rejectCall(context),
                  ),
                  const SizedBox(width: 60),
                  _callButton(
                    context,
                    icon: isVideo ? Icons.videocam : Icons.call,
                    color: Colors.green,
                    label: "Принять",
                    onTap: () => _acceptCall(context),
                  ),
                ],
              )
            ],
          ),
        ),
      ),
    );
  }

  Widget _callButton(BuildContext context,
      {required IconData icon,
      required Color color,
      required String label,
      required VoidCallback onTap}) {
    return Column(
      children: [
        Ink(
          decoration: ShapeDecoration(
            color: color,
            shape: const CircleBorder(),
          ),
          child: IconButton(
            icon: Icon(icon, size: 38, color: Colors.white),
            onPressed: onTap,
          ),
        ),
        const SizedBox(height: 6),
        Text(label, style: const TextStyle(color: Colors.white)),
      ],
    );
  }
}