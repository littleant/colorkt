package dev.kdrag0n.colorkt.cam

import dev.kdrag0n.colorkt.Color
import dev.kdrag0n.colorkt.Illuminants
import dev.kdrag0n.colorkt.adaptation.VonKries
import dev.kdrag0n.colorkt.tristimulus.CieXyz
import dev.kdrag0n.colorkt.tristimulus.CieXyz100
import dev.kdrag0n.colorkt.util.cbrt
import dev.kdrag0n.colorkt.util.square
import dev.kdrag0n.colorkt.util.toDegrees
import dev.kdrag0n.colorkt.util.toRadians
import kotlin.math.*

// Math code looks better with underscores, and we want to match the paper
@Suppress("LocalVariableName", "PrivatePropertyName", "PropertyName")
data class Zcam(
    // 1D
    val brightness: Double = Double.NaN,
    val lightness: Double = Double.NaN,
    val colorfulness: Double = Double.NaN,
    val chroma: Double = Double.NaN,
    val hueAngle: Double,
    /* hue composition is not supported */

    // 2D
    val saturation: Double = Double.NaN,
    val vividness: Double = Double.NaN,
    val blackness: Double = Double.NaN,
    val whiteness: Double = Double.NaN,

    val viewingConditions: ViewingConditions,

    // DEBUG
    val Iz: Double,
    val az: Double,
    val bz: Double,
) : Color {
    // Aliases to match the paper
    val Qz: Double get() = brightness
    val Jz: Double get() = lightness
    val Mz: Double get() = colorfulness
    val Cz: Double get() = chroma
    val hz: Double get() = hueAngle
    val Sz: Double get() = saturation
    val Vz: Double get() = vividness
    val Kz: Double get() = blackness
    val Wz: Double get() = whiteness

    fun toCieXyz100(
        luminanceSource: LuminanceSource,
        chromaSource: ChromaSource,
    ): CieXyz100 {
        val cond = viewingConditions
        val Iz_w = cond.Iz_w
        val Qz_w = cond.Qz_w

        /* Step 1 */
        // Achromatic response
        val Iz_denom = 2700.0 * cond.F_s.pow(2.2) * cond.F_b.pow(0.5) * cond.F_l.pow(0.2)
        val Iz = when (luminanceSource) {
            LuminanceSource.BRIGHTNESS -> Qz / Iz_denom
            LuminanceSource.LIGHTNESS -> (Jz * Qz_w) / (Iz_denom * 100.0)
        }.pow(cond.F_b.pow(0.12) / (1.6 * cond.F_s))

        println("INV: Iz = $Iz")
        /* Step 2 */
        // Chroma
        val Cz = when (chromaSource) {
            ChromaSource.CHROMA -> Cz
            ChromaSource.COLORFULNESS -> Double.NaN // not used
            ChromaSource.SATURATION -> (Qz * square(Sz)) / (100.0 * Qz_w * cond.F_l.pow(1.2))
            ChromaSource.VIVIDNESS -> sqrt((square(Vz) - square(Jz - 58)) / 3.4)
            ChromaSource.BLACKNESS -> sqrt((square((100 - Kz) / 0.8) - square(Jz)) / 8)
            ChromaSource.WHITENESS -> sqrt(square(100.0 - Wz) - square(100.0 - Jz))
        }
        println("INV: Cz = $Cz")

        /* Step 3 is missing because hue composition is not supported */

        /* Step 4 */
        // ... and back to colorfulness
        val Mz = when (chromaSource) {
            ChromaSource.COLORFULNESS -> Mz
            else -> (Cz * Qz_w) / 100
        }
        val ez = hpToEz(hz)
        val Cz_p = ((Mz * Iz_w.pow(0.78) * cond.F_b.pow(0.1)) /
                // Paper specifies pow(1.3514) but this extra precision is necessary for more accurate inversion
                (100.0 * ez.pow(0.068) * cond.F_l.pow(0.2))).pow(1.0 / 0.37 / 2)
        val az = Cz_p * cos(hz.toRadians())
        val bz = Cz_p * sin(hz.toRadians())
        println("INV: Mz = $Mz  ez=$ez  Cz_p=$Cz_p  az=$az  bz=$bz")

        /* Step 5 */
        val I = Iz + EPSILON

        val r = pqInv(I + 0.2772100865*az +  0.1160946323*bz)
        val g = pqInv(I)
        val b = pqInv(I + 0.0425858012*az + -0.7538445799*bz)
        println("INV: rp=${I + 0.2772100865*az +  0.1160946323*bz} gp=$I bp=${I + 0.0425858012*az + -0.7538445799*bz}")
        println("INV: I=$I r=$r g=$g b=$b")

        val xp =  1.9242264358*r + -1.0047923126*g +  0.0376514040*b
        val yp =  0.3503167621*r +  0.7264811939*g + -0.0653844229*b
        val z  = -0.0909828110*r + -0.3127282905*g +  1.5227665613*b
        println("INV: xp=$xp yp=$yp z=$z")

        val x = (xp + (B - 1)*z) / B
        val y = (yp + (G - 1)*x) / G
        println("INV: x=$x y=$y")

        return CieXyz100(x, y, z)
    }

    enum class LuminanceSource {
        BRIGHTNESS,
        LIGHTNESS,
    }

    enum class ChromaSource {
        CHROMA,
        COLORFULNESS,
        SATURATION,
        VIVIDNESS,
        BLACKNESS,
        WHITENESS,
    }

    data class ViewingConditions(
        val F_s: Double, // F_s

        val L_a: Double,
        val Y_b: Double,
        // Absolute
        //val whiteLuminance: Double,
        //val backgroundLuminance: Double,

        val referenceWhite: CieXyz100,
    ) {
        /* Step 1 */
        //private val L_a = whiteLuminance *1 //TODO

        //val backgroundFactor = sqrt(backgroundLuminance / whiteLuminance) // F_b
        val F_b = sqrt(Y_b / referenceWhite.y)
        val F_l = 0.171 * cbrt(L_a) * (1.0 - exp(-48.0/9.0 * L_a)) // F_L

        internal val Iz_w = referenceWhite.xyzToIzazbz()[0]
        internal val Qz_w = izToQz(Iz_w, this)

        companion object {
            const val SURROUND_DARK = 0.525
            const val SURROUND_DIM = 0.59
            const val SURROUND_AVERAGE = 0.69

            /*
            val DEFAULT = ViewingConditions(
                surroundFactor = SURROUND_AVERAGE,
                whiteLuminance = 40.0,
                backgroundLuminance = 20.0,
                referenceWhite = Illuminants.D65.toCieXyz100(),
            )*/
        }
    }

    companion object {
        // Constants
        private const val B = 1.15
        private const val G = 0.66
        private const val C1 = 3424.0 / 4096
        private const val C2 = 2413.0 / 128
        private const val C3 = 2392.0 / 128
        private const val ETA = 2610.0 / 16384
        private const val RHO = 1.7 * 2523.0 / 32
        private const val EPSILON = 3.7035226210190005e-11

        // Transfer function and inverse
        private fun pq(x: Double): Double {
            val num = C1 + C2*(x / 10000).pow(ETA)
            val denom = 1.0 + C3*(x / 10000).pow(ETA)

            return (num / denom).pow(RHO)
        }
        private fun pqInv(x: Double): Double {
            val num = C1 - x.pow(1.0/RHO)
            val denom = C3*x.pow(1.0/RHO) - C2

            println("INV: pqInv x=$x num=$num denom=$denom")
            return 10000.0 * (num / denom).pow(1.0/ETA)
        }

        // Intermediate conversion, also used in ViewingConditions
        private fun CieXyz100.xyzToIzazbz(): DoubleArray {
            val xp = B*x - (B-1)*z
            val yp = G*y - (G-1)*x

            val rp = pq( 0.41478972*xp + 0.579999*yp + 0.0146480*z)
            val gp = pq(-0.20151000*xp + 1.120649*yp + 0.0531008*z)
            val bp = pq(-0.01660080*xp + 0.264800*yp + 0.6684799*z)
            println("lmsp = $rp  $gp  $bp")

            val az = 3.524000*rp + -4.066708*gp +  0.542708*bp
            val bz = 0.199076*rp +  1.096799*gp + -1.295875*bp
            val Iz = gp - EPSILON

            return doubleArrayOf(Iz, az, bz)
        }

        // Shared between forward and inverse models
        private fun hpToEz(hp: Double) = 1.015 + cos((89.038 + hp).toRadians())
        private fun izToQz(Iz: Double, cond: ViewingConditions) =
            2700.0 * Iz.pow((1.6 * cond.F_s) / cond.F_b.pow(0.12)) *
                    (cond.F_s.pow(2.2) * cond.F_b.pow(0.5) * cond.F_l.pow(0.2))

        fun CieXyz100.toZcam(cond: ViewingConditions): Zcam {
            /* Step 0 */
            // TODO: approx check
            val xyzD65 = if (cond.referenceWhite.toCieXyz() != Illuminants.D65 && false) {
                val values = VonKries.adapt(CieXyz(x, y, z), cond.referenceWhite.toCieXyz(), Illuminants.D65, VonKries.CAT02)
                CieXyz100(values.x, values.y, values.z)
            } else {
                this
            }
            println("adapted xyz = $xyzD65")

            /* Step 2 */
            // Achromatic response
            val (Iz, az, bz) = xyzD65.xyzToIzazbz()
            val Iz_w = cond.Iz_w

            /* Step 3 */
            // Hue angle
            val hz = atan2(bz, az).toDegrees()
            val hp = if (hz < 0) hz + 360 else hz

            /* Step 4 */
            // Eccentricity factor
            val ez = hpToEz(hp)
            println("ez = $ez")

            /* Step 5 */
            // Brightness
            val Qz = izToQz(Iz, cond)
            val Qz_w = cond.Qz_w

            // Lightness
            val Jz = 100.0 * (Qz / Qz_w)

            // Colorfulness
            val Mz = 100.0 * (square(az) + square(bz)).pow(0.37) *
                    ((ez.pow(0.068) * cond.F_l.pow(0.2)) /
                            (cond.F_b.pow(0.1) * Iz_w.pow(0.78)))

            // Chroma
            val Cz = 100.0 * (Mz / Qz_w)

            /* Step 6 */
            // Saturation
            val Sz = 100.0 * cond.F_l.pow(0.6) * sqrt(Mz / Qz)

            // Vividness, blackness, whiteness
            val Vz = sqrt(square(Jz - 58) + 3.4 * square(Cz))
            val Kz = 100.0 - 0.8 * sqrt(square(Jz) + 8.0 * square(Cz))
            val Wz = 100.0 - sqrt(square(100.0 - Jz) + square(Cz))

            return Zcam(
                brightness = Qz,
                lightness = Jz,
                colorfulness = Mz,
                chroma = Cz,
                hueAngle = hp,

                saturation = Sz,
                vividness = Vz,
                blackness = Kz,
                whiteness = Wz,

                viewingConditions = cond,

                // DEBUG
                Iz = Iz,
                az = az,
                bz = bz,
            )
        }
    }
}
