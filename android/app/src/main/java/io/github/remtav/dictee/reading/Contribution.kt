package io.github.remtav.dictee.reading

/**
 * The facts the consent text and the public policy (`android/docs/CONTRIBUTION.md`) announce
 * (issue #15, lot 4). Each one exists only here: the strings take them as format arguments
 * (`contrib_share_*`), and `ContributionConsistencyTest` checks that the policy quotes the same values.
 */
object Contribution {
    /** The only address that receives contributions (issue #15, "Adresse de réception"). */
    const val RECEIVING_ADDRESS = "remi@kalmia.ca"

    /** Longest time a contribution is kept after it is received, in years; destroyed then, or earlier. */
    const val RETENTION_MAX_YEARS = 2

    /** Where the public policy can be read (opened by the person, never fetched by the app). */
    const val POLICY_URL = "https://github.com/remtav/speech-to-text/blob/main/android/docs/CONTRIBUTION.md"
}
