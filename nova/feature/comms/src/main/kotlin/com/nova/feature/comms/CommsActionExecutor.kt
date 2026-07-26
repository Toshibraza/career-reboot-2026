package com.nova.feature.comms

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.nova.core.agent.ActionExecutor
import com.nova.core.agent.ActionResult
import com.nova.core.agent.NovaAction
import com.nova.core.agent.RequiredPermission
import com.nova.core.agent.comms.ConfirmationSlot
import com.nova.core.agent.comms.Contact
import com.nova.core.agent.comms.PendingAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Calls and messages, never without being told twice.
 *
 * Every other executor in Nova acts immediately, because turning on a torch or opening an app
 * is undoable and asking permission for it would be theatre. A call and a message are not
 * undoable — the other person's phone rings, the message is delivered — and two lossy steps sit
 * between the user and the outcome: speech recognition, then fuzzy name matching. So this
 * proposes, reads back **who and which number**, and does nothing until confirmed.
 */
class CommsActionExecutor(
    context: Context,
    private val contacts: ContactDirectory,
    private val slot: ConfirmationSlot,
) : ActionExecutor {

    private val appContext = context.applicationContext

    override val name: String = "comms"

    override fun canHandle(action: NovaAction): Boolean = when (action) {
        is NovaAction.CallContact,
        is NovaAction.SendSms,
        NovaAction.ConfirmPending,
        NovaAction.CancelPending,
        -> true

        else -> false
    }

    override suspend fun execute(action: NovaAction): ActionResult = when (action) {
        is NovaAction.CallContact -> propose(action.query) { contact ->
            slot.offer(PendingAction.Call(contact, System.currentTimeMillis()))
            "Call ${contact.name} on ${contact.number.spokenDigits()}? Say yes to confirm."
        }

        is NovaAction.SendSms -> propose(action.query) { contact ->
            slot.offer(PendingAction.Sms(contact, action.message, System.currentTimeMillis()))
            "Text ${contact.name}, \"${action.message}\"? Say yes to confirm."
        }

        NovaAction.ConfirmPending -> carryOut()

        NovaAction.CancelPending -> {
            val had = slot.peek() != null
            slot.clear()
            ActionResult.Success(if (had) "Cancelled." else "There was nothing to cancel.")
        }

        else -> ActionResult.Unhandled(action)
    }

    private suspend fun propose(
        query: String,
        offer: (Contact) -> String,
    ): ActionResult {
        if (!contacts.hasPermission) {
            return ActionResult.NeedsPermission(
                RequiredPermission.READ_CONTACTS,
                "I need access to your contacts to find them.",
            )
        }

        val contact = contacts.resolve(query)
            // Never falls back to dialling the raw text as a number. "Call Amit" with no Amit
            // in the address book is a failure, not an instruction to dial something.
            ?: return ActionResult.Failure("I couldn't find anyone called $query.")

        return ActionResult.Success(offer(contact))
    }

    private suspend fun carryOut(): ActionResult {
        val pending = slot.take()
            ?: return ActionResult.Failure("There's nothing waiting to be confirmed.")

        return when (pending) {
            is PendingAction.Call -> place(pending.contact)
            is PendingAction.Sms -> send(pending.contact, pending.message)
        }
    }

    private fun place(contact: Contact): ActionResult {
        if (!granted(Manifest.permission.CALL_PHONE)) {
            return ActionResult.NeedsPermission(
                RequiredPermission.CALL_PHONE,
                "I need permission to make calls.",
            )
        }

        val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", contact.number, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching { appContext.startActivity(intent) }
            .fold(
                onSuccess = { ActionResult.Success("Calling ${contact.name}.") },
                onFailure = { ActionResult.Failure("I couldn't start the call.", it) },
            )
    }

    private suspend fun send(contact: Contact, message: String): ActionResult =
        withContext(Dispatchers.IO) {
            if (!granted(Manifest.permission.SEND_SMS)) {
                return@withContext ActionResult.NeedsPermission(
                    RequiredPermission.SEND_SMS,
                    "I need permission to send messages.",
                )
            }

            runCatching {
                val manager = appContext.getSystemService(SmsManager::class.java)
                // Split, because a long message silently fails to send otherwise — and a
                // message the user believes was sent is worse than one that visibly failed.
                val parts = manager.divideMessage(message)
                manager.sendMultipartTextMessage(contact.number, null, parts, null, null)
            }.fold(
                onSuccess = { ActionResult.Success("Sent to ${contact.name}.") },
                onFailure = { ActionResult.Failure("I couldn't send that message.", it) },
            )
        }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Spaces out a number so it is spoken as digits rather than read as a huge quantity.
     *
     * The number is the only part of the read-back that catches a wrong-contact match, so it
     * has to be intelligible.
     */
    private fun String.spokenDigits(): String =
        filter { it.isDigit() || it == '+' }.toCharArray().joinToString(" ")
}
