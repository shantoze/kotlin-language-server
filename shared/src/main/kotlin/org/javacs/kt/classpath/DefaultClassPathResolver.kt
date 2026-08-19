package org.javacs.kt.classpath

import org.javacs.kt.LOG
import org.jetbrains.exposed.sql.Database
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.FileSystems

fun defaultClassPathResolver(workspaceRoots: Collection<Path>, db: Database? = null): ClassPathResolver {
    val childResolver = WithStdlibResolver(
        ShellClassPathResolver.global(workspaceRoots.firstOrNull())
            .or(workspaceRoots.asSequence().flatMap { workspaceResolvers(it) }.joined)
    ).or(BackupClassPathResolver)

    return db?.let { CachedClassPathResolver(childResolver, it) } ?: childResolver
}

/** Searches the workspace for all files that could provide classpath info. */
private fun workspaceResolvers(workspaceRoot: Path): Sequence<ClassPathResolver> {
    val gitignore = ignoredPathPatterns(workspaceRoot, workspaceRoot.resolve(".gitignore"))
    val klsIgnore = ignoredPathPatterns(workspaceRoot, workspaceRoot.resolve(".kls/ignore"))
    LOG.debug("Classpath search ignore patterns: {} gitignore + {} .kls/ignore", gitignore.size, klsIgnore.size)
    val ignored: List<PathMatcher> = gitignore + klsIgnore
    return folderResolvers(workspaceRoot, ignored).asSequence()
}

/** Searches the folder for all build-files. */
private fun folderResolvers(root: Path, ignored: List<PathMatcher>): Collection<ClassPathResolver> =
    root.toFile()
        .walk()
        .onEnter { file ->
            val keep = !isIgnored(root, ignored, file.toPath())
            if (!keep && file.isDirectory) LOG.debug("Folder walk: SKIP ignored dir {}", file.path)
            keep
        }
        .mapNotNull {
            asClassPathProvider(it.toPath())?.also { p ->
                LOG.debug("Folder walk: found classpath provider at {}", it.path)
            }
        }
        .toList()

/**
 * Tests whether the given file matches any ignore pattern.
 *
 * Patterns are matched per path segment relative to the workspace root (same
 * semantics as SourceExclusions). This avoids Windows backslash-escaping
 * issues of absolute-path globs like "glob:D:...pattern".
 */
private fun isIgnored(root: Path, ignored: List<PathMatcher>, file: Path): Boolean =
    ignored.any { matcher ->
        root.relativize(file)
            .flatMap { it } // Extract path segments
            .any(matcher::matches)
    }

/** Tries to read glob patterns from a gitignore. */
private fun ignoredPathPatterns(root: Path, gitignore: Path): List<PathMatcher> =
    gitignore.toFile()
        .takeIf { it.exists() }
        ?.readLines()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() && !it.startsWith("#") }
        ?.map { it.removeSuffix("/") }
        ?.let { it + listOf(
            // Patterns that are ignored by default
            ".git"
        ) }
        ?.mapNotNull { try {
            LOG.debug("Adding classpath ignore pattern '{}' from {}", it, gitignore)
            // Matched per path segment relative to the workspace root (see isIgnored)
            FileSystems.getDefault().getPathMatcher("glob:$it")
        } catch (e: Exception) {
            LOG.warn("Did not recognize gitignore pattern: '{}' ({})", it, e.message)
            null
        } }
        ?: emptyList()

/** Tries to create a classpath resolver from a file using as many sources as possible */
private fun asClassPathProvider(path: Path): ClassPathResolver? =
    MavenClassPathResolver.maybeCreate(path)
        ?: GradleClassPathResolver.maybeCreate(path)
        ?: ShellClassPathResolver.maybeCreate(path)
