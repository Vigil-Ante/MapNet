package com.mapnet.tools

import android.content.Context

/** Offline vendor lookup. The bundled seed is generated from IEEE's public MA-L registry. */
class MacVendorLookup(context: Context) {
    private val vendors: Map<String, String> by lazy {
        context.assets.open("oui-vendors.txt").bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val clean = line.substringBefore('#').trim()
                val separator = clean.indexOf('|')
                if (separator <= 0) return@mapNotNull null
                val prefix = clean.substring(0, separator).uppercase().filter(Char::isLetterOrDigit)
                val vendor = clean.substring(separator + 1).trim()
                (prefix.takeIf { it.length == 6 } ?: return@mapNotNull null) to vendor
            }.toMap()
        }
    }

    fun lookup(macAddress: String?): String? {
        val compact = macAddress?.uppercase()?.filter(Char::isLetterOrDigit).orEmpty()
        if (compact.length < 6) return null
        val firstOctet = compact.take(2).toIntOrNull(16) ?: return null
        if (firstOctet and 0x02 != 0) return "Private / randomized"
        return vendors[compact.take(6)]
    }

    companion object {
        const val DATABASE_DATE = "2026-08-28"
    }
}
