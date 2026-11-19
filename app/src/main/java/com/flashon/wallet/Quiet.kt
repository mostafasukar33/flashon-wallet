package com.flashon.wallet

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.telephony.TelephonyManager
import android.telephony.UssdResponse
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
    private const val KEY = "USSD_RESPONSE"   // TelephonyManager.USSD_RESPONSE
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
        } catch (e: Throwable) { why = e.javaClass.simpleName }
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
                val r: UssdResponse? = try {
                    if (Build.VERSION.SDK_INT >= 33) data?.getParcelable(KEY, UssdResponse::class.java)
                    else @Suppress("DEPRECATION") (data?.getParcelable(KEY) as? UssdResponse)
                } catch (_: Throwable) { null }
                done(code != TelephonyManager.USSD_RETURN_SUCCESS, r?.returnMessage?.toString())
            }
        }
        return try { m.invoke(tel, sub, s, rr); true } catch (_: Throwable) { false }
    }
}
