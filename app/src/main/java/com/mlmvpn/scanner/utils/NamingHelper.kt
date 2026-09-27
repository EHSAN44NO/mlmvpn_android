package com.mlmvpn.scanner.utils

import kotlin.random.Random
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

object NamingHelper {
    private val mythNames = listOf(
        S(R.string.rostam), S(R.string.sohrab), S(R.string.kaveh), S(R.string.arash), S(R.string.siavash), S(R.string.fereydoun), S(R.string.esfandiar), S(R.string.jamshid),
        S(R.string.keykhosrow), S(R.string.garshasp), S(R.string.bahram), S(R.string.zal), S(R.string.tahmineh), S(R.string.gordafarid), S(R.string.rudabeh), S(R.string.gordiyeh),
        S(R.string.anahita), S(R.string.pasargad), S(R.string.youtab), S(R.string.zarbanou), S(R.string.artemis), S(R.string.atossa), S(R.string.artadokht),
        S(R.string.azaranahid), S(R.string.kassandane)
    )

    fun generateMythName(): String {
        return mythNames.random()
    }
}
