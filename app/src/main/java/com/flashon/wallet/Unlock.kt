package com.flashon.wallet

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Opens the phone's own lock screen for a transfer, so a request from the iPhone goes through with the Android
 * locked and nobody next to it. Vodafone asks for the wallet PIN only on its own screen, and on a phone locked
 * with a password (Infinix) that screen stays behind the lock; so when the owner turns this on, «التحويل التلقائي»
 * (the accessibility service) types the lock PIN on the lock screen, the way a person does, and the transfer goes on.
 *
 * The lock PIN is kept only on this phone, encrypted with a key in Android's keystore that never leaves it, in its
 * own file (not in the data that goes to the iPhone, and the app has no backup). It's typed only while a transfer
 * runs, once per transfer; if the phone is still locked after it (the lock PIN changed), it's wiped and never
 * typed again until it's saved anew, so it can't run up wrong tries on the lock.
 */
object Unlock {
    private const val ALIAS = "flashon_lock_v1"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("unlock", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    /** "on" (saved), "off", or "bad" (it was typed and the phone stayed locked: wiped). */
    fun state(ctx: Context): String = prefs(ctx).let { p -> if (p.getString("ct", null) != null) "on" else p.getString("st", "off") ?: "off" }

    fun has(ctx: Context) = prefs(ctx).getString("ct", null) != null

    /** Does this phone have a lock with a password, PIN or pattern. */
    fun secure(ctx: Context): Boolean = try { ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true } catch (_: Exception) { false }

    /** Saves the lock PIN or password ("" = remove it). Returns null when done, or what's wrong with it. */
    fun save(ctx: Context, pin: String): String? {
        if (pin.isEmpty()) { prefs(ctx).edit().clear().apply(); return null }
        if (pin.length < 4 || pin.length > 32 || pin.any { it.isWhitespace() }) return "اكتب الـ PIN أو الباسورد بتاع قفل الشاشة زي ما هو (4 حروف أو أرقام على الأقل، من غير مسافات)"
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val ct = c.doFinal(pin.toByteArray(Charsets.UTF_8))
            prefs(ctx).edit().clear().putString("iv", b64(c.iv)).putString("ct", b64(ct)).apply()
            null
        } catch (e: Exception) { "مقدرتش أحفظه (${e.javaClass.simpleName})" }
    }

    private fun read(ctx: Context): String? = try {
        val p = prefs(ctx)
        val iv = p.getString("iv", null); val ct = p.getString("ct", null)
        if (iv == null || ct == null) null else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        }
    } catch (_: Exception) { null }

    /** It was typed and the phone stayed locked: wiped, so it's never typed again until it's saved anew. */
    fun failed(ctx: Context) { prefs(ctx).edit().clear().putString("st", "bad").apply() }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        // usable while the phone is locked (that's when it's needed): no "unlocked device required"
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    // ---------- typing it on the lock screen ----------

    /**
     * Types the saved lock PIN on the lock screen that's showing (our transfer screen asked Android for it).
     * Looks for the lock screen's keys for a few seconds; [done] gets "typed", "nokeys" (nothing typed: the lock
     * screen's keys weren't found), or "none" (nothing saved / no accessibility).
     */
    fun type(ctx: Context, done: (String) -> Unit) {
        val svc = UssdService.current() ?: return done("none")
        val pin = read(ctx) ?: return done("none")
        fun attempt(left: Int) {
            val keys = (0 until pin.length).map { i -> pin[i] }
            val pad = keys.all { it in '0'..'9' } && keys.distinct().all { d -> digitKey(svc, d) != null }
            val field = if (pad) null else passwordField(svc)
            when {
                pad -> tapDigits(svc, pin, 0) { enter(svc); done("typed") }
                field != null -> {
                    val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin) }
                    field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    main.postDelayed({
                        val ime = Build.VERSION.SDK_INT >= 30 && field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                        if (!ime) enter(svc)
                        done("typed")
                    }, 300)
                }
                left > 0 -> main.postDelayed({ attempt(left - 1) }, 700)
                else -> done("nokeys")
            }
        }
        attempt(8)
    }

    private fun tapDigits(svc: AccessibilityService, pin: String, i: Int, then: () -> Unit) {
        if (i >= pin.length) { main.postDelayed(then, 250); return }
        digitKey(svc, pin[i])?.let { tap(svc, it) }
        main.postDelayed({ tapDigits(svc, pin, i + 1, then) }, 160)
    }

    /** The "OK / Enter" key of the lock screen, if it has one (newer Android may open on the last digit by itself). */
    private fun enter(svc: AccessibilityService) {
        find(svc) { n ->
            val id = n.viewIdResourceName ?: ""
            val t = ((n.text ?: "").toString() + " " + (n.contentDescription ?: "")).trim().lowercase()
            id.endsWith("/key_enter") || id.endsWith("/btn_enter") || id.endsWith("/ok") ||
                t == "enter" || t == "ok" || t == "done" || t == "إدخال" || t == "ادخال" || t == "موافق" || t == "تم" || t == "تأكيد"
        }?.let { tap(svc, it) }
    }

    /** The lock screen's key for this digit: by its id ("…:id/key5") or by its text ("5" or "٥"). */
    private fun digitKey(svc: AccessibilityService, d: Char): AccessibilityNodeInfo? {
        val ar = ('٠' + (d - '0')).toString()
        return find(svc) { n ->
            val id = n.viewIdResourceName ?: ""
            val t = (n.text ?: "").toString().trim()
            val cd = (n.contentDescription ?: "").toString().trim()
            id.endsWith("/key$d") || ((t == d.toString() || t == ar || cd == d.toString() || cd == ar) && (n.isClickable || n.parent?.isClickable == true))
        }
    }

    private fun passwordField(svc: AccessibilityService): AccessibilityNodeInfo? =
        find(svc) { n -> n.isEditable && (n.isPassword || (n.viewIdResourceName ?: "").contains("password", true) || (n.viewIdResourceName ?: "").contains("pin", true)) }

    /** Searches the lock screen's windows (System UI). */
    private fun find(svc: AccessibilityService, ok: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try { for (w in svc.windows) w.root?.let { roots += it } } catch (_: Exception) { }
        svc.rootInActiveWindow?.let { roots += it }
        for (r in roots) {
            val pkg = (r.packageName ?: "").toString()
            if (pkg == svc.packageName || !(pkg.contains("systemui") || pkg.contains("keyguard") || pkg == "android")) continue
            walk(r, ok)?.let { return it }
        }
        return null
    }

    private fun walk(n: AccessibilityNodeInfo, ok: (AccessibilityNodeInfo) -> Boolean, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 40) return null
        if (n.isVisibleToUser && ok(n)) return n
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> walk(c, ok, depth + 1)?.let { return it } }
        return null
    }

    /** A click on the key; a finger tap on its middle if the key doesn't take clicks. */
    private fun tap(svc: AccessibilityService, n: AccessibilityNodeInfo) {
        var k: AccessibilityNodeInfo? = n
        while (k != null && !k.isClickable) k = k.parent
        if (k != null && k.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val r = Rect(); n.getBoundsInScreen(r)
        if (r.isEmpty) return
        try {
            val p = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
            svc.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null)
        } catch (_: Exception) { }
    }
}
