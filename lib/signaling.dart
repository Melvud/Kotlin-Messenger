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
  List<RTCIceCandidate> _pendingCandidates = [];

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
    final mediaConstraints = {
      'audio': {
      },
      'video': callType == 'video'
          ? {
              'facingMode': 'user',
              'width': {'ideal': 640},
              'height': {'ideal': 480},
              'frameRate': {'ideal': 30},
            }
          : false,
    };
    _localStream = await navigator.mediaDevices.getUserMedia(mediaConstraints);
    localRenderer.srcObject = _localStream;

    await AudioModeUtil.setInCallMode(true); // <--- Включаем режим звонка ОС Android
    await _setSpeakerphoneOn(callType == 'video');
  }

  Future<void> startCall() async {
    await _createPeerConnection();
    _listenCandidates();
    _callSub?.cancel();
    _callSub = _callsRef.doc(docId).snapshots().listen((doc) async {
      if (!doc.exists) return;
      final data = doc.data() as Map<String, dynamic>;
      if (data['calleeStatus'] == 'accepted' && !_callAccepted) {
        final offer = await _pc!.createOffer();
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
        _callAccepted = true;
      } else if (data['type'] == 'answer' &&
          data['callerStatus'] == 'accepted' &&
          _pc?.signalingState == RTCSignalingState.RTCSignalingStateHaveLocalOffer) {
        await _pc!.setRemoteDescription(RTCSessionDescription(data['sdp'], 'answer'));
        _remoteDescriptionSet = true;
        for (var c in _pendingCandidates) {
          await _pc!.addCandidate(c);
        }
        _pendingCandidates.clear();
      }
    });
  }

  Future<void> waitForCall() async {
    _callSub?.cancel();
    await _createPeerConnection();
    _listenCandidates();
    _callSub = _callsRef.doc(docId).snapshots().listen((doc) async {
      if (!doc.exists) return;
      final data = doc.data() as Map<String, dynamic>;
      if (data['type'] == 'offer' && data['calleeStatus'] == 'accepted' && !_callAccepted) {
        await _pc!.setRemoteDescription(RTCSessionDescription(data['sdp'], 'offer'));
        _remoteDescriptionSet = true;
        for (var c in _pendingCandidates) {
          await _pc!.addCandidate(c);
        }
        _pendingCandidates.clear();
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
            'turn:51.250.39.40:3478?transport=udp',
            'turn:51.250.39.40:3478?transport=tcp',
            'turn:51.250.39.40:443?transport=udp',
            'turn:51.250.39.40:443?transport=tcp',
            'turn:51.250.39.40:80?transport=udp',
            'turn:51.250.39.40:80?transport=tcp'
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
    _pc!.onTrack = (event) {
      if (event.streams.isNotEmpty) {
        onAddRemoteStream(event.streams[0]);
      }
    };
    _pc!.onRemoveStream = (stream) {
      onRemoveRemoteStream();
    };
  }

  void _listenCandidates() {
    _candidatesSub?.cancel();
    _candidatesSub = _candidatesRef
        .orderBy('timestamp')
        .snapshots()
        .listen((snap) async {
      for (var doc in snap.docChanges) {
        final data = doc.doc.data();
        if (data == null) continue;
        final mData = data as Map<String, dynamic>;
        if (mData['from'] == myId) continue;
        final candidate = RTCIceCandidate(
          mData['candidate'],
          mData['sdpMid'],
          mData['sdpMLineIndex'],
        );
        if (_remoteDescriptionSet) {
          await _pc?.addCandidate(candidate);
        } else {
          _pendingCandidates.add(candidate);
        }
      }
    });
  }

  Future<void> hangUp() async {
    try {
      final field = isCaller ? 'callerStatus' : 'calleeStatus';
      await _callsRef.doc(docId).update({field: 'ended'});
    } catch (_) {}
    await _pc?.close();
    await _localStream?.dispose();
    await _callSub?.cancel();
    await _candidatesSub?.cancel();
    await _setSpeakerphoneOn(false);
    await AudioModeUtil.setInCallMode(false); // <--- Возвращаем режим в NORMAL
  }

  void dispose() {
    hangUp();
  }

  Future<void> setSpeakerphoneOn(bool on) async {
    _speakerOn = on;
    await _setSpeakerphoneOn(on);
  }

  Future<void> _setSpeakerphoneOn(bool on) async {
    try {
      await Helper.setSpeakerphoneOn(on);
    } catch (e) {}
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