# M1.T2 password hashing

The server uses Argon2id through Password4j 1.8.4 on JVM 17. The `PasswordHasher` interface keeps the application service independent of the library. A 16-byte random salt is generated for each hash. The stored value is a PHC-style encoded string (`$argon2id$v=19$m=...,t=...,p=...$salt$hash`) containing the algorithm, cost, salt, and 32-byte result; no separate salt column is needed. Verification accepts only bounded Argon2id parameters and checks the encoded salt and hash. An unknown phone must call `verifyUnknown` before returning the same `INVALID_CREDENTIALS` response as an incorrect password.

The current code uses the OWASP minimum `m=19456 KiB, t=2, p=1` as a provisional development setting. Hashes with a different accepted cost are verified. After a successful login, `needsRehash` requests replacement only when the current cost is at least as strong in both memory and iterations and strictly stronger in one. Stronger or incomparable stored costs are retained. The login service must update the hash only after a successful password check. Raw passwords must never be logged or persisted.

## Local comparison

Observed on the development Windows 11 host (Intel Core i9-13900H, JDK 17.0.10) on 2026-11-01. Host RAM size was not available to this measurement. Run `.\gradlew.bat benchmarkPasswordHashing --no-daemon` from this repository to repeat the sequential measurement. Each sample hashes and verifies one test password; the first run is discarded. The following five elapsed times in milliseconds are indicative local observations:

| Argon2id cost | Memory per operation | Samples (ms) | Median (ms) |
| --- | ---: | --- | ---: |
| `m=19456,t=2,p=1` | 19 MiB | 106, 72, 69, 67, 72 | 72 |
| `m=46080,t=1,p=1` | 45 MiB | 178, 160, 136, 100, 98 | 136 |
| `m=65536,t=3,p=1` | 64 MiB | 343, 366, 366, 393, 354 | 366 |

These sequential local samples are not a capacity or price result. On a target server, concurrent authentication can consume roughly the selected memory cost times the number of simultaneous operations, plus JVM and database memory. The final cost and concurrency limit require measurement on the selected host within the 5,000 ₽ monthly budget before production registration is enabled.

References: [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html), [Password4j](https://github.com/Password4j/password4j).

## Local diagnostic retry, 2026-11-04

JDK17.0.10,Windows11,sequential hash+verify: five measured samples after one warmup.19456KiB/t2/p1:108,87,64,66,64ms(median66);46080KiB/t1/p1:96,124,143,214,111ms(median124);65536KiB/t3/p1:381,345,399,351,380ms(median380). This is the local development host, not the unknown target deployment. No concurrent-capacity, latency-SLO or budget acceptance is claimed. Production costs remain unchanged. See acceptance-evidence.md:T012 for the remaining blocker.
