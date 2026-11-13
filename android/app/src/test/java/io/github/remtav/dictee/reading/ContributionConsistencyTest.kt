package io.github.remtav.dictee.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The consent shown in the app and the public policy announce the same recipient and retention
 * (issue #15, lot 4, acceptance criterion), and both come from the constants of [Contribution].
 */
class ContributionConsistencyTest {
    private val stringsFile = File(ReadingTestSupport.mainDir, "res/values/strings.xml")
    private val policy = File(ReadingTestSupport.androidDir, "docs/CONTRIBUTION.md").readText()

    /** The `contrib_*` strings, unescaped as Android shows them (before format arguments). */
    private val strings: Map<String, String> by lazy {
        Regex("<string name=\"(contrib_[a-z_]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(stringsFile.readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("\\n", "\n").replace("\\\"", "\"") }
    }

    private fun shown(name: String, vararg args: Any): String = String.format(strings.getValue(name), *args)

    private val retentionPhrase = "${Contribution.RETENTION_MAX_YEARS} ans au plus à compter de la réception, puis destruction"

    @Test
    fun theConstantsAreTheDecisionsOfTheIssue() {
        assertEquals("remi@kalmia.ca", Contribution.RECEIVING_ADDRESS)
        assertEquals(2, Contribution.RETENTION_MAX_YEARS)
        assertTrue(Contribution.POLICY_URL.endsWith("android/docs/CONTRIBUTION.md"))
    }

    @Test
    fun consentAndPolicyAnnounceTheSameRecipientAndRetention() {
        assertTrue(shown("contrib_share_recipient", Contribution.RECEIVING_ADDRESS).contains(Contribution.RECEIVING_ADDRESS))
        assertTrue(shown("contrib_share_withdraw", Contribution.RECEIVING_ADDRESS).contains(Contribution.RECEIVING_ADDRESS))
        assertTrue(shown("contrib_share_retention", Contribution.RETENTION_MAX_YEARS).contains(retentionPhrase))

        assertTrue(policy.contains(retentionPhrase))
        assertTrue(policy.contains("**${Contribution.RECEIVING_ADDRESS}**"))
        // no other address and no other duration in the policy
        val addresses = Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+").findAll(policy).map { it.value }.toSet()
        assertEquals(setOf(Contribution.RECEIVING_ADDRESS), addresses)
        val durations = Regex("(\\d+) ans au plus").findAll(policy).map { it.groupValues[1].toInt() }.toSet()
        assertEquals(setOf(Contribution.RETENTION_MAX_YEARS), durations)
    }

    @Test
    fun theStringsNeverCopyTheAddressOrTheDurationByHand() {
        for ((name, value) in strings) {
            assertFalse("$name copie l'adresse", value.contains("@"))
            assertFalse("$name écrit une durée en dur", Regex("\\d+\\s*ans").containsMatchIn(value))
            assertFalse("$name copie l'adresse de la politique", value.contains("github.com"))
        }
        assertTrue(strings.getValue("contrib_share_recipient").contains("%1\$s"))
        assertTrue(strings.getValue("contrib_share_withdraw").contains("%1\$s"))
        assertTrue(strings.getValue("contrib_share_retention").contains("%1\$d"))
        assertTrue(strings.getValue("contrib_share_policy").contains("%1\$s"))
    }

    @Test
    fun theConsentSaysEverythingTheIssueRequires() {
        // Step 4.3: the « what would leave » list is generated from ArchiveField, one `contrib_field_*` label
        // (and `contrib_value_*` for the values) per element of the archive.
        val list = strings.filterKeys { it.startsWith("contrib_field_") || it.startsWith("contrib_value_") }.values +
            ContributionContent.DECLARATION_TEXT
        val consent = (list + listOf(
            shown("contrib_share_not_sent"),
            shown("contrib_share_recipient", Contribution.RECEIVING_ADDRESS), shown("contrib_share_purpose"),
            shown("contrib_share_email_reveals"), shown("contrib_share_retention", Contribution.RETENTION_MAX_YEARS),
            shown("contrib_share_voice"), shown("contrib_share_withdraw", Contribution.RECEIVING_ADDRESS),
            shown("contrib_share_you_send"),
        )).joinToString("\n").lowercase()
        for (item in listOf(
            "WAV", "16 kHz", "texte lu", "version", "transcription", "score", "modèle", "version de l'application",
            "modèle de votre téléphone", "je parle un français québécois", "date", "région",
            "aucun identifiant", "aucune autre dictée", "adresse de courriel", "Plus tôt si vous le demandez",
            "n'est plus utile", "à la main", "pas anonyme", "retirer votre consentement", "supprimer",
            "C'est vous qui envoyez", "aucun serveur",
        )) assertTrue("consentement : « $item » absent", consent.contains(item.lowercase()))
        for (item in listOf("WAV", "16 kHz", "aucun identifiant", "aucune autre dictée", "à la main",
            "Ceci n'est pas un avis juridique", "à préciser", "Loi 25", "art. 23")) {
            assertTrue("politique : « $item » absent", policy.contains(item))
        }
    }

    @Test
    fun everyElementOfTheArchiveHasItsLabelOnScreen() {
        // The list shown before consent and the archive come from ArchiveField: each element needs its label.
        for (field in ArchiveField.entries) {
            val name = "contrib_field_${field.name.lowercase()}"
            assertTrue("libellé $name absent", strings[name].orEmpty().isNotBlank())
        }
        assertEquals(ArchiveField.entries.size, strings.keys.count { it.startsWith("contrib_field_") })
        // The bullet list of step 4.1 is gone: a second, hand-written list could diverge from the archive.
        assertFalse(strings.containsKey("contrib_share_sent"))
    }

    @Test
    fun theDeclarationAndItsRefusalAreThere() {
        assertEquals("Parlez-vous un français québécois ?", shown("contrib_declare_question"))
        val declined = shown("contrib_declined_body")
        for (item in listOf("pas de test", "pas d'enregistrement", "rien n'est partagé")) assertTrue(declined.contains(item))
        assertEquals("Aider à améliorer la dictée en français québécois", shown("contrib_card_title"))
        assertEquals(2, shown("contrib_card_body").split(Regex("(?<=[.!?])\\s+")).size)
    }
}
