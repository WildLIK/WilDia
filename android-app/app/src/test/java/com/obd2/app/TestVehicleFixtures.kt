package com.obd2.app

import com.obd2.app.core.repo.VehicleProfile

/**
 * Isolated test-only Škoda Octavia A5 (1Z, 2012, 1.6 MPI BSE) fixture data.
 * Never injected into fresh user installs.
 */
object TestVehicleFixtures {
    fun skodaOctaviaA5Profile(): VehicleProfile = VehicleProfile(
        id = VehicleProfile.BUILTIN_SKODA_ID,
        name = "Škoda Octavia A5 (тестовый)",
        maker = "Škoda",
        model = "Octavia A5 (1Z)",
        year = "2012",
        engine = "1.6 MPI BSE",
        identityStatus = VehicleProfile.ID_MANUAL,
        builtIn = true,
    )

    fun skodaEcuList(): List<Pair<String, String>> = listOf(
        "06A 906 033 KD" to "ЭБУ двигателя — Siemens Simos 7.1",
        "7N0 907 530 R" to "Шлюз CAN",
        "1Z0 920 803 Q" to "Приборная панель",
        "1K0 907 379 AK" to "ABS/ESP (Bosch MK60)",
        "5J0 919 475 A" to "Парктроник",
        "09G 927 750 JA" to "АКПП (Aisin TF-60SN)",
    )
}
