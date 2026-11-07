package eu.tintera.ai.core

/**
 * Je síť, přes kterou by šlo stahování modelu, placená (mobilní data, Wi-Fi z hotspotu
 * telefonu)? Modely mají stovky megabajtů až gigabajty a stahování přes data spustí jedno
 * ťuknutí, proto se má aplikace mimo Wi-Fi ptát (v Plume Nastavení → AI v zařízení).
 *
 * **Placenost, ne druh sítě.** Druh sítě podle přenosu by Wi-Fi a mobil rozlišil, jenže
 * Wi-Fi z hotspotu telefonu jsou taky mobilní data — a systém ji jako placenou vede. Stejně ji
 * bere podmínka úlohy, která na Androidu stahuje (`NetworkType.UNMETERED`), takže dotaz
 * a skutečné chování se shodnou.
 *
 * Čte se při volání, ne jako tok: zajímá jen ve chvíli ťuknutí na Stáhnout.
 */
interface MeteredNetwork {
    fun isMetered(): Boolean
}

/**
 * Platforma, která placenou síť nepozná (desktop: JVM druh sítě nehlásí) nebo modely nestahuje
 * (iOS), a testy. Volba „jen přes Wi-Fi" se pak nenabízí — slibovala by něco, co se nestane.
 */
object NoMeteredNetwork : MeteredNetwork {
    override fun isMetered(): Boolean = false
}
