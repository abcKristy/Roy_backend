package com.example.auth

/** Manual benchmark. Run Gradle's benchmarkPasswordHashing task on the target host. */
fun main() {
    val costs = listOf(
        Argon2Cost(19_456, 2),
        Argon2Cost(46_080, 1),
        Argon2Cost(65_536, 3),
    )
    println("Argon2id sequential hash+verify; JVM=${System.getProperty("java.version")}; OS=${System.getProperty("os.name")}")
    for (cost in costs) {
        val hasher = Argon2idPasswordHasher(cost)
        val samples = List(6) {
            val start = System.nanoTime()
            val encoded = hasher.hash("benchmark-only-password")
            check(hasher.verify("benchmark-only-password", encoded))
            (System.nanoTime() - start) / 1_000_000
        }.drop(1)
        println("m=${cost.memoryKiB}KiB t=${cost.iterations} p=${cost.parallelism} samplesMs=$samples medianMs=${samples.sorted()[2]}")
    }
}
