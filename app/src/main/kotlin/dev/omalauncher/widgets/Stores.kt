package dev.omalauncher.widgets

/**
 * A widget store: a GitHub repo whose CI publishes index.json and the widget files to GitHub Pages,
 * like the built-in one. [repo] is `owner/name` as the user typed it; GitHub ignores case, so equality does too.
 */
data class Store(
    val repo: String,
) {
    private val owner get() = repo.substringBefore('/')
    private val name get() = repo.substringAfter('/')

    /** Where its CI publishes index.json and the files. */
    val base get() = "https://${owner.lowercase()}.github.io/$name/"

    /** The built-in store, whose maintainer reviews every widget; others publish without review. */
    val reviewed get() = this == BUILT_IN

    override fun equals(other: Any?) = other is Store && other.repo.equals(repo, ignoreCase = true)

    override fun hashCode() = repo.lowercase().hashCode()

    companion object {
        val BUILT_IN = Store("SimonSchubert/omarchy-widgets")

        // GitHub's charset; the URL shapes leave out a trailing .git or slash.
        private const val OWNER = "([A-Za-z0-9-]+)"
        private const val NAME = "([A-Za-z0-9._-]+?)"
        private val SHAPES =
            listOf(
                Regex("$OWNER/$NAME"),
                Regex("https://github\\.com/$OWNER/$NAME(?:\\.git|/)?"),
                Regex("https://$OWNER\\.github\\.io/$NAME/?"),
            )

        /** `owner/name`, the repo's GitHub URL, or its Pages URL; null for anything else. */
        fun parse(input: String): Store? {
            val s = input.trim()
            val (owner, name) = SHAPES.firstNotNullOfOrNull { it.matchEntire(s) }?.destructured ?: return null
            return Store("$owner/$name")
        }
    }
}

/**
 * The key of an install record: the catalog id for the built-in store, as records before other stores were.
 * Lowercased like [Store]'s equality, so a store re-added with other capitals finds its installs.
 */
fun installKey(
    store: Store,
    id: String,
) = if (store.reviewed) id else "${store.repo.lowercase()}:$id"

/** The installed file's name; two stores' widgets with the same id get different files. */
fun installFile(
    store: Store,
    author: String,
    name: String,
): String {
    if (store.reviewed) return "market-$author-$name.owl"
    val prefix = store.repo.lowercase().replace(Regex("[^a-z0-9-]"), "-")
    return "market-$prefix-$author-$name.owl"
}
