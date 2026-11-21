package dev.wadackel.limenty

/**
 * The release twin of the debug policy, so nothing in `src/main` has to ask which build it is in.
 *
 * Empty rather than a `BuildConfig.DEBUG` branch in one shared file: a branch still compiles the
 * StrictMode calls into the release binary and leaves them to the shrinker to notice.
 */
fun installStrictMode() = Unit
