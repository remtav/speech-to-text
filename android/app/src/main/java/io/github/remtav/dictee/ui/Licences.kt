package io.github.remtav.dictee.ui

/**
 * Components of other projects shipped inside the app, shown on the « Licences des composants » screen
 * (issue #71). Adding one is one entry here, its licence text in `assets/licences`.
 */
data class Component(
    val name: String,
    /** What the app takes from it, in a sentence. */
    val use: String,
    /** Copyright lines and the changes made, as the licence asks. */
    val notice: String,
    val licence: String,
    /** Full licence text, in the assets. */
    val licenceAsset: String,
)

object Licences {
    val components = listOf(
        Component(
            name = "AnySoftKeyboard",
            use = "Listes de mots des suggestions du clavier à touches (français, anglais, espagnol, portugais), " +
                "tirées des paquets de langue d'AnySoftKeyboard, qui reprennent les dictionnaires du projet Android Open Source.",
            notice = "Copyright 2009 Menny Even-Danan et les contributeurs d'AnySoftKeyboard.\n" +
                "Copyright The Android Open Source Project.\n" +
                "Source : github.com/AnySoftKeyboard/AnySoftKeyboard, commit 1ee43e7f.\n" +
                "Modifications : entrées réduites au mot, à sa fréquence et à la mention « possiblement offensant » ; " +
                "mots avec chiffres ou autres signes retirés ; listes du Brésil et du Portugal réunies ; lignes triées.",
            licence = "Licence Apache, version 2.0",
            licenceAsset = "licences/AnySoftKeyboard-LICENSE.txt",
        ),
        Component(
            name = "Unicode et CLDR",
            use = "Liste des émojis du panneau d'émojis (ordre, groupes) et leurs noms en français et en anglais, lus par TalkBack, " +
                "tirés du fichier emoji-test.txt d'Unicode 18.0 et des annotations CLDR.",
            notice = "Copyright © 2001–2026 Unicode, Inc.\n" +
                "Sources : unicode.org/Public/emoji/latest/emoji-test.txt, github.com/unicode-org/cldr-json (annotations fr et en).\n" +
                "Modifications : émojis complets seulement, sans les variantes de couleur de peau ; une ligne par émoji " +
                "(groupe, émoji, nom anglais, nom français). Fichier produit par android/tools/emojis/build_emojis.py.",
            licence = "Licence Unicode, version 3",
            licenceAsset = "licences/Unicode-LICENSE.txt",
        ),
    )
}
