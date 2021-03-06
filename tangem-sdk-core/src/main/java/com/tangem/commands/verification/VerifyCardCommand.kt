package com.tangem.commands.verification

import com.tangem.CardSession
import com.tangem.Log
import com.tangem.SessionEnvironment
import com.tangem.TangemSdkError
import com.tangem.commands.Command
import com.tangem.commands.CommandResponse
import com.tangem.commands.common.card.Card
import com.tangem.commands.common.card.CardStatus
import com.tangem.commands.common.card.FirmwareType
import com.tangem.commands.common.network.Result
import com.tangem.commands.common.network.TangemService
import com.tangem.common.CompletionResult
import com.tangem.common.apdu.CommandApdu
import com.tangem.common.apdu.Instruction
import com.tangem.common.apdu.ResponseApdu
import com.tangem.common.extensions.getType
import com.tangem.common.extensions.toHexString
import com.tangem.common.tlv.TlvBuilder
import com.tangem.common.tlv.TlvDecoder
import com.tangem.common.tlv.TlvTag
import com.tangem.crypto.CryptoUtils
import kotlinx.coroutines.launch

class VerifyCardResponse(
        val cardId: String,
        val verificationState: VerifyCardState? = null,
        val artworkInfo: ArtworkInfo? = null,
        internal val salt: ByteArray,
        internal val cardSignature: ByteArray
) : CommandResponse {

    fun verify(publicKey: ByteArray, challenge: ByteArray): Boolean {
        return CryptoUtils.verify(
                publicKey,
                challenge + salt,
                cardSignature
        )
    }
}

enum class VerifyCardState {
    VerifiedOnline,
    VerifiedOffline,
}

class VerifyCardCommand(private val onlineVerification: Boolean) : Command<VerifyCardResponse>() {

    private val challenge = CryptoUtils.generateRandomBytes(16)
    private val tangemService = TangemService()

    override fun run(
            session: CardSession,
            callback: (result: CompletionResult<VerifyCardResponse>) -> Unit
    ) {
        val card = session.environment.card
        val cardPublicKey = card?.cardPublicKey
        if (cardPublicKey == null) {
            callback(CompletionResult.Failure(TangemSdkError.CardError()))
            return
        }
        super.run(session) { result ->
            when (result) {
                is CompletionResult.Failure -> {
                    callback(CompletionResult.Failure(result.error))
                }
                is CompletionResult.Success -> {
                    val response = result.data
                    val verified = response.verify(cardPublicKey, challenge)
                    if (!verified) {
                        callback(CompletionResult.Failure(TangemSdkError.CardVerificationFailed()))
                        return@run
                    }
                    if (!onlineVerification || card.getType() != FirmwareType.Release) {
                        callback(CompletionResult.Success(VerifyCardResponse(
                                response.cardId, VerifyCardState.VerifiedOffline, null,
                                response.salt, response.cardSignature
                        )))
                    } else {
                        verify(result.data, card.cardId, cardPublicKey, session, callback)
                    }
                }
            }
        }
    }

    private fun verify(
            response: VerifyCardResponse, cardId: String, cardPublicKey: ByteArray,
            session: CardSession,
            callback: (result: CompletionResult<VerifyCardResponse>) -> Unit
    ) {
        session.scope.launch {
            val result = tangemService.verifyAndGetInfo(cardId, cardPublicKey.toHexString())

            when (result) {
                is Result.Success -> {
                    val tangemResult = result.data.results?.firstOrNull()
                    if (tangemResult?.passed == true) {
                        callback(CompletionResult.Success(VerifyCardResponse(
                                response.cardId, VerifyCardState.VerifiedOnline, tangemResult.artwork,
                                response.salt, response.cardSignature
                        )))
                    } else {
                        // Don't return failure if online verification failed
                        callback(CompletionResult.Success(VerifyCardResponse(
                                response.cardId, VerifyCardState.VerifiedOffline, null,
                                response.salt, response.cardSignature)))
                    }
                }
                is Result.Failure -> {
                    result.error?.localizedMessage?.let { Log.network { it } }
                    callback(CompletionResult.Success(VerifyCardResponse(
                            response.cardId, VerifyCardState.VerifiedOffline, null,
                            response.salt, response.cardSignature)))
                }
            }
        }
    }

    override fun performPreCheck(card: Card): TangemSdkError? {
        if (card.status == CardStatus.NotPersonalized) {
            return TangemSdkError.NotPersonalized()
        }
        if (card.isActivated) {
            return TangemSdkError.NotActivated()
        }
        return null
    }

    override fun serialize(environment: SessionEnvironment): CommandApdu {
        val tlvBuilder = TlvBuilder()
        tlvBuilder.append(TlvTag.Pin, environment.pin1?.value)
        tlvBuilder.append(TlvTag.CardId, environment.card?.cardId)
        tlvBuilder.append(TlvTag.Challenge, challenge)
        return CommandApdu(Instruction.VerifyCard, tlvBuilder.serialize())
    }

    override fun deserialize(
            environment: SessionEnvironment,
            apdu: ResponseApdu
    ): VerifyCardResponse {
        val tlvData = apdu.getTlvData() ?: throw TangemSdkError.DeserializeApduFailed()

        val decoder = TlvDecoder(tlvData)
        return VerifyCardResponse(
                cardId = decoder.decode(TlvTag.CardId),
                salt = decoder.decode(TlvTag.Salt),
                cardSignature = decoder.decode(TlvTag.CardSignature)
        )
    }
}