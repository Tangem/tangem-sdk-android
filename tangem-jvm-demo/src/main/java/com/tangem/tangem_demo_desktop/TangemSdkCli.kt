import com.tangem.Log
import com.tangem.TangemError
import com.tangem.TangemSdk
import com.tangem.commands.CommandResponse
import com.tangem.commands.SignResponse
import com.tangem.commands.common.ResponseConverter
import com.tangem.commands.file.FileData
import com.tangem.common.CompletionResult
import com.tangem.common.extensions.hexToBytes
import kotlinx.coroutines.runBlocking
import org.apache.commons.cli.CommandLine
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class TangemSdkCli(verbose: Boolean = false, indexOfTerminal: Int? = null, private val cmd: CommandLine) {

    private val sdk = TangemSdk.init(verbose, indexOfTerminal)
    private val responseConverter = ResponseConverter()

    fun execute(command: Command) {
        if (sdk == null) {
            println("There's no NFC terminal to execute command.")
            return
        }

        runBlocking {
            val result: CompletionResult<out CommandResponse> = suspendCoroutine { continuation ->
                when (command) {
                    Command.Read -> read(sdk) { continuation.resume(it) }
                    Command.Sign -> sign(sdk) { continuation.resume(it) }
                    Command.ReadFiles -> readFiles(sdk) { continuation.resume(it) }
                    Command.WriteFiles -> writeFiles(sdk) { continuation.resume(it) }
                    Command.DeleteFiles -> deleteFiles(sdk) { continuation.resume(it) }
                    Command.CreateWallet -> createWallet(sdk) { continuation.resume(it) }
                    Command.PurgeWallet -> purgeWallet(sdk) { continuation.resume(it) }
                }
            }
            handleResult(result)
        }
    }

    private fun read(sdk: TangemSdk, callback: (result: CompletionResult<out CommandResponse>) -> Unit) {
        sdk.scanCard(callback = callback)
    }

    private fun sign(sdk: TangemSdk, callback: (result: CompletionResult<SignResponse>) -> Unit) {
        val hashes = cmd.getOptionValue(TangemCommandOptions.Hashes.opt)
        val cid: String? = cmd.getOptionValue(TangemCommandOptions.CardId.opt)

        if (hashes == null) {
            println("Missing option value")
            return
        }

        sdk.sign(hashes = parseHashes(hashes), cardId = cid, callback = callback)
    }

    private fun parseHashes(hashesArgument: String): Array<ByteArray> {
        return hashesArgument
                .split(",")
                .map { hash -> hash.trim().hexToBytes() }
                .toTypedArray()
    }

    private fun createWallet(sdk: TangemSdk, callback: (result: CompletionResult<out CommandResponse>) -> Unit) {
        val cid: String? = cmd.getOptionValue(TangemCommandOptions.CardId.opt)

        sdk.createWallet(cardId = cid, callback = callback)
    }

    private fun purgeWallet(sdk: TangemSdk, callback: (result: CompletionResult<out CommandResponse>) -> Unit) {
        val cid: String? = cmd.getOptionValue(TangemCommandOptions.CardId.opt)

        sdk.purgeWallet(cardId = cid, walletIndex = null, callback = callback)
    }

    private fun readFiles(sdk: TangemSdk, callback: (result: CompletionResult<out CommandResponse>) -> Unit) {
        val cid: String? = cmd.getOptionValue(TangemCommandOptions.CardId.opt)
        val readPrivateFiles: Boolean = cmd.hasOption(TangemCommandOptions.ReadPrivateFiles.opt)
        val fileIndices: List<Int>? = cmd.getOptionValue(TangemCommandOptions.FileIndices.opt)
                ?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }

        sdk.readFiles(
                readPrivateFiles = readPrivateFiles, indices = fileIndices, cardId = cid,
                callback = callback
        )
    }

    private fun writeFiles(sdk: TangemSdk, callback: (result: CompletionResult<out CommandResponse>) -> Unit) {
        val cid: String? = cmd.getOptionValue(TangemCommandOptions.CardId.opt)
        val files: List<FileData>? = cmd.getOptionValue(TangemCommandOptions.Files.opt)
                ?.split(",")
                ?.map { it.trim().hexToBytes() }
                ?.map { FileData.DataProtectedByPasscode(it) }

        if (files == null) {
            println("Missing option value")
            return
        }

        sdk.writeFiles(files = files, cardId = cid, callback = callback)
    }


    private fun deleteFiles(sdk: TangemSdk, callback: (result: CompletionResult<out CommandResponse>) -> Unit) {
        val cid: String? = cmd.getOptionValue(TangemCommandOptions.CardId.opt)
        val fileIndices: List<Int>? = cmd.getOptionValue(TangemCommandOptions.FileIndices.opt)
                ?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }

        sdk.deleteFiles(indices = fileIndices, cardId = cid, callback = callback)
    }

    private fun handleResult(result: CompletionResult<out CommandResponse>) {
        when (result) {
            is CompletionResult.Success -> {
                println(responseConverter.convertResponse(result.data))
            }
            is CompletionResult.Failure -> handleError(result.error)
        }
        Log.command { "Task completed" }
    }

    private fun handleError(error: TangemError) {
        println("Task error: ${error.code}, ${error.javaClass.simpleName}")
    }
}


enum class Command(val value: String) {
    Read("read"),
    Sign("sign"),
    ReadFiles("readfiles"),
    WriteFiles("writefiles"),
    DeleteFiles("deletefiles"),
    CreateWallet("createwallet"),
    PurgeWallet("purgewallet");

    companion object {
        private val values = values()
        fun byValue(value: String): Command? = values.find { it.value == value }
    }
}