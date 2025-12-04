package com.example.messenger_app.data.p2p

import android.content.Context
import android.util.Log
import com.example.messenger_app.data.model.IceCandidateModel
import com.example.messenger_app.data.model.TransferSession
import com.example.messenger_app.data.model.TransferStatus
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.*
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

class FileTransferManager(
    private val context: Context,
    private val firestore: FirebaseFirestore
) {

    private val TAG = "FileTransferManager"
    private val scope = CoroutineScope(Dispatchers.IO)

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    private val _transferStatus = MutableStateFlow(TransferStatus.PENDING)
    val transferStatus: StateFlow<TransferStatus> = _transferStatus.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _currentTransferId = MutableStateFlow<String?>(null)
    val currentTransferId: StateFlow<String?> = _currentTransferId.asStateFlow()

    // Transfer State
    private var activeTransferId: String? = null // Renamed from currentTransferId to avoid conflict
    private var currentChatId: String? = null
    private var outgoingFile: File? = null
    private var incomingFile: File? = null
    private var incomingFileSize: Long = 0
    private var receivedBytes: Long = 0

    init {
        initializePeerConnectionFactory()
    }

    private fun initializePeerConnectionFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
    }

    // ==================== Signaling & Connection ====================

    fun startTransfer(chatId: String, file: File, receiverId: String, senderId: String): String {
        currentChatId = chatId
        outgoingFile = file
        val transferId = UUID.randomUUID().toString()
        activeTransferId = transferId
        _currentTransferId.value = transferId
        _transferStatus.value = TransferStatus.CONNECTING
        _progress.value = 0f

        createPeerConnection()
        createDataChannel()

        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    peerConnection?.setLocalDescription(object : SdpObserverAdapter() {}, it)
                    sendOffer(chatId, transferId, it.description, file.name, file.length(), senderId, receiverId)
                }
            }
        }, MediaConstraints())

        return transferId
    }

    fun receiveTransfer(chatId: String, transferId: String, fileName: String, fileSize: Long) {
        currentChatId = chatId
        activeTransferId = transferId
        _currentTransferId.value = transferId
        incomingFileSize = fileSize
        receivedBytes = 0
        _transferStatus.value = TransferStatus.CONNECTING
        _progress.value = 0f

        // Create temp file
        incomingFile = File(context.cacheDir, "temp_${System.currentTimeMillis()}_$fileName")

        createPeerConnection()

        // Listen for Offer
        firestore.collection("chats").document(chatId)
            .collection("transfers").document(transferId)
            .get()
            .addOnSuccessListener { document ->
                val offerSdp = document.getString("offer")
                if (offerSdp != null) {
                    peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {}, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
                    peerConnection?.createAnswer(object : SdpObserverAdapter() {
                        override fun onCreateSuccess(sdp: SessionDescription?) {
                            sdp?.let {
                                peerConnection?.setLocalDescription(object : SdpObserverAdapter() {}, it)
                                sendAnswer(chatId, transferId, it.description)
                            }
                        }
                    }, MediaConstraints())
                }
            }
        
        listenForCandidates(chatId, transferId, isSender = false)
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnectionObserverAdapter() {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    if (currentChatId != null && activeTransferId != null) {
                        sendIceCandidate(currentChatId!!, activeTransferId!!, it, isSender = outgoingFile != null)
                    }
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE State: $newState")
                if (newState == PeerConnection.IceConnectionState.CONNECTED) {
                    if (outgoingFile != null) {
                        // Sender: Start sending when connected and DataChannel is ready
                    }
                } else if (newState == PeerConnection.IceConnectionState.FAILED || newState == PeerConnection.IceConnectionState.DISCONNECTED) {
                    _transferStatus.value = TransferStatus.FAILED
                    cleanupTransfer()
                }
            }

            override fun onDataChannel(dc: DataChannel?) {
                // Receiver gets DataChannel here
                dc?.let {
                    dataChannel = it
                    setupDataChannelObserver(it)
                }
            }
        })
    }

    private fun createDataChannel() {
        val init = DataChannel.Init()
        init.ordered = true
        init.negotiated = false // We negotiate via SDP
        
        dataChannel = peerConnection?.createDataChannel("file_transfer", init)
        dataChannel?.let { setupDataChannelObserver(it) }
    }

    private fun setupDataChannelObserver(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                Log.d(TAG, "DataChannel State: ${dc.state()}")
                if (dc.state() == DataChannel.State.OPEN) {
                    if (outgoingFile != null) {
                        sendFileData()
                    }
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (!buffer.binary) return
                handleIncomingData(buffer.data)
            }
        })
    }

    // ==================== Data Transfer ====================

    private fun sendFileData() {
        scope.launch {
            _transferStatus.value = TransferStatus.TRANSFERRING
            val file = outgoingFile ?: return@launch
            val buffer = ByteArray(16 * 1024) // 16KB chunks
            val totalBytes = file.length()
            var sentBytes: Long = 0

            try {
                file.inputStream().use { input ->
                    var bytesRead = input.read(buffer)
                    while (bytesRead != -1) {
                        if (dataChannel?.state() != DataChannel.State.OPEN) break

                        val chunk = ByteBuffer.wrap(buffer, 0, bytesRead)
                        val dataBuffer = DataChannel.Buffer(chunk, true)
                        dataChannel?.send(dataBuffer)

                        sentBytes += bytesRead
                        _progress.value = sentBytes.toFloat() / totalBytes

                        bytesRead = input.read(buffer)
                        // Simple flow control
                        // Thread.sleep(5) 
                    }
                }
                _transferStatus.value = TransferStatus.COMPLETED
                Log.d(TAG, "File sent successfully")
                cleanupTransfer()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending file", e)
                _transferStatus.value = TransferStatus.FAILED
                cleanupTransfer()
            }
        }
    }

    private fun handleIncomingData(data: ByteBuffer) {
        try {
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            
            incomingFile?.appendBytes(bytes)
            receivedBytes += bytes.size
            
            if (incomingFileSize > 0) {
                _progress.value = receivedBytes.toFloat() / incomingFileSize
            }

            if (receivedBytes >= incomingFileSize) {
                _transferStatus.value = TransferStatus.COMPLETED
                Log.d(TAG, "File received successfully: ${incomingFile?.absolutePath}")
                
                // Move to Downloads
                try {
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val finalFile = File(downloadsDir, incomingFile?.name?.removePrefix("temp_${System.currentTimeMillis()}_") ?: "received_file")
                    incomingFile?.copyTo(finalFile, overwrite = true)
                    incomingFile?.delete()
                    Log.d(TAG, "File moved to: ${finalFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error moving file to downloads", e)
                }
                cleanupTransfer()
            } else {
                _transferStatus.value = TransferStatus.TRANSFERRING
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving data", e)
            _transferStatus.value = TransferStatus.FAILED
            cleanupTransfer()
        }
    }

    // ==================== Firestore Helpers ====================

    private fun sendOffer(chatId: String, transferId: String, sdp: String, fileName: String, fileSize: Long, senderId: String, receiverId: String) {
        val session = TransferSession(
            transferId = transferId,
            senderId = senderId,
            receiverId = receiverId,
            fileName = fileName,
            fileSize = fileSize,
            offer = sdp,
            status = TransferStatus.PENDING
        )
        firestore.collection("chats").document(chatId)
            .collection("transfers").document(transferId)
            .set(session)
            
        listenForCandidates(chatId, transferId, isSender = true)
    }

    private fun sendAnswer(chatId: String, transferId: String, sdp: String) {
        firestore.collection("chats").document(chatId)
            .collection("transfers").document(transferId)
            .update("answer", sdp, "status", TransferStatus.CONNECTING)
    }

    private fun sendIceCandidate(chatId: String, transferId: String, candidate: IceCandidate, isSender: Boolean) {
        val collection = if (isSender) "senderCandidates" else "receiverCandidates"
        val candidateModel = IceCandidateModel(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        firestore.collection("chats").document(chatId)
            .collection("transfers").document(transferId)
            .collection(collection).add(candidateModel)
    }

    private fun listenForCandidates(chatId: String, transferId: String, isSender: Boolean) {
        val collection = if (isSender) "receiverCandidates" else "senderCandidates"
        firestore.collection("chats").document(chatId)
            .collection("transfers").document(transferId)
            .collection(collection)
            .addSnapshotListener { snapshots, e ->
                if (e != null) return@addSnapshotListener
                snapshots?.documentChanges?.forEach { change ->
                    if (change.type == DocumentChange.Type.ADDED) {
                        val data = change.document.data
                        val sdpMid = data["sdpMid"] as String
                        val sdpMLineIndex = (data["sdpMLineIndex"] as Long).toInt()
                        val sdp = data["sdp"] as String
                        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                        peerConnection?.addIceCandidate(candidate)
                    }
                }
            }
    }

    fun cancelTransfer() {
        _transferStatus.value = TransferStatus.FAILED
        cleanupTransfer()
    }

    private fun cleanupTransfer() {
        val chatId = currentChatId
        val transferId = activeTransferId
        
        // Close WebRTC
        dataChannel?.close()
        dataChannel?.dispose()
        dataChannel = null
        
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        
        // Delete Firestore Doc
        if (chatId != null && transferId != null) {
            firestore.collection("chats").document(chatId)
                .collection("transfers").document(transferId)
                .delete()
                .addOnSuccessListener { Log.d(TAG, "Transfer doc deleted") }
                .addOnFailureListener { e -> Log.e(TAG, "Error deleting transfer doc", e) }
        }
        
        // Reset State
        currentChatId = null
        activeTransferId = null
        outgoingFile = null
        incomingFile = null
        _currentTransferId.value = null
    }

    // ==================== Adapters ====================

    open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) { Log.e("SdpObserver", "Create Failure: $p0") }
        override fun onSetFailure(p0: String?) { Log.e("SdpObserver", "Set Failure: $p0") }
    }

    open class PeerConnectionObserverAdapter : PeerConnection.Observer {
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(p0: IceCandidate?) {}
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onAddStream(p0: MediaStream?) {}
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onDataChannel(p0: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }
}
