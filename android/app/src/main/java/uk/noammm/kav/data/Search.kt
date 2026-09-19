package uk.noammm.kav.data

fun Net.searchStops(q: String, limit: Int = 60): IntArray {
    val raw = q.trim().lowercase()
    if (raw.isEmpty()) return IntArray(0)
    val all = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val words = all.filter { !it.all { c -> c.isDigit() } }
    val need = words.ifEmpty { all }

    val idx = ArrayList<Int>(limit * 4)
    val score = ArrayList<Double>(limit * 4)
    for (i in name.indices) {
        val h = hay[i]
        var ok = true
        var sc = 0.0
        for (t in need) {
            val at = h.indexOf(t)
            if (at < 0) { ok = false; break }
            sc += if (at == 0) 0.0 else minOf(at, 40) / 100.0
        }
        if (ok) { idx.add(i); score.add(sc); continue }
        if (need.size == 1 && code[i].toString().startsWith(need[0])) { idx.add(i); score.add(0.5) }
    }
    val orderIdx = idx.indices.sortedBy { score[it] }
    return IntArray(minOf(limit, orderIdx.size)) { idx[orderIdx[it]] }
}

fun Net.searchRoutes(q: String, types: IntArray = intArrayOf()): IntArray {
    val need = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val out = ArrayList<Int>(256)
    val seen = HashSet<String>(1024)
    for (r in rShort.indices) {
        if (types.isNotEmpty() && rType[r] !in types) continue
        if (need.isNotEmpty()) {
            val h = (rShort[r] + " " + rLong[r]).lowercase()
            if (!need.all { h.contains(it) }) continue
        }
        if (!seen.add(rShort[r] + "\u0000" + rLong[r])) continue
        out.add(r)
    }
    return out.toIntArray()
}

fun Net.representativeTrip(route: Int): Int {
    var best = -1; var bestLen = -1
    for (t in tripRoute.indices) {
        if (tripRoute[t] != route) continue
        val len = tripStart[t + 1] - tripStart[t]
        if (len > bestLen) { bestLen = len; best = t }
    }
    return best
}

fun Net.nearestStops(la: Double, lo: Double, k: Int = 24, radius: Double = 2500.0): List<Pair<Int, Double>> {
    val out = ArrayList<Pair<Int, Double>>(k * 4)
    for (i in lat.indices) {
        if (kotlin.math.abs(lat[i] - la) > 0.03 || kotlin.math.abs(lon[i] - lo) > 0.04) continue
        val d = uk.noammm.kav.ui.metres(la, lo, lat[i], lon[i])
        if (d < radius) out.add(i to d)
    }
    return out.sortedBy { it.second }.take(k)
}
