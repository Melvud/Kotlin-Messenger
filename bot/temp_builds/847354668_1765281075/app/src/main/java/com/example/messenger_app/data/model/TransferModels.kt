package com.example.messenger_app.data.model

data class TransferSession(
    val transferId: String = "",
    val senderId: String = "",
    val receiverId: String = "",
    val fileName: String = "",
    val fileSize: Long = 0,
    val offer: String? = null, // SDP
    val answer: String? = null, // SDP
    val status: TransferStatus = TransferStatus.PENDING
)

enum class TransferStatus {
    PENDING,
    CONNECTING,
    TRANSFERRING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class IceCandidateModel(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val sdp: String
)
