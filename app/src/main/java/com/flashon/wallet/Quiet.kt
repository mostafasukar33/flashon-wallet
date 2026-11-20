package com.flashon.wallet

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcelable
import android.os.ResultReceiver
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

/**
 * Talks to Vodafone with no screen at all, for a phone locked with a password: there Android keeps Vodafone's own
 * screen behind the lock, so nothing can be typed in it until someone opens the phone.
 *
 * Android's public TelephonyManager.sendUssdRequest gives one answer per code, and when Vodafone asks something back
 * it says "failed" and drops Vodafone's words. The same call one level down, the phone service's handleUssdRequest
 * (what sendUssdRequest itself calls), hands the words over with that "failed" (AOSP GsmCdmaPhone.onMMIDone sends
 * mmi.getMessage() either way). And a reply sent the same way while Vodafone waits goes into the same talk: the
 * modem sends a USSD string inside the session that's open. Android sends a string as USSD only when it ends in "#"
 * (or is one or two digits); anything else it dials as a phone call, so every reply goes with "#" (Ussd.quietReply).
 *
 * The phone service is a part of Android apps can't reach by its name since Android 9, so this lifts that limit for
 * this app only (HiddenApiBypass). If anything here is missing on this phone, [ready] says no and nothing is sent.
 */
object Quiet {
    // TelephonyManager.USSD_RESPONSE / USSD_RETURN_SUCCESS, and UssdResponse: hidden from apps' build kit, same values on every phone
    private const val KEY = "USSD_RESPONSE"
    private const val SUCCESS = 100
    private var tel: Any? = null
    private var call: Method? = null
    private var tried = false
    /** Why it can't, for the step-by-step list. */
    @Volatile var why = ""
        private set

    @Synchronized fun ready(): Boolean {
        if (tried) return call != null
        tried = true
        try {
            if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("L")
            val b = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "phone") as? IBinder
            if (b == null) { why = "مفيش خدمة الهاتف"; return false }
            val t = Class.forName("com.android.internal.telephony.ITelephony\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, b)
            val m = t?.javaClass?.methods?.firstOrNull { m ->
                m.name == "handleUssdRequest" && m.parameterTypes.let { p -> p.size == 3 && p[0] == Int::class.javaPrimitiveType && p[1] == String::class.java && p[2] == ResultReceiver::class.java }
            }
            if (m == null) { why = "خدمة الهاتف مختلفة على الموبايل ده"; return false }
            m.isAccessible = true
            tel = t; call = m
        } catch (e: Throwable) { why = said(e) }
        return call != null
    }

    /**
     * Sends [s] on the SIM [sub]. [done] runs on the main thread with Vodafone's words (null = none) and whether
     * Vodafone waits for an answer (Android reports a page that asks something as "failed"). False = not sent.
     */
    fun send(sub: Int, s: String, done: (asks: Boolean, text: String?) -> Unit): Boolean {
        val m = call ?: return false
        val rr = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(code: Int, data: Bundle?) {
                val text = try {
                    @Suppress("DEPRECATION") val r = data?.getParcelable<Parcelable>(KEY)
                    r?.javaClass?.getMethod("getReturnMessage")?.invoke(r)?.toString()
                } catch (_: Throwable) { null }
                done(code != SUCCESS, text)
            }
        }
        return try { m.invoke(tel, sub, s, rr); true } catch (e: Throwable) { why = said(e); false }
    }

    /** What went wrong, short: the real error under a reflection wrapper. */
    private fun said(e: Throwable): String {
        val c = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e.cause ?: e
        return (c.javaClass.simpleName + (c.message?.let { ": $it" } ?: "")).take(160)
    }
}
