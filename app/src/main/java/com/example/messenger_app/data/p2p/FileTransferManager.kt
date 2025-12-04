package com.example.messenger_app.data.p2p

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.messenger_app.data.model.IceCandidateModel
import com.example.messenger_app.data.model.TransferSession
import com.example.messenger_app.data.model.TransferStatus
import com.example.messenger_app.webrtc.WebRtcCallManager
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.webrtc.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID

sealed class TransferState {
    object Idle : TransferState()
    object Connecting : TransferState()
    data class Transferring(val progress: Float) : TransferState()
    data class Completed(val file: File?) : TransferState() // file is null for sender
    data class Failed(val reason: String) : TransferState()
}

class FileTransferManager(
    private val context: Context,
    private val firestore: FirebaseFirestore
) {

    private val TAG = "FileTransferManager"
    private val scope = CoroutineScope(Dispatchers.IO)

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    
    // Transfer State
    private var incomingFile: File? = null
    private var incomingFileSize: Long = 0
    private var receivedBytes: Long = 0
    private var incomingFileName: String = ""

    // Global State (for UI/Service observation)
    private val _transferStatus = MutableStateFlow<TransferStatus>(TransferStatus.PENDING)
    val transferStatus: kotlinx.coroutines.flow.StateFlow<TransferStatus> = _transferStatus

    private val _progress = MutableStateFlow(0f)
    val progress: kotlinx.coroutines.flow.StateFlow<Float> = _progress

    private val _currentTransferId = MutableStateFlow<String?>(null)
    val currentTransferId: kotlinx.coroutines.flow.StateFlow<String?> = _currentTransferId

    fun cancelTransfer() {
        cleanup(null, null) // Cleanup current
        _transferStatus.value = TransferStatus.FAILED
        _currentTransferId.value = null
        _progress.value = 0f
    }

    // ==================== Hosting (Sender) ====================

    fun startHosting(
        chatId: String,
        fileUri: Uri,
        senderId: String,
        receiverId: String
    ): Flow<TransferState> = flow {
        emit(TransferState.Connecting)

        val transferId = UUID.randomUUID().toString()
        _currentTransferId.value = transferId
        _transferStatus.value = TransferStatus.CONNECTING
        _progress.value = 0f
        val pcFactory = WebRtcCallManager.getPeerConnectionFactory() ?: run {
            emit(TransferState.Failed("PeerConnectionFactory not initialized"))
            return@flow
        }

        // 1. Create PeerConnection
        val rtcConfig = PeerConnection.RTCConfiguration(getIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val stateFlow = MutableStateFlow<TransferState>(TransferState.Connecting)

        peerConnection = pcFactory.createPeerConnection(rtcConfig, object : PeerConnectionObserverAdapter() {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { sendIceCandidate(chatId, transferId, it, true) }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "Sender ICE State: $newState")
                if (newState == PeerConnection.IceConnectionState.FAILED) {
                    stateFlow.value = TransferState.Failed("ICE Connection Failed")
                }
            }
        })

        // 2. Create DataChannel
        val init = DataChannel.Init()
        init.ordered = true
        dataChannel = peerConnection?.createDataChannel("file_transfer", init)
        
        dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                Log.d(TAG, "Sender DataChannel State: ${dataChannel?.state()}")
                if (dataChannel?.state() == DataChannel.State.OPEN) {
                    scope.launch {
                        sendFile(fileUri, stateFlow)
                    }
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {}
        })

        // 3. Create Offer
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    peerConnection?.setLocalDescription(object : SdpObserverAdapter() {}, it)
                    
                    // Get file info for initial session data
                    val contentResolver = context.contentResolver
                    var fileName = "unknown_file"
                    var fileSize = 0L
                    
                    contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                            if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                        }
                    }

                    createTransferSession(chatId, transferId, it.description, fileName, fileSize, senderId, receiverId)
                }
            }
        }, constraints)

        // Listen for Answer
        val answerListener = firestore.collection("chats").document(chatId)
            .collection("transfers").document(transferId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener
                if (snapshot != null && snapshot.exists()) {
                    val answerSdp = snapshot.getString("answer")
                    if (answerSdp != null && peerConnection?.remoteDescription == null) {

                    }
                }
            }
            
        listenForCandidates(chatId, transferId, false)

        // Emit updates from stateFlow
        stateFlow.collect {
            emit(it)
            if (it is TransferState.Completed || it is TransferState.Failed) {
                // Cleanup
                answerListener.remove()
                cleanup(chatId, transferId)
            }
        }
    }

    // ==================== Downloading (Receiver) ====================

    fun startDownloading(
        chatId: String,
        transferId: String
    ): Flow<TransferState> = flow {
        Log.d(TAG, "startDownloading: $transferId")
        emit(TransferState.Connecting)
        
        // Update Global State
        _currentTransferId.value = transferId
        _transferStatus.value = TransferStatus.CONNECTING
        _progress.value = 0f

        val pcFactory = WebRtcCallManager.getPeerConnectionFactory() ?: run {
            val error = "PeerConnectionFactory not initialized"
            Log.e(TAG, error)
            _transferStatus.value = TransferStatus.FAILED
            emit(TransferState.Failed(error))
            return@flow
        }
        Log.d(TAG, "PeerConnectionFactory obtained")

        val rtcConfig = PeerConnection.RTCConfiguration(getIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val stateFlow = MutableStateFlow<TransferState>(TransferState.Connecting)

        peerConnection = pcFactory.createPeerConnection(rtcConfig, object : PeerConnectionObserverAdapter() {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { sendIceCandidate(chatId, transferId, it, false) }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "Receiver ICE State: $newState")
                 if (newState == PeerConnection.IceConnectionState.FAILED) {
                    stateFlow.value = TransferState.Failed("ICE Connection Failed")
                }
            }

            override fun onDataChannel(dc: DataChannel?) {
                dc?.let {
                    dataChannel = it
                    it.registerObserver(object : DataChannel.Observer {
                        override fun onBufferedAmountChange(previousAmount: Long) {}
                        override fun onStateChange() {}
                        override fun onMessage(buffer: DataChannel.Buffer) {
                            handleIncomingMessage(buffer, stateFlow)
                        }
                    })
                }
            }
        })

        // Get Offer
        try {
            val doc = firestore.collection("chats").document(chatId)
                .collection("transfers").document(transferId)
                .get().await()
            
            val offerSdp = doc.getString("offer") ?: throw Exception("No offer found")
            


        } catch (e: Exception) {
            val error = "Failed to get offer: ${e.message}"
            _transferStatus.value = TransferStatus.FAILED
            emit(TransferState.Failed(error))
            return@flow
        }

        listenForCandidates(chatId, transferId, true)

        stateFlow.collect { state ->
            // Update Global State
            when (state) {
                is TransferState.Connecting -> _transferStatus.value = TransferStatus.CONNECTING
                is TransferState.Transferring -> {
                    _transferStatus.value = TransferStatus.TRANSFERRING
                    _progress.value = state.progress
                }
                is TransferState.Completed -> {
                    _transferStatus.value = TransferStatus.COMPLETED
                    _progress.value = 1f
                }
                is TransferState.Failed -> _transferStatus.value = TransferStatus.FAILED
                else -> {}
            }
            
            emit(state)
            if (state is TransferState.Completed || state is TransferState.Failed) {
                cleanup(chatId, transferId)
            }
        }
    }

    // ==================== Logic ====================

    private suspend fun sendFile(uri: Uri, stateFlow: MutableStateFlow<TransferState>) {
        try {
            val contentResolver = context.contentResolver
            contentResolver.openInputStream(uri)?.use { inputStream ->
                // 1. Get File Info
                var fileName = "file"
                var fileSize = 0L
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                        if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                    }
                }

                // 2. Send Header
                val headerJson = JSONObject().apply {
                    put("name", fileName)
                    put("size", fileSize)
                    put("mime", contentResolver.getType(uri) ?: "application/octet-stream")
                }
                val headerBytes = headerJson.toString().toByteArray()
                val headerBuffer = DataChannel.Buffer(ByteBuffer.wrap(headerBytes), false) // false = text/string (but we send as binary for simplicity usually, but here let's try binary for everything or distinguish)
                // Actually, let's send header as binary but first byte indicates type? 
                // Or simpler: First message IS header.
                
                dataChannel?.send(headerBuffer)
                Log.d(TAG, "Sent Header: $headerJson")

                // 3. Send Chunks
                val buffer = ByteArray(16 * 1024) // 16KB
                var bytesRead: Int
                var totalSent = 0L
                
                stateFlow.value = TransferState.Transferring(0f)

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (dataChannel?.state() != DataChannel.State.OPEN) throw Exception("DataChannel closed")

                    val chunk = ByteBuffer.wrap(buffer, 0, bytesRead)
                    val dataBuffer = DataChannel.Buffer(chunk, true)
                    
                    // Flow Control: Wait if buffered amount is too high
                    while ((dataChannel?.bufferedAmount() ?: 0) > 1024 * 1024) { // 1MB buffer limit
                        delay(10)
                    }
                    
                    dataChannel?.send(dataBuffer)
                    totalSent += bytesRead
                    stateFlow.value = TransferState.Transferring(totalSent.toFloat() / fileSize)
                }

                stateFlow.value = TransferState.Completed(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending file", e)
            stateFlow.value = TransferState.Failed(e.message ?: "Unknown error")
        }
    }

    private fun handleIncomingMessage(buffer: DataChannel.Buffer, stateFlow: MutableStateFlow<TransferState>) {
        try {
            val data = ByteArray(buffer.data.remaining())
            buffer.data.get(data)

            if (incomingFile == null) {
                // First message is Header
                val headerString = String(data)
                val json = JSONObject(headerString)
                incomingFileName = json.getString("name")
                incomingFileSize = json.getLong("size")
                
                incomingFile = File(context.cacheDir, "temp_${System.currentTimeMillis()}_$incomingFileName")
                receivedBytes = 0
                Log.d(TAG, "Received Header: $json")
                stateFlow.value = TransferState.Transferring(0f)
            } else {
                // Binary Chunk
                FileOutputStream(incomingFile, true).use { fos ->
                    fos.write(data)
                }
                receivedBytes += data.size
                stateFlow.value = TransferState.Transferring(receivedBytes.toFloat() / incomingFileSize)

                if (receivedBytes >= incomingFileSize) {
                    Log.d(TAG, "File received completely")
                    // Move to Downloads
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val finalFile = File(downloadsDir, incomingFileName)
                    
                    // Handle duplicates
                    var finalFileUnique = finalFile
                    var counter = 1
                    while (finalFileUnique.exists()) {
                        val name = incomingFileName.substringBeforeLast(".")
                        val ext = incomingFileName.substringAfterLast(".", "")
                        finalFileUnique = File(downloadsDir, "$name($counter).$ext")
                        counter++
                    }

                    incomingFile?.copyTo(finalFileUnique, overwrite = true)
                    incomingFile?.delete()
                    
                    stateFlow.value = TransferState.Completed(finalFileUnique)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming message", e)
            stateFlow.value = TransferState.Failed(e.message ?: "Unknown error")
        }
    }

    // ==================== Helpers ====================

    private fun getIceServers(): List<PeerConnection.IceServer> {
        return listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
             PeerConnection.IceServer.builder("turn:sil-video.ru:3478?transport=udp")
                .setUsername("melvud").setPassword("berkut14").createIceServer(),
            PeerConnection.IceServer.builder("turn:sil-video.ru:3478?transport=tcp")
                .setUsername("melvud").setPassword("berkut14").createIceServer()
        )
    }

    private fun createTransferSession(chatId: String, transferId: String, sdp: String, fileName: String, fileSize: Long, senderId: String, receiverId: String) {
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
    }

    private fun sendIceCandidate(chatId: String, transferId: String, candidate: IceCandidate, isSender: Boolean) {
        val collection = if (isSender) "senderCandidates" else "receiverCandidates"
        val candidateModel = IceCandidateModel(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        firestore.collection("chats").document(chatId)
            .collection("transfers").document(transferId)
            .collection(collection).add(candidateModel)
    }

    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

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
                        
                        if (peerConnection?.remoteDescription == null) {
                            Log.d(TAG, "Queuing remote candidate (RemoteDescription is null)")
                            pendingRemoteCandidates.add(candidate)
                        } else {
                            Log.d(TAG, "Adding remote candidate")
                            peerConnection?.addIceCandidate(candidate)
                        }
                    }
                }
            }
    }

    private fun drainPendingCandidates() {
        Log.d(TAG, "Draining ${pendingRemoteCandidates.size} pending candidates")
        pendingRemoteCandidates.forEach { 
            peerConnection?.addIceCandidate(it) 
        }
        pendingRemoteCandidates.clear()
    }

    private fun cleanup(chatId: String?, transferId: String?) {
        try {
            dataChannel?.close()
            dataChannel?.dispose()
            peerConnection?.close()
            peerConnection?.dispose()
            
            // Delete Firestore Doc
            if (chatId != null && transferId != null) {
                firestore.collection("chats").document(chatId)
                    .collection("transfers").document(transferId)
                    .delete()
            }
            pendingRemoteCandidates.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up", e)
        }
    }

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
