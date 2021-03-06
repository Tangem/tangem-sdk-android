package com.tangem.common.tlv

import com.tangem.*
import com.tangem.commands.common.IssuerDataMode
import com.tangem.commands.common.card.CardStatus
import com.tangem.commands.common.card.EllipticCurve
import com.tangem.commands.common.card.masks.ProductMask
import com.tangem.commands.common.card.masks.SettingsMask
import com.tangem.commands.common.card.masks.SigningMethodMask
import com.tangem.commands.file.FileDataMode
import com.tangem.commands.file.FileSettings
import com.tangem.commands.read.ReadMode
import com.tangem.commands.wallet.WalletStatus
import com.tangem.common.extensions.toDate
import com.tangem.common.extensions.toHexString
import com.tangem.common.extensions.toInt
import com.tangem.common.extensions.toUtf8
import java.util.*

/**
 * Maps value fields in [Tlv] from raw [ByteArray] to concrete classes
 * according to their [TlvTag] and corresponding [TlvValueType].
 *
 * @property tlvList List of TLVs, which values are to be converted to particular classes.
 */
class TlvDecoder(val tlvList: List<Tlv>) {

    /**
     * Finds [Tlv] by its [TlvTag].
     * Returns null if [Tlv] is not found, otherwise converts its value to [T].
     *
     * @param tag [TlvTag] of a [Tlv] which value is to be returned.
     *
     * @return Value converted to a nullable type [T].
     */
    inline fun <reified T> decodeOptional(tag: TlvTag): T? =
        try {
            decode<T>(tag, false)
        } catch (exception: TangemSdkError.DecodingFailedMissingTag) {
            null
        }

    /**
     * Finds [Tlv] by its [TlvTag].
     * Throws [TaskError.MissingTag] if [Tlv] is not found,
     * otherwise converts [Tlv] value to [T].
     *
     * @param tag [TlvTag] of a [Tlv] which value is to be returned.
     *
     * @return [Tlv] value converted to a nullable type [T].
     *
     * @throws [TangemSdkError.DecodingFailedMissingTag] exception if no [Tlv] is found by the Tag.
     */
    inline fun <reified T> decode(tag: TlvTag, logError: Boolean = true): T {
        val tlv = tlvList.find { it.tag == tag }
            ?: if (tag.valueType() == TlvValueType.BoolValue && T::class == Boolean::class) {
                logTlv(Tlv(tag, "00".toByteArray()), false)
                return false as T
            } else {
                if (logError) {
                    Log.error { "TLV $tag not found" }
                } else {
                    Log.warning { "TLV $tag not found, but it is not required" }
                }
                throw TangemSdkError.DecodingFailedMissingTag("TLV $tag not found")
            }

        val decodedValue: T = decodeTlv(tlv)
        logTlv(tlv, decodedValue)
        return decodedValue
    }

    inline fun <reified T> decodeArray(tag: TlvTag): List<T> {
        val list = tlvList.filter { it.tag == tag }
        return list.map { decodeTlv(it) }
    }

    inline fun <reified T> decodeTlv(tlv: Tlv): T {
        val tag = tlv.tag
        val tlvValue: ByteArray = tlv.value

        return when (tag.valueType()) {
            TlvValueType.HexString, TlvValueType.HexStringToHash -> {
                typeCheck<T, String>(tag)
                tlvValue.toHexString() as T
            }
            TlvValueType.Utf8String -> {
                typeCheck<T, String>(tag)
                tlvValue.toUtf8() as T
            }
            TlvValueType.Uint8, TlvValueType.Uint16, TlvValueType.Uint32 -> {
                typeCheck<T, Int>(tag)
                try {
                    tlvValue.toInt() as T
                } catch (exception: IllegalArgumentException) {
                    logException(tag, tlvValue.toHexString(), exception)
                    throw TangemSdkError.DecodingFailed(provideDecodingFailedMessage(tag))
                }
            }
            TlvValueType.BoolValue -> {
                typeCheck<T, Boolean>(tag)
                true as T
            }
            TlvValueType.ByteArray -> {
                typeCheck<T, ByteArray>(tag)
                tlvValue as T
            }
            TlvValueType.EllipticCurve -> {
                typeCheck<T, EllipticCurve>(tag)
                try {
                    EllipticCurve.byName(tlvValue.toUtf8()) as T
                } catch (exception: Exception) {
                    logException(tag, tlvValue.toUtf8(), exception)
                    throw TangemSdkError.DecodingFailed(provideDecodingFailedMessage(tag))
                }
            }
            TlvValueType.DateTime -> {
                typeCheck<T, Date>(tag)
                try {
                    tlvValue.toDate() as T
                } catch (exception: Exception) {
                    logException(tag, tlvValue.toHexString(), exception)
                    throw TangemSdkError.DecodingFailed(provideDecodingFailedMessage(tag))
                }
            }
            TlvValueType.ProductMask -> {
                typeCheck<T, ProductMask>(tag)
                ProductMask(tlvValue.toInt()) as T
            }
            TlvValueType.SettingsMask -> {
                typeCheck<T, SettingsMask>(tag)
                SettingsMask(tlvValue.toInt()) as T
            }
            TlvValueType.Status -> {
                try {
                    typeCheck<T, CardStatus>(tag)
                    CardStatus.byCode(tlvValue.toInt()) as T
                } catch (exception: Exception) {
                    Log.warning { "Status is not CardStatus type. Trying to check WalletStatus" }
                    try {
                        typeCheck<T, WalletStatus>(tag)
                        WalletStatus.byValue(tlvValue.toInt()) as T
                    } catch (ex: Exception) {
                        logException(tag, tlvValue.toInt().toString(), exception)
                        throw TangemSdkError.DecodingFailed(provideDecodingFailedMessage(tag))
                    }
                }
            }
            TlvValueType.SigningMethod -> {
                typeCheck<T, SigningMethodMask>(tag)
                try {
                    SigningMethodMask(tlvValue.toInt()) as T
                } catch (exception: Exception) {
                    logException(tag, tlvValue.toInt().toString(), exception)
                    throw TangemSdkError.DecodingFailed(provideDecodingFailedMessage(tag))
                }
            }
            TlvValueType.InteractionMode -> {
                try {
                    typeCheck<T, IssuerDataMode>(tag)
                    IssuerDataMode.byCode(tlvValue.toInt().toByte()) as T
                } catch (ex: Exception) {
                    Log.warning { "InteractionMode is not IssuerDataMode type. Trying to check ReadMode" }
                    try {
                        typeCheck<T, ReadMode>(tag)
                        ReadMode.byRawValue(tlvValue.toInt()) as T
                    } catch (ex: Exception) {
                        logException(tag, tlvValue.toInt().toString(), ex)
                        throw TangemSdkError.DecodingFailed(provideDecodingFailedMessage(tag))
                    }
                }
            }
            TlvValueType.FileDataMode -> {
                typeCheck<T, FileDataMode>(tag)
                try {
                    FileDataMode.byRawValue(tlvValue.toInt()) as T
                } catch (exception: Exception) {
                    logException(tag, tlvValue.toInt().toString(), exception)
                    throw TangemSdkError.DecodingFailed(provideDecodingFailedMessage(tag))
                }
            }
            TlvValueType.FileSettings -> {
                typeCheck<T, FileSettings>(tag)
                try {
                    FileSettings.byRawValue(tlvValue.toInt()) as T
                } catch (exception: Exception) {
                    logException(tag, tlvValue.toInt().toString(), exception)
                    throw TangemSdkError.DecodingFailed(provideDecodingFailedMessage(tag))
                }
            }
        }
    }

    fun <T> logTlv(tlv: Tlv, value: T) {
        var tlvString = tlv.toString()
        if (tlv.tag.valueType() != TlvValueType.ByteArray && tlv.tag.valueType() != TlvValueType.HexString) {
            tlvString += " ($value)"
        }
        Log.tlv { tlvString }
    }

    fun provideDecodingFailedMessage(tag: TlvTag): String =
        "Decoding failed. Failed to convert $tag to ${tag.valueType()}"

    fun logException(tag: TlvTag, value: String, exception: Exception) {
        Log.error { "Unknown ${tag.name} with value of: $value, \n${exception.message}" }
    }

    inline fun <reified T, reified ExpectedT> typeCheck(tag: TlvTag) {
        if (T::class != ExpectedT::class) {
            val error = TangemSdkError.DecodingFailedTypeMismatch(
                "Decoder: Mapping error. Type for tag: $tag must be ${tag.valueType()}. It is ${T::class}"
            )
            Log.error { error.customMessage }
            throw error
        }
    }
}