import 'dart:async';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'audio_mode_util.dart';

class Signaling {
  final String myId;
  final String peerId;
  final String docId;
  final bool isCaller;
  final void Function(MediaStream stream) onAddRemoteStream;
  final void Function() onRemoveRemoteStream;
  final String callType; // 'audio' or 'video'

  RTCPeerConnection? _pc;
  MediaStream? _localStream;
  late final CollectionReference _callsRef;
  late final CollectionReference _candidatesRef;
  StreamSubscription? _callSub;
  StreamSubscription? _candidatesSub;
  bool _callAccepted = false;
  bool _speakerOn = false;
  bool _remoteDescriptionSet = false;

  final Set<String> _seenCandidateDocIds = {};
  bool _iceRestartInProgress = false;

  MediaStream? get localStream => _localStream;

  Signaling({
    required this.myId,
    required this.peerId,
    required this.docId,
    required this.isCaller,
    required this.onAddRemoteStream,
    required this.onRemoveRemoteStream,
    required this.callType,
  }) {
    _callsRef = FirebaseFirestore.instance.collection('calls');
    _candidatesRef = _callsRef.doc(docId).collection('candidates');
  }

  Future<void> initRenderers(RTCVideoRenderer localRenderer) async {
    await AudioModeUtil.setInCallMode(true);

    final mediaConstraints = {
      'audio': {
        'echoCancellation': true,
        'noiseSuppression': true,
        'autoGainControl': true,
        'sampleRate': 48000,
        'channelCount': 1,
      },
      'video': callType == 'video'
          ? {
              'facingMode': 'user',
              'width': {'ideal': 1280},
              'height': {'ideal': 720},
              'frameRate': {'ideal': 30},
            }
          : false,
    };
    _localStream = await navigator.mediaDevices.getUserMedia(mediaConstraints);
    for (var track in _localStream!.getAudioTracks()) {
      track.enabled = true;
    }
    localRenderer.srcObject = _localStream;

    await _setSpeakerphoneOn(callType == 'video');
  }

  Future<void> startCall() async {
    await _createPeerConnection();
    _listenCandidates();
    await _callSub?.cancel();
    _callSub = _callsRef.doc(docId).snapshots().listen((doc) async {
      if (!doc.exists) return;
      final data = doc.data() as Map<String, dynamic>;
      if (data['calleeStatus'] == 'accepted' && !_callAccepted) {
        await _sendOffer(iceRestart: false);
        _callAccepted = true;
      } else if (data['type'] == 'answer' &&
          data['callerStatus'] == 'accepted' &&
          _pc?.signalingState == RTCSignalingState.RTCSignalingStateHaveLocalOffer) {
        await _pc!.setRemoteDescription(RTCSessionDescription(data['sdp'], 'answer'));
        _remoteDescriptionSet = true;
      }
    });
  }

  Future<void> waitForCall() async {
    await _callSub?.cancel();
    await _createPeerConnection();
    _listenCandidates();
    _callSub = _callsRef.doc(docId).snapshots().listen((doc) async {
      if (!doc.exists) return;
      final data = doc.data() as Map<String, dynamic>;
      if (data['type'] == 'offer' && data['calleeStatus'] == 'accepted' && !_callAccepted) {
        await _pc!.setRemoteDescription(RTCSessionDescription(data['sdp'], 'offer'));
        _remoteDescriptionSet = true;

        final answer = await _pc!.createAnswer();
        await _pc!.setLocalDescription(answer);
        await _callsRef.doc(docId).set({
          'type': 'answer',
          'sdp': answer.sdp,
          'callerStatus': 'accepted',
          'calleeStatus': 'accepted',
          'callType': callType,
        }, SetOptions(merge: true));
        _callAccepted = true;
      }
    });
  }

  Future<void> _createPeerConnection() async {
    if (_pc != null) return;

    final config = {
      'iceServers': [
        {
          'urls': [
            'turn:213.109.204.225:3478?transport=udp',
            'turn:213.109.204.225:3478?transport=tcp',
            'turn:213.109.204.225:443?transport=udp',
            'turn:213.109.204.225:443?transport=tcp',
            'turn:213.109.204.225:80?transport=udp',
            'turn:213.109.204.225:80?transport=tcp'
          ],
          'username': 'melvud',
          'credential': 'berkut14'
        }
      ]
    };

    _pc = await createPeerConnection(config);

    if (_localStream != null) {
      for (var track in _localStream!.getTracks()) {
        await _pc!.addTrack(track, _localStream!);
      }
    }

    _pc!.onTrack = (event) {
      if (event.streams.isNotEmpty) {
        onAddRemoteStream(event.streams[0]);
      }
    };

    _pc!.onRemoveStream = (stream) {
      onRemoveRemoteStream();
    };

    _pc!.onIceCandidate = (candidate) async {
      if (candidate.candidate != null) {
        await _candidatesRef.add({
          'candidate': candidate.candidate,
          'sdpMid': candidate.sdpMid,
          'sdpMLineIndex': candidate.sdpMLineIndex,
          'from': myId,
          'timestamp': FieldValue.serverTimestamp(),
        });
      }
    };

    _pc!.onIceConnectionState = (state) async {
      if (state == RTCIceConnectionState.RTCIceConnectionStateFailed ||
          state == RTCIceConnectionState.RTCIceConnectionStateDisconnected) {
        await _restartIce();
      }
    };
  }

  void _listenCandidates() {
    _candidatesSub?.cancel();
    _candidatesSub = _candidatesRef
        .orderBy('timestamp')
        .snapshots()
        .listen((snap) async {
      for (var change in snap.docChanges) {
        final doc = change.doc;
        if (_seenCandidateDocIds.contains(doc.id)) continue;
        _seenCandidateDocIds.add(doc.id);

        final data = doc.data();
        if (data == null) continue;
        final mData = data as Map<String, dynamic>;
        if (mData['from'] == myId) continue;
        final candidate = RTCIceCandidate(
          mData['candidate'],
          mData['sdpMid'],
          mData['sdpMLineIndex'],
        );
        if (_remoteDescriptionSet) {
          try { await _pc?.addCandidate(candidate); } catch (_) {}
        } else {
          Future<void>.delayed(const Duration(milliseconds: 100), () async {
            if (_remoteDescriptionSet) {
              try { await _pc?.addCandidate(candidate); } catch (_) {}
            }
          });
        }
      }
    });
  }

  Future<void> _sendOffer({required bool iceRestart}) async {
    if (_pc == null) return;
    _iceRestartInProgress = iceRestart;

    // В вашей версии flutter_webrtc используем Map-опции
    final offer = await _pc!.createOffer({
      'iceRestart': iceRestart,
      // В большинстве версий addTrack уже определяет направления; эти флаги не обязательны.
      // 'offerToReceiveAudio': 1,
      // 'offerToReceiveVideo': callType == 'video' ? 1 : 0,
    });

    await _pc!.setLocalDescription(offer);
    await _callsRef.doc(docId).set({
      'type': 'offer',
      'from': myId,
      'to': peerId,
      'sdp': offer.sdp,
      'callerStatus': 'calling',
      'calleeStatus': 'accepted',
      'callType': callType,
    }, SetOptions(merge: true));

    _iceRestartInProgress = false;
  }

  Future<void> _restartIce() async {
    if (_pc == null || _iceRestartInProgress) return;
    try {
      if (isCaller) {
        await _sendOffer(iceRestart: true);
      } else {
        // для callee дождёмся нового offer от caller
      }
    } catch (_) {}
  }

  Future<void> hangUp() async {
    try {
      final field = isCaller ? 'callerStatus' : 'calleeStatus';
      await _callsRef.doc(docId).update({field: 'ended'});
    } catch (_) {}
    await _callSub?.cancel();
    await _candidatesSub?.cancel();
    try { await _pc?.close(); } catch (_) {}
    _pc = null;
    try { await _localStream?.dispose(); } catch (_) {}
    _localStream = null;
    _remoteDescriptionSet = false;
    _seenCandidateDocIds.clear();
    await _setSpeakerphoneOn(false);
    await AudioModeUtil.setInCallMode(false);
  }

  void dispose() {
    hangUp();
  }

  Future<void> setSpeakerphoneOn(bool on) async {
    _speakerOn = on;
    await _setSpeakerphoneOn(on);
  }

  Future<void> _setSpeakerphoneOn(bool on) async {
    try { await Helper.setSpeakerphoneOn(on); } catch (_) {}
  }

  Future<void> switchCamera() async {
    if (_localStream != null) {
      final videoTracks = _localStream!.getVideoTracks();
      if (videoTracks.isNotEmpty) {
        await Helper.switchCamera(videoTracks[0]);
      }
    }
  }
}
