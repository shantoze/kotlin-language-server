package org.javacs.kt.classpath

import org.javacs.kt.LOG
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.nio.file.Paths

private const val MAX_PATH_LENGTH = 2047

private object ClassPathMetadataCache : IntIdTable() {
    val includesSources = bool("includessources")
    val buildFileVersion = long("buildfileversion").nullable()
}

private object ClassPathCacheEntry : IntIdTable() {
    val compiledJar = varchar("compiledjar", length = MAX_PATH_LENGTH)
    val sourceJar = varchar("sourcejar", length = MAX_PATH_LENGTH).nullable()
}

private object BuildScriptClassPathCacheEntry : IntIdTable() {
    val jar = varchar("jar", length = MAX_PATH_LENGTH)
}

/** A classpath resolver that caches another resolver */
internal class CachedClassPathResolver(
    private val wrapped: ClassPathResolver,
    private val db: Database
) : ClassPathResolver {
    override val resolverType: String get() = "Cached + ${wrapped.resolverType}"

    // Serializes all cache transactions. The classpath and build-script classpath
    // resolvers can be called concurrently (see CompilerClassPath.refresh), and
    // both replace whole tables (deleteAll + insert), which otherwise races and
    // leaves the cache in an inconsistent state (e.g. "id is not in record set").
    private val cacheLock = Any()

    private var cachedClassPathEntries: Set<ClassPathEntry>
        get() = synchronized(cacheLock) {
            transaction(db) {
                ClassPathCacheEntry.selectAll().map {
                    ClassPathEntry(
                        compiledJar = Paths.get(it[ClassPathCacheEntry.compiledJar]),
                        sourceJar = it[ClassPathCacheEntry.sourceJar]?.let(Paths::get)
                    )
                }.toSet()
            }
        }
        set(newEntries) = synchronized(cacheLock) {
            transaction(db) {
                ClassPathCacheEntry.deleteAll()
                ClassPathCacheEntry.batchInsert(newEntries) { entry ->
                    this[ClassPathCacheEntry.compiledJar] = entry.compiledJar.toString()
                    this[ClassPathCacheEntry.sourceJar] = entry.sourceJar?.toString()
                }
            }
        }

    private var cachedBuildScriptClassPathEntries: Set<Path>
        get() = synchronized(cacheLock) {
            transaction(db) { BuildScriptClassPathCacheEntry.selectAll().map { Paths.get(it[BuildScriptClassPathCacheEntry.jar]) }.toSet() }
        }
        set(newEntries) = synchronized(cacheLock) {
            transaction(db) {
                BuildScriptClassPathCacheEntry.deleteAll()
                BuildScriptClassPathCacheEntry.batchInsert(newEntries) { path ->
                    this[BuildScriptClassPathCacheEntry.jar] = path.toString()
                }
            }
        }

    private var cachedClassPathMetadata
        get() = synchronized(cacheLock) {
            transaction(db) {
                ClassPathMetadataCache.selectAll().map {
                    ClasspathMetadata(
                        includesSources = it[ClassPathMetadataCache.includesSources],
                        buildFileVersion = it[ClassPathMetadataCache.buildFileVersion]
                    )
                }.firstOrNull()
            }
        }
        set(newClassPathMetadata) = synchronized(cacheLock) {
            transaction(db) {
                ClassPathMetadataCache.deleteAll()
                val newClassPathMetadataRow = newClassPathMetadata ?: ClasspathMetadata()
                ClassPathMetadataCache.insert {
                    it[includesSources] = newClassPathMetadataRow.includesSources
                    it[buildFileVersion] = newClassPathMetadataRow.buildFileVersion
                }
            }
        }

    init {
        transaction(db) {
            SchemaUtils.createMissingTablesAndColumns(
                ClassPathMetadataCache, ClassPathCacheEntry, BuildScriptClassPathCacheEntry
            )
        }
    }

    override val classpath: Set<ClassPathEntry> get() {
        val cached = try {
            cachedClassPathEntries
        } catch (e: Exception) {
            // The cache can be corrupted (e.g. by concurrent writers in older versions).
            // Fall back to a fresh resolution instead of returning an empty classpath.
            LOG.warn("[KLS-DEBUG] Cache read failed ({}), clearing cache and resolving fresh", e.message)
            clearCache()
            emptySet()
        }
        if (cached.isNotEmpty() && !dependenciesChanged()) {
            LOG.info("Classpath has not changed. Fetching from cache")
            val androidFromCache = cached.map { c -> c.compiledJar.toString() }.filter { s -> s.contains("android.jar") }
            LOG.info("[KLS-DEBUG] Cache returned {} entries, {} android.jar: {}", cached.size, androidFromCache.size, androidFromCache)
            return cached
        }

        LOG.info("Cached classpath is outdated or not found. Resolving again")

        val newClasspath = wrapped.classpath
        val androidFromWrapped = newClasspath.map { c -> c.compiledJar.toString() }.filter { s -> s.contains("android.jar") }
        LOG.info("[KLS-DEBUG] Wrapped resolver returned {} entries, {} android.jar: {}", newClasspath.size, androidFromWrapped.size, androidFromWrapped)
        updateClasspathCache(newClasspath, false)

        return newClasspath
    }

    private fun clearCache() {
        synchronized(cacheLock) {
            transaction(db) {
                ClassPathCacheEntry.deleteAll()
                BuildScriptClassPathCacheEntry.deleteAll()
                ClassPathMetadataCache.deleteAll()
            }
        }
    }

    override val buildScriptClasspath: Set<Path> get() {
        if (!dependenciesChanged()) {
            LOG.info("Build script classpath has not changed. Fetching from cache")
            return cachedBuildScriptClassPathEntries
        }

        LOG.info("Cached build script classpath is outdated or not found. Resolving again")

        val newBuildScriptClasspath = wrapped.buildScriptClasspath

        updateBuildScriptClasspathCache(newBuildScriptClasspath)
        return newBuildScriptClasspath
    }

    override val classpathWithSources: Set<ClassPathEntry> get() {
        cachedClassPathMetadata?.let { if (!dependenciesChanged() && it.includesSources) return cachedClassPathEntries }

        val newClasspath = wrapped.classpathWithSources
        updateClasspathCache(newClasspath, true)

        return newClasspath
    }

    override val currentBuildFileVersion: Long get() = wrapped.currentBuildFileVersion

    private fun updateClasspathCache(newClasspathEntries: Set<ClassPathEntry>, includesSources: Boolean) {
        synchronized(cacheLock) {
            transaction(db) {
                cachedClassPathEntries = newClasspathEntries
                cachedClassPathMetadata = cachedClassPathMetadata?.copy(
                    includesSources = includesSources,
                    buildFileVersion = currentBuildFileVersion
                ) ?: ClasspathMetadata()
            }
        }
    }

    private fun updateBuildScriptClasspathCache(newClasspath: Set<Path>) {
        synchronized(cacheLock) {
            transaction(db) {
                cachedBuildScriptClassPathEntries = newClasspath
                cachedClassPathMetadata = cachedClassPathMetadata?.copy(
                    buildFileVersion = currentBuildFileVersion
                ) ?: ClasspathMetadata()
            }
        }
    }

    private fun dependenciesChanged(): Boolean {
        synchronized(cacheLock) {
            return (cachedClassPathMetadata?.buildFileVersion ?: 0) < wrapped.currentBuildFileVersion
        }
    }
}

private data class ClasspathMetadata(
    val includesSources: Boolean = false,
    val buildFileVersion: Long? = null
)
