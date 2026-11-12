package io.github.remtav.dictee.polish

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Human table (stdout, Markdown) and machine report (JSON, per-case TSV) of a scoring run. */
object PolishReport {
    const val RARE_WORDS = "controle-mots-rares"

    private fun pct(n: Int, d: Int) =
        if (d == 0) "—" else "$n/$d (${String.format(Locale.ROOT, "%.1f", 100.0 * n / d)} %)"

    private fun ms(v: Double) = String.format(Locale.ROOT, if (v < 1) "%.3f" else "%.1f", v)

    private fun latency(l: Latency?) = if (l == null) "non mesurée" else "${ms(l.p50)} / ${ms(l.p95)} (${l.where})"

    private fun table(header: List<String>, rows: List<List<String>>): String {
        val widths = header.indices.map { c -> (rows.map { it[c] } + header[c]).maxOf { it.length } }
        fun line(cells: List<String>) = "| " + cells.mapIndexed { c, s -> s.padEnd(widths[c]) }.joinToString(" | ") + " |"
        return (listOf(line(header), "|" + widths.joinToString("|") { "-".repeat(it + 2) } + "|") + rows.map(::line))
            .joinToString("\n")
    }

    fun text(scores: List<ApproachScore>, cases: List<EvalCase>): String {
        val sb = StringBuilder()
        val nTargets = cases.count { !it.isControl }
        val nControls = cases.count { it.isControl }
        sb.append("Polissage : ${cases.size} paires ($nTargets à corriger, $nControls contrôles), ")
            .append("latence mesurée sur l'hôte (pas sur un téléphone) pour les approches exécutées ici.\n\n")
        sb.append(
            table(
                listOf("Approche", "LLM", "Corrigés", "Modifiés à tort", "Contrôles abîmés", "dont mots rares", "Erreurs", "Latence p50 / p95 ms"),
                scores.map { s ->
                    val a = s.all
                    val rare = Tally.of(s.results.filter { it.case.category == RARE_WORDS })
                    listOf(
                        s.approach.name, if (s.approach.needsLlm) "oui" else "non",
                        pct(a.corrected, a.targets), pct(a.wrong, a.targets),
                        pct(a.damaged, a.controls), pct(rare.damaged, rare.controls),
                        a.errors.toString(), latency(s.latency),
                    )
                },
            ),
        ).append("\n")
        for (s in scores) {
            sb.append("\n").append(s.approach.name)
            if (s.approach.description.isNotEmpty()) sb.append(" — ").append(s.approach.description)
            sb.append("\n")
            val groups = s.byCategory().toSortedMap(compareBy({ PolishEvalSet.isControl(it) }, { it })).map { "catégorie ${it.key}" to it.value } +
                s.byLanguage().toSortedMap().map { "langue ${it.key}" to it.value }
            sb.append(
                table(
                    listOf("Groupe", "Corrigés", "Modifiés à tort", "Contrôles abîmés", "Erreurs"),
                    groups.map { (g, t) -> listOf(g, pct(t.corrected, t.targets), pct(t.wrong, t.targets), pct(t.damaged, t.controls), t.errors.toString()) },
                ),
            ).append("\n")
            if (s.damagedIds.isNotEmpty()) sb.append("Abîmés : ").append(s.damagedIds.joinToString()).append("\n")
            if (s.errorIds.isNotEmpty()) sb.append("Erreurs : ").append(s.errorIds.joinToString()).append("\n")
        }
        return sb.toString()
    }

    private fun tallyJson(t: Tally) = JSONObject()
        .put("a_corriger", t.targets).put("corriges", t.corrected).put("modifies_a_tort", t.wrong)
        .put("controles", t.controls).put("abimes", t.damaged).put("erreurs", t.errors)
        .put("taux_corriges", t.correctedRate ?: JSONObject.NULL)
        .put("taux_abimes", t.damagedRate ?: JSONObject.NULL)

    fun json(scores: List<ApproachScore>, cases: List<EvalCase>): JSONObject {
        val approaches = JSONArray()
        for (s in scores) {
            val o = JSONObject()
                .put("nom", s.approach.name)
                .put("llm", s.approach.needsLlm)
                .put("description", s.approach.description)
                .put("rejouee", s.approach is RecordedApproach)
                .put("total", tallyJson(s.all))
                .put("par_categorie", JSONObject().apply { s.byCategory().forEach { (k, v) -> put(k, tallyJson(v)) } })
                .put("par_langue", JSONObject().apply { s.byLanguage().forEach { (k, v) -> put(k, tallyJson(v)) } })
                .put("abimes", JSONArray(s.damagedIds))
                .put("erreurs", JSONArray(s.errorIds))
            s.latency?.let {
                o.put("latence_ms", JSONObject().put("ou", it.where).put("p50", it.p50).put("p95", it.p95).put("mesures", it.samples))
            } ?: o.put("latence_ms", JSONObject.NULL)
            approaches.put(o)
        }
        return JSONObject()
            .put("jeu", PolishEvalSet.RESOURCE)
            .put("paires", cases.size)
            .put("a_corriger", cases.count { !it.isControl })
            .put("controles", cases.count { it.isControl })
            .put("normalisation", "NFC, apostrophe typographique = droite, espaces/tabulations consécutifs = un espace, bords rognés ; casse, ponctuation, accents et espaces insécables comptent")
            .put("approches", approaches)
    }

    /** Per-case outputs of one approach, for reading the failures. */
    fun outputs(s: ApproachScore): String = buildString {
        append("id\tbrut\tsortie\tattendu\tverdict\n")
        for (r in s.results) {
            fun clean(x: String?) = (x ?: "").replace('\t', ' ').replace('\n', ' ')
            append(r.case.id).append('\t').append(clean(r.case.raw)).append('\t').append(clean(r.output ?: r.error))
                .append('\t').append(clean(r.case.expected)).append('\t').append(r.verdict.label).append('\n')
        }
    }

    fun write(dir: File, scores: List<ApproachScore>, cases: List<EvalCase>) {
        dir.mkdirs()
        File(dir, "resultats.json").writeText(json(scores, cases).toString(2))
        File(dir, "resultats.md").writeText("# Évaluation du polissage\n\n" + text(scores, cases))
        for (s in scores) File(dir, "sorties-${s.approach.name}.tsv").writeText(outputs(s))
    }
}
